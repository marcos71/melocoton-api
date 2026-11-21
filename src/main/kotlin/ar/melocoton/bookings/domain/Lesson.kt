package ar.melocoton.bookings.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** A lesson (a slot on a date) with its free capacity, as the booking screens need it (DI-56). Derived: it has no table. */
data class Lesson(
    val slotId: Long,
    val lessonDate: LocalDate,
    val dayOfWeek: DayOfWeek,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val capacity: Int,
    val occupancy: Int,
    val freeSpots: Int,
    val overbooked: Boolean,
    val started: Boolean,
)

/** Identifies a lesson: a slot on a date. */
data class LessonKey(val slotId: Long, val lessonDate: LocalDate)

/**
 * The range of a lessons query: [from] not after [to], and [to] at most [DROP_IN_MAX_MONTHS] months after [from] (inclusive).
 *
 * @throws InvalidDateRange the range is inverted or too long
 */
fun requireValidLessonRange(from: LocalDate, to: LocalDate) {
    if (from.isAfter(to)) throw InvalidDateRange("La fecha inicial no puede ser posterior a la final.")
    if (to.isAfter(from.plusMonths(DROP_IN_MAX_MONTHS))) {
        throw InvalidDateRange("El rango no puede abarcar más de $DROP_IN_MAX_MONTHS meses.")
    }
}

/** Every date from [from] to [to], both inclusive, that falls on [dayOfWeek], in order. */
fun lessonDatesInRange(dayOfWeek: DayOfWeek, from: LocalDate, to: LocalDate): List<LocalDate> =
    generateSequence(from) { it.plusDays(1) }
        .takeWhile { !it.isAfter(to) }
        .filter { it.dayOfWeek == dayOfWeek }
        .toList()

/**
 * The lesson of a slot on [lessonDate] with its derived fields: free places (never negative), overbooked
 * (occupancy above capacity, DI-43) and started (RF-09).
 *
 * @param occupancy occupied capacity of the lesson (RN-02), see [occupancyOf]
 * @param now in the workshop's time zone
 */
fun lessonOf(
    slotId: Long,
    dayOfWeek: DayOfWeek,
    startTime: LocalTime,
    endTime: LocalTime,
    capacity: Int,
    lessonDate: LocalDate,
    occupancy: Int,
    now: LocalDateTime,
) = Lesson(
    slotId = slotId,
    lessonDate = lessonDate,
    dayOfWeek = dayOfWeek,
    startTime = startTime,
    endTime = endTime,
    capacity = capacity,
    occupancy = occupancy,
    freeSpots = maxOf(0, capacity - occupancy),
    overbooked = occupancy > capacity,
    started = lessonHasStarted(lessonDate, startTime, now),
)

/** The lessons of one slot between [from] and [to], with the occupancy of each date (absent means 0). */
fun lessonsOfSlot(
    slotId: Long,
    dayOfWeek: DayOfWeek,
    startTime: LocalTime,
    endTime: LocalTime,
    capacity: Int,
    from: LocalDate,
    to: LocalDate,
    occupancyByDate: Map<LocalDate, Int>,
    now: LocalDateTime,
): List<Lesson> = lessonDatesInRange(dayOfWeek, from, to).map {
    lessonOf(slotId, dayOfWeek, startTime, endTime, capacity, it, occupancyByDate[it] ?: 0, now)
}
