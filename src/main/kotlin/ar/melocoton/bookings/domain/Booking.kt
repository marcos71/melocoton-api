package ar.melocoton.bookings.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** A student's place in one lesson (a slot on a date), as the use cases that change it need it. */
class Booking(
    val id: Long,
    val studentId: Long,
    val slotId: Long,
    val lessonDate: LocalDate,
    val type: BookingType,
    status: BookingStatus,
    cancellationReason: CancellationReason?,
) {
    var status: BookingStatus = status
        private set

    var cancellationReason: CancellationReason? = cancellationReason
        private set

    /**
     * The lead teacher cancels a student's drop-in class (RF-52, DI-44). Only a scheduled booking
     * can be cancelled, and only before the lesson starts; it frees the place (RN-02).
     *
     * @param slotStartTime start time of the booking's slot
     * @param now in the workshop's time zone
     * @throws BookingNotFound the booking belongs to another student or is not a drop-in
     * @throws BookingStatusConflict the booking is not scheduled
     * @throws LessonAlreadyStarted the lesson has started
     */
    fun cancelDropIn(studentId: Long, slotStartTime: LocalTime, now: LocalDateTime) {
        if (this.studentId != studentId || type != BookingType.DROP_IN) throw BookingNotFound()
        if (status != BookingStatus.SCHEDULED) throw BookingStatusConflict()
        if (lessonHasStarted(lessonDate, slotStartTime, now)) throw LessonAlreadyStarted()
        status = BookingStatus.CANCELLED
        cancellationReason = CancellationReason.TEACHER
    }
}

/** What a student already has booked in a lesson, to decide which fixed bookings are missing. */
data class ExistingBooking(
    val lessonDate: LocalDate,
    val status: BookingStatus,
    val cancellationReason: CancellationReason?,
)
