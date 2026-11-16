package ar.melocoton.shared.infrastructure.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.Base64

class SecuritySupportTest {

    @Test
    fun `DI-28 a signing key shorter than 32 bytes stops the API at startup`() {
        val short = Base64.getEncoder().encodeToString(ByteArray(31))

        assertThatThrownBy { SessionProperties(short, Duration.ofDays(30)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("at least 32 bytes")
    }

    @Test
    fun `a 32-byte signing key is accepted`() {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

        assertThat(SessionProperties(key, Duration.ofDays(30)).key).hasSize(32)
    }

    @Test
    fun `the signing key never shows up when the settings are printed`() {
        val secret = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })

        assertThat(SessionProperties(secret, Duration.ofDays(30)).toString())
            .doesNotContain(secret)
            .contains("<hidden>")
    }

    @Test
    fun `CP-22 a role that may not do something gets 403 role_not_allowed in the error shape`() {
        val response = MockHttpServletResponse()

        SecurityErrorResponses(JsonMapper.builder().build())
            .handle(MockHttpServletRequest(), response, AccessDeniedException("denied"))

        assertThat(response.status).isEqualTo(403)
        assertThat(response.contentType).startsWith("application/json")
        assertThat(response.contentAsString)
            .contains("\"code\":\"role_not_allowed\"")
            .contains("Tu rol no tiene permiso para hacer esto.")
    }
}
