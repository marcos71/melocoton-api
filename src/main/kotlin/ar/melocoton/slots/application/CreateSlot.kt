package ar.melocoton.slots.application

import ar.melocoton.slots.application.port.SlotRepository
import ar.melocoton.slots.domain.NewSlot
import ar.melocoton.slots.domain.Slot
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalTime

/** CU-03, RF-06: the lead teacher creates a slot. */
@Service
class CreateSlot(private val slots: SlotRepository) {

    @Transactional
    fun create(dayOfWeek: Int, startTime: LocalTime, endTime: LocalTime): Slot {
        // Held until commit: a concurrent create waits here and then reads this slot as active (DI-25).
        slots.lockSchedule()
        val active = slots.findActive()
        return slots.create(NewSlot.create(dayOfWeek, startTime, endTime, active))
    }
}
