package ar.melocoton.bookings.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** Rolling window of fixed bookings (DI-42, RF-64). */
object RollingWindow {

    /**
     * Last day covered: the last day of the month that contains `today + horizonDays`.
     * For example, on 2026-11-19 with a horizon of 60 days it is 2027-01-31.
     */
    fun endDate(today: LocalDate, horizonDays: Int): LocalDate {
        val horizon = today.plusDays(horizonDays.toLong())
        return horizon.withDayOfMonth(horizon.lengthOfMonth())
    }
}

/**
 * Dates on which a student with a fixed slot still needs a fixed booking (RF-08, RF-64, DI-42).
 * One per weekday of the slot from today to [windowEnd], on or after [monthlyStartDate], for lessons
 * that have not started. A date is skipped when the student already has a booking in it in any state,
 * except one cancelled by an enrollment change: coming back to a previous slot generates it again.
 *
 * @param now in the workshop's time zone
 * @param existing the student's bookings in this slot from today on
 */
fun fixedBookingDates(
    slotDay: DayOfWeek,
    slotStartTime: LocalTime,
    monthlyStartDate: LocalDate,
    now: LocalDateTime,
    windowEnd: LocalDate,
    existing: Collection<ExistingBooking>,
): List<LocalDate> {
    val from = maxOf(firstUnstartedLessonDate(slotStartTime, now), monthlyStartDate)
    val taken = existing
        .filterNot { it.status == BookingStatus.CANCELLED && it.cancellationReason == CancellationReason.ENROLLMENT_CHANGE }
        .map { it.lessonDate }
        .toSet()
    return generateSequence(from.with(TemporalAdjusters.nextOrSame(slotDay))) { it.plusWeeks(1) }
        .takeWhile { !it.isAfter(windowEnd) }
        .filter { it !in taken }
        .toList()
}
