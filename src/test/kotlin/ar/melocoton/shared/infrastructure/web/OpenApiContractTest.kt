package ar.melocoton.shared.infrastructure.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * The committed contract (openapi/openapi.json) must match what the API actually serves (DI-11).
 * When the API changes on purpose, regenerate it with `gradlew.bat updateOpenApi` and commit it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiContractTest(@Autowired private val mvc: MockMvc) {

    private val contractFile: Path = Path.of("openapi", "openapi.json")
    private val updateMode = System.getProperty("openapi.update") == "true"
    private val json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build()

    private fun rawContract(): String =
        mvc.get("/v3/api-docs").andExpect { status { isOk() } }
            .andReturn().response.getContentAsString(Charsets.UTF_8)

    // Jackson's pretty printer uses the OS line separator; the contract always uses LF.
    private fun servedContract(): String =
        json.writeValueAsString(json.readTree(rawContract())).replace("\r\n", "\n") + "\n"

    @Test
    fun `the committed OpenAPI contract is up to date`() {
        val served = servedContract()

        if (updateMode) {
            Files.createDirectories(contractFile.parent)
            Files.writeString(contractFile, served)
            return
        }

        assertThat(contractFile)
            .withFailMessage("openapi/openapi.json does not exist. Run: gradlew.bat updateOpenApi")
            .exists()
        val committed = Files.readString(contractFile).replace("\r\n", "\n")
        if (committed != served) {
            // Leave the served version next to the build output, to diff against the committed one.
            val servedCopy = Path.of("build", "openapi", "openapi.served.json")
            Files.createDirectories(servedCopy.parent)
            Files.writeString(servedCopy, served)
        }
        assertThat(committed)
            .withFailMessage(
                "openapi/openapi.json is out of date (served version in build/openapi/openapi.served.json). " +
                    "Run: gradlew.bat updateOpenApi, review and commit it.",
            )
            .isEqualTo(served)
    }

    @Test
    fun `the contract publishes the health endpoint and the error shape`() {
        mvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            jsonPath("$.paths['/api/v1/health'].get") { exists() }
            jsonPath("$.components.schemas.ErrorResponse") { exists() }
            jsonPath("$.components.schemas.ErrorBody.properties.code") { exists() }
            jsonPath("$.components.schemas.ErrorBody.properties.message") { exists() }
        }
    }

    @Test
    fun `only API endpoints are in the contract`() {
        val paths = json.readTree(rawContract()).get("paths").propertyNames()
        assertThat(paths).isNotEmpty.allMatch { it.startsWith("/api/v1/") }
    }

    @Test
    fun `non-null Kotlin fields are required in the contract`() {
        val schemas = json.readTree(rawContract()).get("components").get("schemas")
        fun required(schema: String): List<String> {
            val node = schemas.get(schema).get("required") ?: return emptyList()
            return (0 until node.size()).map { node.get(it).asString() }
        }

        assertThat(required("HealthResponse")).containsExactlyInAnyOrder("status", "database")
        assertThat(required("ErrorResponse")).containsExactly("error")
        assertThat(required("ErrorBody")).containsExactlyInAnyOrder("code", "message")
    }

    @Test
    fun `the contract keeps accents`() {
        assertThat(json.readTree(rawContract()).get("info").get("title").asString()).isEqualTo("Melocotón API")
    }

    @Test
    fun `Swagger UI is served`() {
        mvc.get("/swagger-ui/index.html").andExpect { status { isOk() } }
    }
}
