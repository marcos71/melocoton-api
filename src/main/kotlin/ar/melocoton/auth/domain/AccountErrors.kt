package ar.melocoton.auth.domain

import ar.melocoton.shared.domain.BusinessErrorKind
import ar.melocoton.shared.domain.BusinessRuleViolation

/** Another account already uses the email, whatever its role or state (DI-34). */
class EmailAlreadyUsed : BusinessRuleViolation(
    code = "email_already_used",
    message = "Ya hay una cuenta con ese email.",
    kind = BusinessErrorKind.CONFLICT,
)

/** The id does not belong to an assistant teacher (DI-34). */
class AssistantTeacherNotFound : BusinessRuleViolation(
    code = "not_found",
    message = "No existe ese docente secundario.",
    kind = BusinessErrorKind.NOT_FOUND,
)

/** The account is already inactive (DI-34). */
class AccountAlreadyInactive : BusinessRuleViolation(
    code = "status_conflict",
    message = "La cuenta ya está inactiva.",
    kind = BusinessErrorKind.CONFLICT,
)
