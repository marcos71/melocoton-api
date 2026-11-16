package ar.melocoton.architecture

import com.tngtech.archunit.base.DescribedPredicate.alwaysTrue
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.RestController

/**
 * Architecture rules of DI-18 and DI-21 (plan, section 3.1), parameterized by the root package
 * so they can be checked against the real code and against examples that break them.
 *
 * Layout: `<root>.<module>.{domain, application, infrastructure}`; `shared` is the
 * cross-cutting module. It is a single hexagon (DI-21): modules are folders by topic, and a
 * use case may use another module's ports and domain, but never its infrastructure.
 */
class ArchitectureRules(private val root: String) {

    private val shared = "$root.shared"

    /** The domain is plain Kotlin: no Spring, no JPA, no other layer. Only the shared domain is allowed. */
    fun domainIsPlainKotlin(): ArchRule = classes()
        .that().resideInAPackage("$root..domain..")
        .should().onlyDependOnClassesThat(
            resideInAnyPackage("java..", "kotlin..", "org.jetbrains.annotations..", "$root..domain.."),
        )
        .because("the domain holds the business rules in plain Kotlin (DI-18)")

    /** A module's domain does not use another module's domain; data comes in through parameters. */
    fun domainsAreIndependent(): ArchRule = slices()
        .matching("$root.(*).domain..")
        .should().notDependOnEachOther()
        .ignoreDependency(alwaysTrue(), resideInAPackage("$shared.domain.."))
        .because("a module's rules get other modules' data through parameters (DI-21)")

    /** Use cases do not know adapters or JPA. */
    fun applicationDoesNotUseInfrastructure(): ArchRule = noClasses()
        .that().resideInAPackage("$root..application..")
        .should().dependOnClassesThat(
            resideInAnyPackage("$root..infrastructure..", "jakarta.persistence..", "org.hibernate.."),
        )
        .because("use cases reach the outside only through their ports (DI-18)")

    /** Spring in use cases only for @Service and @Transactional. */
    fun applicationUsesSpringOnlyForServiceAndTransactions(): ArchRule = noClasses()
        .that().resideInAPackage("$root..application..")
        .should().dependOnClassesThat(
            resideInAPackage("org.springframework..")
                .and(resideOutsideOfPackage("org.springframework.stereotype.."))
                .and(resideOutsideOfPackage("org.springframework.transaction.annotation..")),
        )
        .because("the application layer may use Spring only for @Service and @Transactional (plan, 3.1)")

    /** JPA lives only in persistence adapters. */
    fun jpaOnlyInPersistenceAdapters(): ArchRule = noClasses()
        .that().resideOutsideOfPackage("$root..infrastructure.persistence..")
        .and().resideInAPackage("$root..")
        .should().dependOnClassesThat(resideInAnyPackage("jakarta.persistence..", "org.hibernate.."))
        .because("JPA entities and repositories are an adapter detail (DI-04)")

    /** REST controllers live only in web adapters. */
    fun controllersOnlyInWebAdapters(): ArchRule = classes()
        .that().areAnnotatedWith(RestController::class.java)
        .and().resideInAPackage("$root..")
        .should().resideInAPackage("$root..infrastructure.web..")
        .because("controllers are input adapters (DI-18)")

    /** No module reaches into another module's adapters. */
    fun modulesDoNotUseOtherModulesInfrastructure(): ArchRule = slices()
        .matching("$root.(*)..")
        .should().notDependOnEachOther()
        .ignoreDependency(alwaysTrue(), resideInAnyPackage("$root..application..", "$root..domain..", "$shared.."))
        .because("another module's persistence and controllers are its own detail (DI-21)")

    /**
     * A use case injects repositories, not other use cases. No cycle rule is needed between modules:
     * Spring already refuses to start when two beans need each other.
     */
    fun useCasesDoNotDependOnOtherUseCases(): ArchRule = noClasses()
        .that().resideInAPackage("$root..application..")
        .and().areAnnotatedWith(Service::class.java)
        .should().dependOnClassesThat(resideInAPackage("$root..application..").and(annotatedWith(Service::class.java)))
        .because("a use case coordinates repositories and the domain, not other use cases (DI-21)")

    /** `shared` is used by every module and uses none of them. */
    fun sharedDoesNotDependOnModules(): ArchRule = noClasses()
        .that().resideInAPackage("$shared..")
        .should().dependOnClassesThat(resideInAPackage("$root..").and(resideOutsideOfPackage("$shared..")))
        .because("shared is cross-cutting: every module may use it, it uses no module")

    fun all(): List<ArchRule> = listOf(
        domainIsPlainKotlin(),
        domainsAreIndependent(),
        applicationDoesNotUseInfrastructure(),
        applicationUsesSpringOnlyForServiceAndTransactions(),
        jpaOnlyInPersistenceAdapters(),
        controllersOnlyInWebAdapters(),
        modulesDoNotUseOtherModulesInfrastructure(),
        useCasesDoNotDependOnOtherUseCases(),
        sharedDoesNotDependOnModules(),
    )
}
