package ar.melocoton.auth.domain

import ar.melocoton.shared.domain.BusinessErrorKind
import ar.melocoton.shared.domain.BusinessRuleViolation

/** Only a length range, no composition rules (DI-29, DI-33; RNF-03). */
object PasswordPolicy {
    const val MIN_LENGTH = 10

    /** Upper limit so nobody can make the server hash a huge text (DI-33). */
    const val MAX_LENGTH = 128

    /** Fails with [WeakPassword] if [password] is out of range. Counts characters, not bytes. */
    fun check(password: String) {
        val length = password.codePointCount(0, password.length)
        if (length !in MIN_LENGTH..MAX_LENGTH) throw WeakPassword()
    }
}

/** The new password does not meet [PasswordPolicy]. Same code as any invalid field (catalog, set-password). */
class WeakPassword : BusinessRuleViolation(
    code = "validation_error",
    message = "La contraseña tiene que tener entre ${PasswordPolicy.MIN_LENGTH} y ${PasswordPolicy.MAX_LENGTH} caracteres.",
    kind = BusinessErrorKind.INVALID,
)
