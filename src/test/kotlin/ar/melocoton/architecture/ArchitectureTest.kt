package ar.melocoton.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** The real production code follows the architecture rules of DI-18. */
class ArchitectureTest {

    private val productionCode = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("ar.melocoton")

    @Test
    fun `the production code was imported`() {
        // Guards against the rules passing only because nothing was checked.
        assertThat(productionCode.map { it.name }).contains(
            "ar.melocoton.shared.domain.BusinessRuleViolation",
            "ar.melocoton.shared.application.CheckHealth",
            "ar.melocoton.shared.infrastructure.web.HealthController",
        )
    }

    @TestFactory
    fun `production code follows every architecture rule`() =
        ArchitectureRules("ar.melocoton").all().map { rule ->
            DynamicTest.dynamicTest(rule.description) { rule.check(productionCode) }
        }
}
