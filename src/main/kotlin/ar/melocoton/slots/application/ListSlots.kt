package ar.melocoton.slots.application

import ar.melocoton.bookings.application.port.BookingRepository
import ar.melocoton.slots.application.port.SlotRepository
import ar.melocoton.slots.domain.SlotStatusFilter
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** CU-03, RF-07, RF-10: lists slots with the future lessons over capacity (DI-43). */
@Service
class ListSlots(
    private val slots: SlotRepository,
    private val bookings: BookingRepository,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(status: SlotStatusFilter): List<SlotOverview> {
        val now = clock.workshopNow()
        val all = slots.findAll(status)
        val withBookings = bookings.slotIdsWithBookings(all.map { it.id })
        return all.map { slot ->
            val occupancy = bookings.occupancyByLessonDate(slot.id, now.toLocalDate())
            SlotOverview(slot, slot.overbookedLessons(occupancy, now), slot.scheduleEditable(slot.id in withBookings))
        }
    }
}
