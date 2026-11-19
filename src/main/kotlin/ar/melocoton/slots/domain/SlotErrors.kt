package ar.melocoton.slots.domain

import ar.melocoton.shared.domain.BusinessErrorKind
import ar.melocoton.shared.domain.BusinessRuleViolation
import java.time.DayOfWeek
import java.time.LocalDate
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

/** The new capacity is below the occupancy of some future lessons (CU-03 E1, DI-45). */
class CapacityBelowOccupancy(newCapacity: Int, lessons: List<LessonOverCapacity>) : BusinessRuleViolation(
    code = "capacity_below_occupancy",
    message = "No se puede bajar el cupo a $newCapacity: hay ${lessons.size} " +
        (if (lessons.size == 1) "clase futura" else "clases futuras") + " con más lugares ocupados.",
    kind = BusinessErrorKind.CONFLICT,
    details = CapacityBelowOccupancyDetails(lessons),
)

/** `details` of `slot_overlap`: the slot it collides with. */
data class SlotOverlapDetails(val slotId: Long, val dayOfWeek: Int, val startTime: LocalTime, val endTime: LocalTime)

/** `details` of `capacity_below_occupancy`: every future lesson that does not fit the new capacity. */
data class CapacityBelowOccupancyDetails(val lessons: List<LessonOverCapacity>)

/** A lesson with more occupied places than the new capacity; [spotsToFree] is how many must be freed. */
data class LessonOverCapacity(val lessonDate: LocalDate, val occupancy: Int, val spotsToFree: Int)

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
