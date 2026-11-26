package ar.melocoton.slots.application

import ar.melocoton.bookings.application.port.BookingRepository
import ar.melocoton.slots.application.port.SlotRepository
import ar.melocoton.slots.domain.SlotStatusFilter
import ar.melocoton.students.application.port.StudentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** CU-03, RF-07, RF-10: lists slots with the future lessons over capacity (DI-43). */
@Service
class ListSlots(
    private val slots: SlotRepository,
    private val bookings: BookingRepository,
    private val students: StudentRepository,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(status: SlotStatusFilter): List<SlotOverview> {
        val now = clock.workshopNow()
        val all = slots.findAll(status)
        val ids = all.map { it.id }
        val withBookings = bookings.slotIdsWithBookings(ids)
        val fixedStudents = students.countFixedStudentsBySlot(ids)
        val occupancy = bookings.occupancyByLessonDate(ids, now.toLocalDate())
        return all.map { slot ->
            SlotOverview(
                slot,
                slot.overbookedLessons(occupancy[slot.id] ?: emptyMap(), now),
                slot.scheduleEditable(slot.id in withBookings),
                fixedStudents[slot.id] ?: 0,
            )
        }
    }
}
