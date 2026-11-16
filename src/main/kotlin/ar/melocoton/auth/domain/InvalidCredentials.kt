package ar.melocoton.auth.domain

import ar.melocoton.shared.domain.BusinessErrorKind
import ar.melocoton.shared.domain.BusinessRuleViolation

/**
 * Any failed login: unknown email, wrong password, inactive or locked account. The answer
 * is always the same so it never reveals whether an email is registered (DI-30; 02, F2).
 */
class InvalidCredentials : BusinessRuleViolation(
    code = "invalid_credentials",
    message = "Email o contraseña incorrectos. Si dejaste el taller, consultá con la docente.",
    kind = BusinessErrorKind.UNAUTHENTICATED,
)
