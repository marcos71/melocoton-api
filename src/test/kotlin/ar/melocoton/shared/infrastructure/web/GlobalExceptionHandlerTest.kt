package ar.melocoton.shared.infrastructure.web

import ar.melocoton.shared.domain.BusinessErrorKind
import ar.melocoton.shared.domain.BusinessRuleViolation
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.InsufficientAuthenticationException
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.sql.DataSource

/**
 * Every error leaves the API as `{ "error": { "code", "message" } }` with the right status
 * (`02`, 7.1 and 7.3). The timeout cases run against the real test database.
 * The example endpoints live under /api, so the requests carry a logged-in user.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class GlobalExceptionHandlerTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val dataSource: DataSource,
) {

    private fun ResultActionsDsl.isError(status: HttpStatus, code: String) = andExpect {
        status { isEqualTo(status.value()) }
        content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
        jsonPath("$.error.code") { value(code) }
        jsonPath("$.error.message") { isNotEmpty() }
    }

    @Test
    fun `a business conflict returns 409 with its code and Spanish message`() {
        mvc.get("$BASE/conflict").isError(HttpStatus.CONFLICT, "capacity_full")
            .andExpect { jsonPath("$.error.message") { value("No quedan lugares en esta clase.") } }
    }

    @Test
    fun `a reached limit returns 429`() {
        mvc.get("$BASE/limit").isError(HttpStatus.TOO_MANY_REQUESTS, "monthly_limit_reached")
    }

    @Test
    fun `each business error kind maps to its HTTP status`() {
        assertThat(BusinessErrorKind.entries.associateWith { it.httpStatus() }).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                BusinessErrorKind.INVALID to HttpStatus.BAD_REQUEST,
                BusinessErrorKind.UNAUTHENTICATED to HttpStatus.UNAUTHORIZED,
                BusinessErrorKind.NOT_FOUND to HttpStatus.NOT_FOUND,
                BusinessErrorKind.CONFLICT to HttpStatus.CONFLICT,
                BusinessErrorKind.LIMIT_REACHED to HttpStatus.TOO_MANY_REQUESTS,
            ),
        )
    }

    @Test
    fun `a body that fails validation returns 400`() {
        mvc.post("$BASE/body") {
            contentType = MediaType.APPLICATION_JSON
            content = """{ "name": "" }"""
        }.isError(HttpStatus.BAD_REQUEST, "validation_error")
    }

    @Test
    fun `malformed JSON returns 400`() {
        mvc.post("$BASE/body") {
            contentType = MediaType.APPLICATION_JSON
            content = "{ not json"
        }.isError(HttpStatus.BAD_REQUEST, "validation_error")
    }

    @Test
    fun `an unknown route returns 404`() {
        mvc.get("/api/v1/does-not-exist").isError(HttpStatus.NOT_FOUND, "not_found")
    }

    @Test
    fun `a wrong HTTP method returns 405`() {
        mvc.delete("$BASE/conflict").isError(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed")
    }

    @Test
    fun `an unexpected error returns 500 without internal details`() {
        mvc.get("$BASE/boom").isError(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error")
            .andExpect { content { string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))) } }
    }

    @Test
    fun `CP-22 a role check that fails inside a controller returns 403, not 500`() {
        mvc.get("$BASE/denied").isError(HttpStatus.FORBIDDEN, "role_not_allowed")
    }

    @Test
    fun `an authentication failure inside a controller returns 401, not 500`() {
        mvc.get("$BASE/unauthenticated").isError(HttpStatus.UNAUTHORIZED, "not_authenticated")
    }

    @Test
    fun `a query longer than the statement timeout returns 503`() {
        mvc.get("$BASE/slow-query").isError(HttpStatus.SERVICE_UNAVAILABLE, "service_busy")
    }

    @Test
    fun `waiting for a lock longer than the lock timeout returns 503`() {
        // Another connection holds the lock, as a concurrent transaction would.
        dataSource.connection.use { holder ->
            holder.createStatement().execute("SELECT pg_advisory_lock($LOCK_KEY)")
            try {
                mvc.get("$BASE/locked").isError(HttpStatus.SERVICE_UNAVAILABLE, "service_busy")
            } finally {
                holder.createStatement().execute("SELECT pg_advisory_unlock($LOCK_KEY)")
            }
        }
    }

    @TestConfiguration
    class Config {
        @Bean
        fun errorTestController(jdbc: JdbcTemplate) = ErrorTestController(jdbc)
    }

    /** Test-only endpoints that raise each kind of error. Not part of the real API. */
    @RestController
    @RequestMapping(BASE)
    class ErrorTestController(private val jdbc: JdbcTemplate) {

        data class Body(@field:NotBlank val name: String)

        @GetMapping("/conflict")
        fun conflict(): Nothing = throw BusinessRuleViolation("capacity_full", "No quedan lugares en esta clase.")

        @GetMapping("/limit")
        fun limit(): Nothing = throw BusinessRuleViolation(
            "monthly_limit_reached", "Usaste las 10 consultas de este mes.", BusinessErrorKind.LIMIT_REACHED,
        )

        @PostMapping("/body")
        fun body(@Valid @RequestBody body: Body) = body

        @GetMapping("/boom")
        fun boom(): Nothing = throw IllegalStateException("secret internal detail")

        @GetMapping("/denied")
        fun denied(): Nothing = throw AccessDeniedException("role not allowed")

        @GetMapping("/unauthenticated")
        fun unauthenticated(): Nothing = throw InsufficientAuthenticationException("no session")

        @GetMapping("/slow-query")
        fun slowQuery() = jdbc.execute("SELECT pg_sleep(6)")

        @GetMapping("/locked")
        fun locked() = jdbc.execute("SELECT pg_advisory_lock($LOCK_KEY)")
    }

    companion object {
        const val BASE = "/api/v1/test-errors"
        const val LOCK_KEY = 4242
    }
}
