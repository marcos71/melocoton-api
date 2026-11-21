package ar.melocoton.slots.application

import ar.melocoton.shared.domain.WorkshopTime
import ar.melocoton.slots.domain.LessonOccupancy
import ar.melocoton.slots.domain.Slot
import java.time.Clock
import java.time.LocalDateTime

/**
 * A slot with its future lessons that are over capacity (DI-43) and whether its day and time can
 * still be changed (DI-63): what the slots screen shows.
 */
class SlotOverview(val slot: Slot, val overbookedLessons: List<LessonOccupancy>, val scheduleEditable: Boolean)

/** The current date and time in the workshop zone, to hand to the domain. */
internal fun Clock.workshopNow(): LocalDateTime = LocalDateTime.ofInstant(instant(), WorkshopTime.ZONE)
