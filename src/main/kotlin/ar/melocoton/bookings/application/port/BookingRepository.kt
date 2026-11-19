package ar.melocoton.bookings.application.port

import java.time.LocalDate

/** Output port for bookings. For now only the reads that slots need; the bookings module grows next. */
interface BookingRepository {

    /**
     * Occupied capacity (RN-02) of each lesson of the slot on or after [fromDate], by lesson date.
     * Dates with no occupying booking are absent from the map.
     */
    fun occupancyByLessonDate(slotId: Long, fromDate: LocalDate): Map<LocalDate, Int>

    /** True when the slot has any booking, in any state and of any date. */
    fun existsForSlot(slotId: Long): Boolean
}
