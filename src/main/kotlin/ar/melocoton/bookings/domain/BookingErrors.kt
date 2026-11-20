package ar.melocoton.bookings.domain

import ar.melocoton.shared.domain.BusinessErrorKind
import ar.melocoton.shared.domain.BusinessRuleViolation

/** The lesson has no free place: no drop-in or make-up can take one (RF-11, DI-43). */
class CapacityFull : BusinessRuleViolation(
    code = "capacity_full",
    message = "No queda lugar en esa clase.",
    kind = BusinessErrorKind.CONFLICT,
)

/** The student already has an active booking in that lesson (DI-41). */
class AlreadyBooked : BusinessRuleViolation(
    code = "already_booked",
    message = "El alumno ya tiene una reserva en esa clase.",
    kind = BusinessErrorKind.CONFLICT,
)

/** The lesson has already started: it cannot be booked or cancelled (RF-09, RF-52, DI-44). */
class LessonAlreadyStarted : BusinessRuleViolation(
    code = "lesson_already_started",
    message = "La clase ya empezó.",
    kind = BusinessErrorKind.CONFLICT,
)

/** The date does not fall on the slot's day, or is too far ahead (DI-44). */
class InvalidLessonDate(message: String) : BusinessRuleViolation(
    code = "invalid_lesson_date",
    message = message,
    kind = BusinessErrorKind.INVALID,
)

/** The booking does not exist, is not the student's, or is not of the expected type. */
class BookingNotFound : BusinessRuleViolation(
    code = "not_found",
    message = "No existe esa reserva.",
    kind = BusinessErrorKind.NOT_FOUND,
)

/** The booking is not in a state that allows the operation (only a scheduled one can be cancelled). */
class BookingStatusConflict : BusinessRuleViolation(
    code = "status_conflict",
    message = "La reserva ya no está programada, así que no se puede cancelar.",
    kind = BusinessErrorKind.CONFLICT,
)
