package ar.melocoton.slots.application

import ar.melocoton.bookings.application.port.BookingRepository
import ar.melocoton.slots.application.port.SlotRepository
import ar.melocoton.slots.domain.SlotChange
import ar.melocoton.slots.domain.SlotNotFound
import ar.melocoton.students.application.port.StudentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** CU-03, RF-06, DI-45: the lead teacher edits the day and time of a slot while it has no bookings. */
@Service
class UpdateSlot(
    private val slots: SlotRepository,
    private val bookings: BookingRepository,
    private val students: StudentRepository,
    private val clock: Clock,
) {
    @Transactional
    fun update(id: Long, change: SlotChange): SlotOverview {
        // Schedule lock first (DI-25: two concurrent moves cannot both pass the overlap check), then the slot row.
        slots.lockSchedule()
        val slot = slots.lockById(id) ?: throw SlotNotFound()
        val active = slots.findActive()

        val now = clock.workshopNow()
        val occupancy = bookings.occupancyByLessonDate(id, now.toLocalDate())
        // Asked at most once: when the schedule changes, and for the response.
        val hasBookings by lazy { bookings.existsForSlot(id) }
        slot.update(change, active, hasBookings = { hasBookings })
        slots.save(slot)
        return SlotOverview(slot, slot.overbookedLessons(occupancy, now), slot.scheduleEditable(hasBookings), students.countFixedStudents(id))
    }
}
