package ar.melocoton.shared.domain

/**
 * What kind of business error happened. The domain decides the kind; the web layer
 * maps it to an HTTP status (docs/glosario_en.md), so the domain never knows about HTTP.
 */
enum class BusinessErrorKind { INVALID, NOT_FOUND, CONFLICT, LIMIT_REACHED }

/**
 * A business rule rejected the operation.
 *
 * @param code stable identifier in English that the client can rely on (for example `capacity_full`).
 * @param message text for the person, in Spanish (DI-20).
 */
open class BusinessRuleViolation(
    val code: String,
    override val message: String,
    val kind: BusinessErrorKind = BusinessErrorKind.CONFLICT,
) : RuntimeException(message)
