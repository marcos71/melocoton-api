package ar.melocoton.bookings.application

import ar.melocoton.bookings.application.port.BookingRepository
import ar.melocoton.bookings.domain.fixedBookingDates
import ar.melocoton.slots.domain.Slot
import ar.melocoton.students.domain.Student
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The step that assigning a fixed slot (RF-08) and extending the rolling window (RF-64) share: store the
 * fixed bookings a student is missing in a slot up to the window end (DI-42). It is a plain helper that
 * each use case builds with its port; a use case never injects another one.
 * The caller holds the student and slot locks.
 */
internal class FixedBookingGeneration(private val bookings: BookingRepository) {

    /** Creates the missing fixed bookings and returns how many; running it again creates none. */
    fun generate(student: Student, slot: Slot, now: LocalDateTime, windowEnd: LocalDate): Int {
        val monthlyStartDate = checkNotNull(student.monthlyStartDate) { "A student with a fixed slot is monthly" }
        val existing = bookings.existingStudentBookings(student.userId, slot.id, now.toLocalDate(), windowEnd)
        val dates = fixedBookingDates(slot.dayOfWeek, slot.startTime, monthlyStartDate, now, windowEnd, existing)
        return bookings.insertFixedIfAbsent(student.userId, slot.id, dates)
    }
}
