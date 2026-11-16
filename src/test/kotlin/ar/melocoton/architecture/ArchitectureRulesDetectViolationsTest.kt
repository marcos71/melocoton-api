package ar.melocoton.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchRule
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Each architecture rule really catches what it forbids, and none rejects a correct design.
 * Example code lives in the test-only `archfixtures` packages (see its README).
 */
class ArchitectureRulesDetectViolationsTest {

    private fun example(name: String): Pair<ArchitectureRules, JavaClasses> {
        val root = "archfixtures.$name"
        val classes = ClassFileImporter().importPackages(root)
        assertThat(classes).describedAs("example classes for $root").isNotEmpty
        return ArchitectureRules(root) to classes
    }

    private fun assertViolates(name: String, rule: (ArchitectureRules) -> ArchRule) {
        val (rules, classes) = example(name)
        assertThatThrownBy { rule(rules).check(classes) }
            .describedAs("the rule should reject archfixtures.$name")
            .isInstanceOf(AssertionError::class.java)
            // ArchUnit also fails when a rule checks no classes; only a real violation counts.
            .hasMessageContaining("was violated")
    }

    @TestFactory
    fun `a correct design passes every rule`(): List<DynamicTest> {
        val (rules, classes) = example("good")
        return rules.all().map { rule -> DynamicTest.dynamicTest(rule.description) { rule.check(classes) } }
    }

    @Test
    fun `the domain cannot use Spring`() = assertViolates("domainspring") { it.domainIsPlainKotlin() }

    @Test
    fun `a module's domain cannot use another module's domain`() =
        assertViolates("domaincross") { it.domainsAreIndependent() }

    @Test
    fun `a use case cannot use an adapter`() =
        assertViolates("appinfra") { it.applicationDoesNotUseInfrastructure() }

    @Test
    fun `a use case cannot use Spring beyond Service and Transactional`() =
        assertViolates("appspring") { it.applicationUsesSpringOnlyForServiceAndTransactions() }

    @Test
    fun `JPA cannot be used outside persistence adapters`() =
        assertViolates("jpaoutside") { it.jpaOnlyInPersistenceAdapters() }

    @Test
    fun `a controller cannot live outside web adapters`() =
        assertViolates("controllerplace") { it.controllersOnlyInWebAdapters() }

    @Test
    fun `a module cannot use another module's adapters`() =
        assertViolates("crossinfra") { it.modulesDoNotUseOtherModulesInfrastructure() }

    @Test
    fun `a use case cannot inject another use case`() =
        assertViolates("usecasecall") { it.useCasesDoNotDependOnOtherUseCases() }

    @Test
    fun `shared cannot depend on a module`() =
        assertViolates("sharedup") { it.sharedDoesNotDependOnModules() }

    @Test
    fun `every rule has a violation test`() {
        // Adding a rule to ArchitectureRules.all() without a test above should fail here.
        assertThat(ArchitectureRules("x").all()).hasSize(9)
    }
}
