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

/** The action does not fit the student's enrollment type: a fixed slot is only for monthly, a drop-in class only for drop-in (RN-03, DI-44). */
class EnrollmentTypeMismatch(message: String) : BusinessRuleViolation(
    code = "enrollment_type_mismatch",
    message = message,
    kind = BusinessErrorKind.CONFLICT,
)

/** The student is inactive: no slots or bookings are given to them. */
class StudentInactive : BusinessRuleViolation(
    code = "student_inactive",
    message = "El alumno está inactivo.",
    kind = BusinessErrorKind.CONFLICT,
)
