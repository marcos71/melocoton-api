package ar.melocoton.slots.domain

import ar.melocoton.shared.domain.BusinessErrorKind
import ar.melocoton.shared.domain.BusinessRuleViolation
import java.time.DayOfWeek
import java.time.LocalTime

/** The slot data is not valid: a field out of range, or a start that is not before the end. */
class InvalidSlot(message: String) : BusinessRuleViolation(
    code = "validation_error",
    message = message,
    kind = BusinessErrorKind.INVALID,
)

/** The id does not belong to a slot. */
class SlotNotFound : BusinessRuleViolation(
    code = "not_found",
    message = "No existe ese turno.",
    kind = BusinessErrorKind.NOT_FOUND,
)

/** The slot is inactive, so it cannot be edited (DI-45). */
class SlotInactive : BusinessRuleViolation(
    code = "status_conflict",
    message = "El turno está inactivo y no se puede editar.",
    kind = BusinessErrorKind.CONFLICT,
)

/** The slot is deactivated, so it takes no new bookings (RF-08, RF-09). */
class SlotNotAcceptingBookings : BusinessRuleViolation(
    code = "slot_inactive",
    message = "El turno está desactivado: no admite reservas nuevas.",
    kind = BusinessErrorKind.CONFLICT,
)

/** The slot overlaps another active slot of the same day (DI-25). */
class SlotOverlap(conflicting: Slot) : BusinessRuleViolation(
    code = "slot_overlap",
    message = "Se superpone con el turno del ${conflicting.dayOfWeek.spanishName()} " +
        "de ${conflicting.startTime} a ${conflicting.endTime}.",
    kind = BusinessErrorKind.CONFLICT,
    details = SlotOverlapDetails(
        slotId = conflicting.id,
        dayOfWeek = conflicting.dayOfWeek.value,
        startTime = conflicting.startTime,
        endTime = conflicting.endTime,
    ),
)

/** The slot already has bookings, so its day and time cannot change (DI-45). */
class SlotHasBookings : BusinessRuleViolation(
    code = "slot_has_bookings",
    message = "El turno ya tiene reservas: solo se puede cambiar el cupo.",
    kind = BusinessErrorKind.CONFLICT,
)

/** The slot already has [Slot.CAPACITY] fixed students (DI-66). */
class FixedStudentsFull(fixedStudents: Int) : BusinessRuleViolation(
    code = "fixed_students_full",
    message = "El turno ya tiene ${Slot.CAPACITY} alumnos fijos, que es su cupo.",
    kind = BusinessErrorKind.CONFLICT,
    details = FixedStudentsFullDetails(fixedStudents, Slot.CAPACITY),
)

/** `details` of `fixed_students_full`: the other fixed students of the slot and its capacity. */
data class FixedStudentsFullDetails(val fixedStudents: Int, val capacity: Int)

/** `details` of `slot_overlap`: the slot it collides with. */
data class SlotOverlapDetails(val slotId: Long, val dayOfWeek: Int, val startTime: LocalTime, val endTime: LocalTime)

private val SPANISH_DAYS = mapOf(
    DayOfWeek.MONDAY to "lunes",
    DayOfWeek.TUESDAY to "martes",
    DayOfWeek.WEDNESDAY to "miércoles",
    DayOfWeek.THURSDAY to "jueves",
    DayOfWeek.FRIDAY to "viernes",
    DayOfWeek.SATURDAY to "sábado",
    DayOfWeek.SUNDAY to "domingo",
)

/** Day name for the messages people read (DI-20). */
fun DayOfWeek.spanishName(): String = SPANISH_DAYS.getValue(this)
