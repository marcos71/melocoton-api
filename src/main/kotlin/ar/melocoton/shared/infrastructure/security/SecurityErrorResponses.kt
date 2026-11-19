package ar.melocoton.shared.infrastructure.security

import ar.melocoton.shared.domain.BusinessErrorKind
import ar.melocoton.shared.infrastructure.web.ErrorResponse
import ar.melocoton.shared.infrastructure.web.httpStatus
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
 * sees it. These write the same error shape as every other error (`02`, 7.1; DI-20). The rate
 * limit filters (RNF-25) use [tooManyRequests] for the same reason.
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

    /** Too many requests from this user or IP (RNF-25, DI-36): 429, with no Retry-After. */
    fun tooManyRequests(response: HttpServletResponse) = write(
        response,
        BusinessErrorKind.LIMIT_REACHED.httpStatus(),
        "too_many_requests",
        "Hiciste demasiados pedidos seguidos. Esperá un minuto y probá de nuevo.",
    )

    private fun write(response: HttpServletResponse, status: HttpStatus, code: String, message: String) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        json.writeValue(response.writer, ErrorResponse.of(code, message))
    }
}
