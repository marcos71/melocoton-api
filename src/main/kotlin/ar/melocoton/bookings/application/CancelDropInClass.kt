package ar.melocoton.bookings.application

import ar.melocoton.bookings.application.port.BookingRepository
import ar.melocoton.bookings.domain.Booking
import ar.melocoton.bookings.domain.BookingNotFound
import ar.melocoton.shared.domain.WorkshopTime
import ar.melocoton.slots.application.port.SlotRepository
import ar.melocoton.students.application.port.StudentRepository
import ar.melocoton.students.domain.StudentNotFound
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * RF-52, DI-44: the lead teacher cancels a student's drop-in class before it starts. The place is
 * free again because a cancelled booking does not count for the capacity (RN-02).
 *
 * Lock order: the student, the slot, then the booking.
 */
@Service
class CancelDropInClass(
    private val students: StudentRepository,
    private val slots: SlotRepository,
    private val bookings: BookingRepository,
    private val clock: Clock,
) {
    @Transactional
    fun cancel(studentId: Long, bookingId: Long): Booking {
        val now = WorkshopTime.now(clock)
        students.lockById(studentId) ?: throw StudentNotFound()

        val slotId = bookings.findSlotId(bookingId) ?: throw BookingNotFound()
        val slot = checkNotNull(slots.lockById(slotId)) { "Slot $slotId of booking $bookingId disappeared" }
        val booking = bookings.lockById(bookingId) ?: throw BookingNotFound()

        booking.cancelDropIn(studentId, slot.startTime, now)
        bookings.save(booking)
        return booking
    }
}
