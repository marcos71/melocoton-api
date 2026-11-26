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
            activeSlots: List<Slot>,
        ): NewSlot {
            val day = dayOfWeekOf(dayOfWeek)
            requireValidTimes(startTime, endTime)
            findOverlap(day, startTime, endTime, activeSlots, ignoring = null)?.let { throw SlotOverlap(it) }
            return NewSlot(day, startTime, endTime)
        }
    }
}

/**
 * The fields a PATCH changes. At least one is present and each one is valid on its own; whether the
 * start is before the end depends on the slot being edited, so [Slot.update] checks that.
 *
 * @throws InvalidSlot nothing to change, or the day is not 1 to 7
 */
class SlotChange(dayOfWeek: Int?, val startTime: LocalTime?, val endTime: LocalTime?) {
    val dayOfWeek: DayOfWeek? = dayOfWeek?.let(::dayOfWeekOf)

    init {
        if (dayOfWeek == null && startTime == null && endTime == null) {
            throw InvalidSlot("Tenés que indicar al menos un dato para cambiar.")
        }
    }
}

/** A weekly recurring time range; every slot holds [CAPACITY] students (CU-03; RF-06; RN-02; DI-64). */
class Slot(
    val id: Long,
    dayOfWeek: DayOfWeek,
    startTime: LocalTime,
    endTime: LocalTime,
    active: Boolean,
) {
    var dayOfWeek: DayOfWeek = dayOfWeek
        private set

    var startTime: LocalTime = startTime
        private set

    var endTime: LocalTime = endTime
        private set

    var active: Boolean = active
        private set

    companion object {
        /** Every slot holds 12 students: the size of the workshop (DI-64). */
        const val CAPACITY = 12
    }

    /** Two slots overlap when each starts before the other ends; touching ends do not overlap (DI-25). */
    fun overlaps(dayOfWeek: DayOfWeek, startTime: LocalTime, endTime: LocalTime): Boolean =
        this.dayOfWeek == dayOfWeek && this.startTime < endTime && startTime < this.endTime

    /**
     * Whether day and time can still be changed: the slot is active and never had a booking of any
     * status, because a booking keeps the slot and the date, not the time (DI-45, DI-63).
     */
    fun scheduleEditable(hasBookings: Boolean): Boolean = active && !hasBookings

    /**
     * A slot has at most [CAPACITY] fixed students (DI-66).
     * @param otherFixedStudents active fixed students of the slot, not counting the one being assigned
     * @throws FixedStudentsFull the slot already has [CAPACITY] of them
     */
    fun requireRoomForFixedStudent(otherFixedStudents: Int) {
        if (otherFixedStudents >= CAPACITY) throw FixedStudentsFull(otherFixedStudents)
    }

    /** @throws SlotNotAcceptingBookings the slot is deactivated */
    fun requireAcceptsBookings() {
        if (!active) throw SlotNotAcceptingBookings()
    }

    /** Future lessons whose occupancy is above [CAPACITY], by date (DI-43). Past lessons are never considered. */
    fun overbookedLessons(occupancyByDate: Map<LocalDate, Int>, now: LocalDateTime): List<LessonOccupancy> =
        futureLessons(occupancyByDate, now).filter { it.occupancy > CAPACITY }

    /**
     * Applies a PATCH (CU-03, RF-06, DI-45, DI-64): day and time, only while [scheduleEditable] and
     * without overlapping another active slot. The capacity is the same for every slot, so it is not edited.
     *
     * @param activeSlots all active slots (any day); those of the target day are checked for overlap
     * @param hasBookings asked only when day or time change
     * @throws SlotInactive the slot is inactive
     * @throws InvalidSlot the start is not before the end
     * @throws SlotHasBookings day or time change and the slot has bookings
     * @throws SlotOverlap the new day and time overlap another active slot
     */
    fun update(change: SlotChange, activeSlots: List<Slot>, hasBookings: () -> Boolean) {
        if (!active) throw SlotInactive()
        val newDay = change.dayOfWeek ?: dayOfWeek
        val newStart = change.startTime ?: startTime
        val newEnd = change.endTime ?: endTime
        requireValidTimes(newStart, newEnd)

        val changesSchedule = newDay != dayOfWeek || newStart != startTime || newEnd != endTime
        if (changesSchedule) {
            if (!scheduleEditable(hasBookings())) throw SlotHasBookings()
            findOverlap(newDay, newStart, newEnd, activeSlots, ignoring = id)?.let { throw SlotOverlap(it) }
        }

        dayOfWeek = newDay
        startTime = newStart
        endTime = newEnd
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
