package ar.melocoton.shared.infrastructure.web

import ar.melocoton.shared.domain.BusinessErrorKind
import ar.melocoton.shared.domain.BusinessRuleViolation
import org.slf4j.LoggerFactory
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.dao.QueryTimeoutException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.sql.SQLException

/**
 * Turns every exception into the API error shape. Messages are in Spanish because a
 * person reads them; internal details (stack traces, SQL) never reach the client.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BusinessRuleViolation::class)
    fun businessRule(e: BusinessRuleViolation) = respond(e.kind.httpStatus(), e.code, e.message)

    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        HandlerMethodValidationException::class,
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
        MissingServletRequestParameterException::class,
    )
    fun invalidRequest(e: Exception) =
        respond(HttpStatus.BAD_REQUEST, "validation_error", "Los datos enviados no son válidos.")

    @ExceptionHandler(NoResourceFoundException::class)
    fun notFound(e: NoResourceFoundException) =
        respond(HttpStatus.NOT_FOUND, "not_found", "No encontramos lo que buscás.")

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun methodNotAllowed(e: HttpRequestMethodNotSupportedException) =
        respond(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "Esta operación no está permitida.")

    /** A lock or statement timeout expired: the transaction was rolled back (`02`, 7.3). */
    @ExceptionHandler(PessimisticLockingFailureException::class, QueryTimeoutException::class)
    fun busy(e: Exception): ResponseEntity<ErrorResponse> {
        log.warn("Database busy: {}", e.message)
        return respond(
            HttpStatus.SERVICE_UNAVAILABLE,
            "service_busy",
            "El sistema está ocupado en este momento. Probá de nuevo en unos segundos.",
        )
    }

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<ErrorResponse> {
        // Spring does not always translate PostgreSQL timeouts (it depends on whether the
        // query went through JPA or JDBC), so the SQL state is checked as a fallback.
        if (e.isDatabaseTimeout()) return busy(e)
        log.error("Unexpected error", e)
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Ocurrió un error inesperado.")
    }

    private fun respond(status: HttpStatus, code: String, message: String) =
        ResponseEntity.status(status).body(ErrorResponse.of(code, message))
}

/** PostgreSQL SQL states for lock_timeout (55P03) and statement_timeout (57014). */
private val TIMEOUT_SQL_STATES = setOf("55P03", "57014")

/** True when any cause in the chain is a PostgreSQL lock or statement timeout. */
fun Throwable.isDatabaseTimeout(): Boolean =
    generateSequence(this) { it.cause }
        .filterIsInstance<SQLException>()
        .any { it.sqlState in TIMEOUT_SQL_STATES }

/** Mapping from business error kind to HTTP status (docs/glosario_en.md). */
fun BusinessErrorKind.httpStatus(): HttpStatus = when (this) {
    BusinessErrorKind.INVALID -> HttpStatus.BAD_REQUEST
    BusinessErrorKind.NOT_FOUND -> HttpStatus.NOT_FOUND
    BusinessErrorKind.CONFLICT -> HttpStatus.CONFLICT
    BusinessErrorKind.LIMIT_REACHED -> HttpStatus.TOO_MANY_REQUESTS
}
