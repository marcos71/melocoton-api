package ar.melocoton.bookings.application

import ar.melocoton.bookings.application.port.BookingRepository
import ar.melocoton.bookings.domain.Lesson
import ar.melocoton.bookings.domain.lessonsOfSlot
import ar.melocoton.bookings.domain.requireValidLessonRange
import ar.melocoton.shared.domain.WorkshopTime
import ar.melocoton.slots.application.port.SlotRepository
import ar.melocoton.slots.domain.SlotNotFound
import ar.melocoton.slots.domain.SlotStatusFilter
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/**
 * DI-56, CU-04 steps 3 and 3a: the lessons of the active slots (or of one) between two dates, with their
 * free capacity. Read only; the occupancy of the whole range is one grouped query. Lesson closures
 * come with iteration 7.
 */
@Service
class ListLessons(
    private val slots: SlotRepository,
    private val bookings: BookingRepository,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(from: LocalDate, to: LocalDate, slotId: Long?): List<Lesson> {
        requireValidLessonRange(from, to)
        val now = WorkshopTime.now(clock)
        val candidates = if (slotId != null) {
            listOfNotNull((slots.findById(slotId) ?: throw SlotNotFound()).takeIf { it.active })
        } else {
            slots.findAll(SlotStatusFilter.ACTIVE)
        }
        val occupancy = bookings.occupancyInRange(candidates.map { it.id }, from, to)
        return candidates
            .flatMap { slot ->
                val ofSlot = occupancy.filterKeys { it.slotId == slot.id }.mapKeys { (key, _) -> key.lessonDate }
                lessonsOfSlot(
                    slot.id, slot.dayOfWeek, slot.startTime, slot.endTime, slot.capacity, from, to, ofSlot, now,
                )
            }
            .sortedWith(compareBy({ it.lessonDate }, { it.startTime }))
    }
}
