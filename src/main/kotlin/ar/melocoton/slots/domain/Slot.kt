package ar.melocoton.slots.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Which slots a list asks for. */
enum class SlotStatusFilter { ACTIVE, INACTIVE, ALL }

/** A lesson (a slot on a date) and how many places are occupied in it (RN-02). */
data class LessonOccupancy(val lessonDate: LocalDate, val occupancy: Int)

/** A slot to be stored: already validated and without overlaps (CU-03, RF-06, DI-25). */
class NewSlot private constructor(
    val dayOfWeek: DayOfWeek,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val capacity: Int,
) {
    companion object {
        /**
         * Validates the data and checks it against the active slots (any day; only those of the same day count).
         * @throws InvalidSlot a field is out of range or the start is not before the end
         * @throws SlotOverlap it overlaps an active slot of the same day
         */
        fun create(
            dayOfWeek: Int,
            startTime: LocalTime,
            endTime: LocalTime,
            capacity: Int,
            activeSlots: List<Slot>,
        ): NewSlot {
            val day = dayOfWeekOf(dayOfWeek)
            requireValidTimes(startTime, endTime)
            requireValidCapacity(capacity)
            findOverlap(day, startTime, endTime, activeSlots, ignoring = null)?.let { throw SlotOverlap(it) }
            return NewSlot(day, startTime, endTime, capacity)
        }
    }
}

/**
 * The fields a PATCH changes. At least one is present and each one is valid on its own; whether the
 * start is before the end depends on the slot being edited, so [Slot.update] checks that.
 *
 * @throws InvalidSlot nothing to change, the day is not 1 to 7, or the capacity is not positive
 */
class SlotChange(dayOfWeek: Int?, val startTime: LocalTime?, val endTime: LocalTime?, val capacity: Int?) {
    val dayOfWeek: DayOfWeek? = dayOfWeek?.let(::dayOfWeekOf)

    init {
        if (dayOfWeek == null && startTime == null && endTime == null && capacity == null) {
            throw InvalidSlot("Tenés que indicar al menos un dato para cambiar.")
        }
        capacity?.let(::requireValidCapacity)
    }
}

/** A weekly recurring time range with a maximum number of students (CU-03; RF-06; RN-02). */
class Slot(
    val id: Long,
    dayOfWeek: DayOfWeek,
    startTime: LocalTime,
    endTime: LocalTime,
    capacity: Int,
    active: Boolean,
) {
    var dayOfWeek: DayOfWeek = dayOfWeek
        private set

    var startTime: LocalTime = startTime
        private set

    var endTime: LocalTime = endTime
        private set

    var capacity: Int = capacity
        private set

    var active: Boolean = active
        private set

    /** Two slots overlap when each starts before the other ends; touching ends do not overlap (DI-25). */
    fun overlaps(dayOfWeek: DayOfWeek, startTime: LocalTime, endTime: LocalTime): Boolean =
        this.dayOfWeek == dayOfWeek && this.startTime < endTime && startTime < this.endTime

    /** @throws SlotNotAcceptingBookings the slot is deactivated */
    fun requireAcceptsBookings() {
        if (!active) throw SlotNotAcceptingBookings()
    }

    /** Future lessons whose occupancy is above the capacity, by date (DI-43). Past lessons are never considered. */
    fun overbookedLessons(occupancyByDate: Map<LocalDate, Int>, now: LocalDateTime): List<LessonOccupancy> =
        futureLessons(occupancyByDate, now).filter { it.occupancy > capacity }

    /**
     * Applies a PATCH (CU-03, RF-06, DI-45). The capacity is always editable, but never below the
     * occupancy of a future lesson; day and time only while the slot has no bookings, and without
     * overlapping another active slot. Past lessons are neither touched nor considered (CU-03 E3).
     *
     * @param activeSlots all active slots (any day); those of the target day are checked for overlap
     * @param hasBookings asked only when day or time change
     * @param occupancyByDate occupancy of this slot's lessons from today on (RN-02)
     * @param now in the workshop's time zone
     * @throws SlotInactive the slot is inactive
     * @throws InvalidSlot the start is not before the end
     * @throws SlotHasBookings day or time change and the slot has bookings
     * @throws SlotOverlap the new day and time overlap another active slot
     * @throws CapacityBelowOccupancy some future lesson has more occupied places than the new capacity
     */
    fun update(
        change: SlotChange,
        activeSlots: List<Slot>,
        hasBookings: () -> Boolean,
        occupancyByDate: Map<LocalDate, Int>,
        now: LocalDateTime,
    ) {
        if (!active) throw SlotInactive()
        val newDay = change.dayOfWeek ?: dayOfWeek
        val newStart = change.startTime ?: startTime
        val newEnd = change.endTime ?: endTime
        requireValidTimes(newStart, newEnd)

        val changesSchedule = newDay != dayOfWeek || newStart != startTime || newEnd != endTime
        if (changesSchedule) {
            if (hasBookings()) throw SlotHasBookings()
            findOverlap(newDay, newStart, newEnd, activeSlots, ignoring = id)?.let { throw SlotOverlap(it) }
        }

        val newCapacity = change.capacity ?: capacity
        val offending = futureLessons(occupancyByDate, now)
            .filter { it.occupancy > newCapacity }
            .map { LessonOverCapacity(it.lessonDate, it.occupancy, spotsToFree = it.occupancy - newCapacity) }
        if (offending.isNotEmpty()) throw CapacityBelowOccupancy(newCapacity, offending)

        dayOfWeek = newDay
        startTime = newStart
        endTime = newEnd
        capacity = newCapacity
    }

    /** A lesson that has not started: a later date, or today with the slot's start still ahead. */
    private fun futureLessons(occupancyByDate: Map<LocalDate, Int>, now: LocalDateTime): List<LessonOccupancy> =
        occupancyByDate
            .filterKeys { it > now.toLocalDate() || (it == now.toLocalDate() && startTime > now.toLocalTime()) }
            .map { (date, occupancy) -> LessonOccupancy(date, occupancy) }
            .sortedBy { it.lessonDate }
}

private fun findOverlap(
    day: DayOfWeek,
    start: LocalTime,
    end: LocalTime,
    activeSlots: List<Slot>,
    ignoring: Long?,
): Slot? = activeSlots.firstOrNull { it.active && it.id != ignoring && it.overlaps(day, start, end) }

private fun dayOfWeekOf(value: Int): DayOfWeek =
    if (value in 1..7) DayOfWeek.of(value) else throw InvalidSlot("El día debe ser un número de 1 (lunes) a 7 (domingo).")

private fun requireValidTimes(start: LocalTime, end: LocalTime) {
    if (start >= end) throw InvalidSlot("La hora de inicio tiene que ser anterior a la de fin.")
}

private fun requireValidCapacity(capacity: Int) {
    if (capacity <= 0) throw InvalidSlot("El cupo tiene que ser mayor a cero.")
}
