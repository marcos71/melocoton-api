package ar.melocoton.bookings.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** How far ahead a drop-in class can be booked, in months (DI-44). */
const val DROP_IN_MAX_MONTHS = 3L

/**
 * The date of a drop-in booking must be a lesson of the slot (its weekday), no more than 3 months from
 * today (inclusive), and not started yet (RF-09, DI-44).
 *
 * @param now in the workshop's time zone
 * @throws InvalidLessonDate the date is not on the slot's day or is more than 3 months ahead
 * @throws LessonAlreadyStarted the lesson has started
 */
fun requireValidDropInDate(slotDay: DayOfWeek, slotStartTime: LocalTime, lessonDate: LocalDate, now: LocalDateTime) {
    if (lessonDate.dayOfWeek != slotDay) throw InvalidLessonDate("La fecha no cae en el día del turno.")
    if (lessonDate.isAfter(now.toLocalDate().plusMonths(DROP_IN_MAX_MONTHS))) {
        throw InvalidLessonDate("Solo se puede reservar hasta $DROP_IN_MAX_MONTHS meses adelante.")
    }
    if (lessonHasStarted(lessonDate, slotStartTime, now)) throw LessonAlreadyStarted()
}

/** @throws AlreadyBooked the student has a booking in the lesson that is not cancelled (DI-41) */
fun requireNotAlreadyBooked(existingInLesson: Collection<ExistingBooking>) {
    if (existingInLesson.any { it.status != BookingStatus.CANCELLED }) throw AlreadyBooked()
}
