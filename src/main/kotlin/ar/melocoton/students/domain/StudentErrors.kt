package ar.melocoton.students.domain

import ar.melocoton.shared.domain.BusinessErrorKind
import ar.melocoton.shared.domain.BusinessRuleViolation

/** The student data is not valid: a required text is blank, or the modality and the start date do not fit. */
class InvalidStudent(message: String) : BusinessRuleViolation(
    code = "validation_error",
    message = message,
    kind = BusinessErrorKind.INVALID,
)

/** The id does not belong to a student: it does not exist, or it is another role's account (DI-34, DI-46). */
class StudentNotFound : BusinessRuleViolation(
    code = "not_found",
    message = "No existe ese alumno.",
    kind = BusinessErrorKind.NOT_FOUND,
)
