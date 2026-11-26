package ar.melocoton.bookings.application.port

import ar.melocoton.bookings.domain.Booking
import ar.melocoton.bookings.domain.CancellationReason
import ar.melocoton.bookings.domain.ExistingBooking
import ar.melocoton.bookings.domain.LessonKey
import java.time.LocalDate

/** Output port for bookings. */
interface BookingRepository {

    /**
     * Occupied capacity (RN-02) of each lesson of the slot on or after [fromDate], by lesson date.
     * Dates with no occupying booking are absent from the map.
     */
    fun occupancyByLessonDate(slotId: Long, fromDate: LocalDate): Map<LocalDate, Int>

    /** [occupancyByLessonDate] of each slot in one query. Slots with no occupying booking are absent from the map. */
    fun occupancyByLessonDate(slotIds: Collection<Long>, fromDate: LocalDate): Map<Long, Map<LocalDate, Int>>

    /**
     * Occupied capacity (RN-02) of every lesson of the given slots between [fromDate] and [toDate] inclusive,
     * in one query. Lessons with no occupying booking are absent from the map.
     */
    fun occupancyInRange(slotIds: Collection<Long>, fromDate: LocalDate, toDate: LocalDate): Map<LessonKey, Int>

    /** Occupied capacity (RN-02) of one lesson. Read it holding the slot lock (D-43). */
    fun occupancyOn(slotId: Long, lessonDate: LocalDate): Int

    /** True when the slot has any booking, in any state and of any date. */
    fun existsForSlot(slotId: Long): Boolean

    /** Of the given slots, the ids of those with any booking, in any state and of any date, in one query (DI-63). */
    fun slotIdsWithBookings(slotIds: Collection<Long>): Set<Long>

    /** The student's bookings in the slot, in any state, with lesson dates from [fromDate] to [toDate] inclusive. */
    fun existingStudentBookings(studentId: Long, slotId: Long, fromDate: LocalDate, toDate: LocalDate): List<ExistingBooking>

    /**
     * Stores a scheduled drop-in booking (RF-09) and writes it right away.
     * @throws ar.melocoton.bookings.domain.AlreadyBooked the student already has an active booking in the lesson (DI-41)
     */
    fun createDropIn(studentId: Long, slotId: Long, lessonDate: LocalDate): Booking

    /**
     * Stores a scheduled fixed booking for each date the student does not already have an active one in
     * (DI-41: a concurrent insert of the same date is skipped, not an error). Returns how many were created.
     */
    fun insertFixedIfAbsent(studentId: Long, slotId: Long, lessonDates: Collection<LocalDate>): Int

    /**
     * Cancels with [reason] the student's scheduled fixed bookings in the slot for lessons on or after
     * [fromDate]. Returns how many were cancelled.
     */
    fun cancelScheduledFixed(studentId: Long, slotId: Long, fromDate: LocalDate, reason: CancellationReason): Int

    /** The slot of a booking, or null when it does not exist. Plain read, to know which slot to lock. */
    fun findSlotId(bookingId: Long): Long?

    /** The booking, locked for update. */
    fun lockById(bookingId: Long): Booking?

    /** Writes the state of a booking already loaded in this transaction. */
    fun save(booking: Booking)
}
