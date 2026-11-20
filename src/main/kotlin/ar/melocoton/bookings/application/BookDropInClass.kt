package ar.melocoton.bookings.application

import ar.melocoton.bookings.application.port.BookingRepository
import ar.melocoton.bookings.domain.Booking
import ar.melocoton.bookings.domain.requireFreePlace
import ar.melocoton.bookings.domain.requireNotAlreadyBooked
import ar.melocoton.bookings.domain.requireValidDropInDate
import ar.melocoton.shared.domain.WorkshopTime
import ar.melocoton.slots.application.port.SlotRepository
import ar.melocoton.slots.domain.SlotNotFound
import ar.melocoton.students.application.port.StudentRepository
import ar.melocoton.students.domain.StudentNotFound
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/**
 * RF-09, RF-11, D-43, DI-41, DI-44: the lead teacher books a drop-in class for a drop-in student.
 * The capacity is checked while holding the slot lock, so two requests for the last place cannot both
 * pass (CP-01). The "no prior payment" warning comes with the payments iteration.
 *
 * Lock order: the student, then the slot.
 */
@Service
class BookDropInClass(
    private val students: StudentRepository,
    private val slots: SlotRepository,
    private val bookings: BookingRepository,
    private val clock: Clock,
) {
    @Transactional
    fun book(studentId: Long, slotId: Long, lessonDate: LocalDate): Booking {
        val now = WorkshopTime.now(clock)
        val student = students.lockById(studentId) ?: throw StudentNotFound()
        student.requireCanBookDropIn()

        val slot = slots.lockById(slotId) ?: throw SlotNotFound()
        slot.requireAcceptsBookings()
        requireValidDropInDate(slot.dayOfWeek, slot.startTime, lessonDate, now)
        requireNotAlreadyBooked(bookings.existingStudentBookings(studentId, slotId, lessonDate, lessonDate))
        requireFreePlace(slot.capacity, bookings.occupancyOn(slotId, lessonDate))

        return bookings.createDropIn(studentId, slotId, lessonDate)
    }
}
