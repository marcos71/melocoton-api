package ar.melocoton.shared.infrastructure.security

import ar.melocoton.shared.infrastructure.web.ErrorResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Security rejects a request before it reaches a controller, so the GlobalExceptionHandler never
 * sees it. These two write the same error shape as every other error (`02`, 7.1; DI-20).
 */
@Component
class SecurityErrorResponses(private val json: ObjectMapper) : AuthenticationEntryPoint, AccessDeniedHandler {

    /** No token, or one that is invalid, expired or no longer valid: 401. */
    override fun commence(request: HttpServletRequest, response: HttpServletResponse, e: AuthenticationException) {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        write(response, HttpStatus.UNAUTHORIZED, "not_authenticated", "Tenés que iniciar sesión.")
    }

    /** Valid session, but the role may not do this: 403 (CP-22). */
    override fun handle(request: HttpServletRequest, response: HttpServletResponse, e: AccessDeniedException) {
        write(response, HttpStatus.FORBIDDEN, "role_not_allowed", "Tu rol no tiene permiso para hacer esto.")
    }

    private fun write(response: HttpServletResponse, status: HttpStatus, code: String, message: String) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        json.writeValue(response.writer, ErrorResponse.of(code, message))
    }
}
