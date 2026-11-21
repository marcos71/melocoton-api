package ar.melocoton.students.domain

import ar.melocoton.shared.domain.BusinessErrorKind
import ar.melocoton.shared.domain.BusinessRuleViolation
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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

/** The monthly start date is before the first day of the current month or more than one month ahead (DI-58). */
class InvalidMonthlyStartDate(earliest: LocalDate, latest: LocalDate) : BusinessRuleViolation(
    code = "invalid_monthly_start_date",
    message = "La fecha de ingreso tiene que estar entre el ${earliest.format(DATE_FORMAT)} y el ${latest.format(DATE_FORMAT)}.",
    kind = BusinessErrorKind.INVALID,
)

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d/M/yyyy")

/**
 * A monthly student starts between the first day of [today]'s month and one month after [today], both
 * inclusive (DI-58): the app starts without history, so an older date would only create debts that do not exist (RN-12).
 *
 * @throws InvalidMonthlyStartDate the date is outside that range
 */
fun requireValidMonthlyStartDate(monthlyStartDate: LocalDate, today: LocalDate) {
    val earliest = today.withDayOfMonth(1)
    val latest = today.plusMonths(1)
    if (monthlyStartDate.isBefore(earliest) || monthlyStartDate.isAfter(latest)) {
        throw InvalidMonthlyStartDate(earliest, latest)
    }
}
