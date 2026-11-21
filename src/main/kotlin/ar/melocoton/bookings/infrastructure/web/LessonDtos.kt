package ar.melocoton.bookings.infrastructure.web

import ar.melocoton.bookings.domain.Lesson
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate
import java.time.LocalTime

/** A slot on a concrete date with its free capacity (DI-56). */
data class LessonResponse(
    val slotId: Long,
    @field:Schema(type = "string", format = "date", example = "2026-12-03")
    val lessonDate: LocalDate,
    @field:Schema(description = "ISO day of the week: 1 is Monday, 7 is Sunday")
    val dayOfWeek: Int,
    @field:Schema(type = "string", example = "18:00", description = "HH:mm")
    val startTime: LocalTime,
    @field:Schema(type = "string", example = "20:00", description = "HH:mm")
    val endTime: LocalTime,
    @field:Schema(description = "The slot's maximum capacity")
    val capacity: Int,
    @field:Schema(description = "Occupied places: scheduled and present bookings of any type (RN-02)")
    val occupancy: Int,
    @field:Schema(description = "Places left, never negative")
    val freeSpots: Int,
    @field:Schema(description = "More places occupied than the capacity (DI-43)")
    val overbooked: Boolean,
    @field:Schema(description = "The lesson has already started (workshop time zone)")
    val started: Boolean,
)

fun Lesson.toResponse() = LessonResponse(
    slotId = slotId,
    lessonDate = lessonDate,
    dayOfWeek = dayOfWeek.value,
    startTime = startTime,
    endTime = endTime,
    capacity = capacity,
    occupancy = occupancy,
    freeSpots = freeSpots,
    overbooked = overbooked,
    started = started,
)
