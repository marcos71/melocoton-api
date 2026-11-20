package ar.melocoton.bookings.infrastructure.web

import ar.melocoton.bookings.application.FixedSlotAssignment
import ar.melocoton.bookings.domain.Booking
import ar.melocoton.bookings.domain.BookingStatus
import ar.melocoton.bookings.domain.BookingType
import ar.melocoton.slots.domain.LessonOccupancy
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate
import java.time.LocalTime

private const val TIME_EXAMPLE = "18:00"

/** Body of PUT /api/v1/students/{id}/fixed-slot (RF-08). */
class AssignFixedSlotRequest(val slotId: Long)

/** Body of POST /api/v1/students/{id}/drop-in-bookings (RF-09). */
class BookDropInClassRequest(
    val slotId: Long,
    @field:Schema(type = "string", format = "date", example = "2026-12-01", description = "A date on the slot's weekday")
    val lessonDate: LocalDate,
)

/** The fixed slot a student was assigned. */
data class AssignedSlotResponse(
    val id: Long,
    @field:Schema(description = "ISO day of the week: 1 is Monday, 7 is Sunday")
    val dayOfWeek: Int,
    @field:Schema(type = "string", example = TIME_EXAMPLE, description = "HH:mm")
    val startTime: LocalTime,
    @field:Schema(type = "string", example = TIME_EXAMPLE, description = "HH:mm")
    val endTime: LocalTime,
)

/** A future lesson with more occupied places than the capacity (DI-43). */
data class OverbookedLessonResponse(
    @field:Schema(type = "string", format = "date")
    val lessonDate: LocalDate,
    val occupancy: Int,
    val capacity: Int,
)

/** The result of assigning a fixed slot: the slot and the overbooked future lessons of it. */
data class FixedSlotAssignmentResponse(
    val fixedSlot: AssignedSlotResponse,
    val overbookedLessons: List<OverbookedLessonResponse>,
)

/** A booking. */
data class BookingResponse(
    val id: Long,
    val slotId: Long,
    @field:Schema(type = "string", format = "date")
    val lessonDate: LocalDate,
    val type: BookingType,
    val status: BookingStatus,
)

fun FixedSlotAssignment.toResponse() = FixedSlotAssignmentResponse(
    fixedSlot = AssignedSlotResponse(
        id = slot.id,
        dayOfWeek = slot.dayOfWeek.value,
        startTime = slot.startTime,
        endTime = slot.endTime,
    ),
    overbookedLessons = overbookedLessons.map { it.toResponse(slot.capacity) },
)

private fun LessonOccupancy.toResponse(capacity: Int) =
    OverbookedLessonResponse(lessonDate = lessonDate, occupancy = occupancy, capacity = capacity)

fun Booking.toResponse() =
    BookingResponse(id = id, slotId = slotId, lessonDate = lessonDate, type = type, status = status)
