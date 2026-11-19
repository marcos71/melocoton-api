package ar.melocoton.slots.application

import ar.melocoton.bookings.application.port.BookingRepository
import ar.melocoton.slots.application.port.SlotRepository
import ar.melocoton.slots.domain.SlotNotFound
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** CU-03, RF-07: one slot with the future lessons over capacity (DI-43). */
@Service
class GetSlot(
    private val slots: SlotRepository,
    private val bookings: BookingRepository,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun get(id: Long): SlotOverview {
        val slot = slots.findById(id) ?: throw SlotNotFound()
        val now = clock.workshopNow()
        val occupancy = bookings.occupancyByLessonDate(slot.id, now.toLocalDate())
        return SlotOverview(slot, slot.overbookedLessons(occupancy, now))
    }
}
