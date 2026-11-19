package ar.melocoton.slots.infrastructure.web

import ar.melocoton.slots.application.SlotOverview
import ar.melocoton.slots.domain.LessonOccupancy
import ar.melocoton.slots.domain.Slot
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate
import java.time.LocalTime

private const val TIME_EXAMPLE = "18:00"

/**
 * Body of POST /api/v1/slots. Every field is required (CU-03). The domain checks the ranges, so
 * a day out of 1 to 7, a start that is not before the end, or a capacity of zero are 400 validation_error.
 */
class CreateSlotRequest(
    @field:Schema(description = "ISO day of the week: 1 is Monday, 7 is Sunday", minimum = "1", maximum = "7")
    val dayOfWeek: Int,
    @field:Schema(type = "string", example = TIME_EXAMPLE, description = "HH:mm")
    val startTime: LocalTime,
    @field:Schema(type = "string", example = TIME_EXAMPLE, description = "HH:mm")
    val endTime: LocalTime,
    @field:Schema(minimum = "1")
    val capacity: Int,
)

/** Body of PATCH /api/v1/slots/{id}. At least one field; day and time only while the slot has no bookings (DI-45). */
class UpdateSlotRequest(
    @field:Schema(description = "ISO day of the week: 1 is Monday, 7 is Sunday", minimum = "1", maximum = "7")
    val dayOfWeek: Int? = null,
    @field:Schema(type = "string", example = TIME_EXAMPLE, description = "HH:mm")
    val startTime: LocalTime? = null,
    @field:Schema(type = "string", example = TIME_EXAMPLE, description = "HH:mm")
    val endTime: LocalTime? = null,
    @field:Schema(minimum = "1")
    val capacity: Int? = null,
)

/** A slot: a weekly time range with a capacity. */
data class SlotResponse(
    val id: Long,
    @field:Schema(description = "ISO day of the week: 1 is Monday, 7 is Sunday")
    val dayOfWeek: Int,
    @field:Schema(type = "string", example = TIME_EXAMPLE, description = "HH:mm")
    val startTime: LocalTime,
    @field:Schema(type = "string", example = TIME_EXAMPLE, description = "HH:mm")
    val endTime: LocalTime,
    val capacity: Int,
    val active: Boolean,
)

/** A slot with its future lessons that have more occupied places than the capacity (DI-43). */
data class SlotItemResponse(
    val id: Long,
    @field:Schema(description = "ISO day of the week: 1 is Monday, 7 is Sunday")
    val dayOfWeek: Int,
    @field:Schema(type = "string", example = TIME_EXAMPLE, description = "HH:mm")
    val startTime: LocalTime,
    @field:Schema(type = "string", example = TIME_EXAMPLE, description = "HH:mm")
    val endTime: LocalTime,
    val capacity: Int,
    val active: Boolean,
    @field:Schema(description = "Lessons not started yet whose occupancy is above the capacity, by date")
    val upcomingOverbookedLessons: List<OverbookedLessonResponse>,
)

/** A lesson over capacity: its date and how many places are occupied. */
data class OverbookedLessonResponse(val lessonDate: LocalDate, val occupancy: Int)

fun Slot.toResponse() = SlotResponse(
    id = id,
    dayOfWeek = dayOfWeek.value,
    startTime = startTime,
    endTime = endTime,
    capacity = capacity,
    active = active,
)

fun LessonOccupancy.toResponse() = OverbookedLessonResponse(lessonDate = lessonDate, occupancy = occupancy)

fun SlotOverview.toResponse() = SlotItemResponse(
    id = slot.id,
    dayOfWeek = slot.dayOfWeek.value,
    startTime = slot.startTime,
    endTime = slot.endTime,
    capacity = slot.capacity,
    active = slot.active,
    upcomingOverbookedLessons = overbookedLessons.map { it.toResponse() },
)
