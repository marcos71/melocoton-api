package ar.melocoton.bookings.infrastructure.persistence

import ar.melocoton.bookings.application.port.BookingRepository
import ar.melocoton.bookings.domain.AlreadyBooked
import ar.melocoton.bookings.domain.Booking
import ar.melocoton.bookings.domain.BookingStatus
import ar.melocoton.bookings.domain.BookingType
import ar.melocoton.bookings.domain.CancellationReason
import ar.melocoton.bookings.domain.ExistingBooking
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.SQLException
import java.time.LocalDate

/**
 * Adapter of [BookingRepository]. Entity reads and single-row writes go through JPA; the two bulk
 * writes (fixed bookings with ON CONFLICT, and cancelling a student's fixed bookings) are plain SQL.
 * JDBC and JPA share the transaction and the connection of the use case.
 */
@Repository
class BookingRepositoryJpa(
    private val jpa: BookingJpaRepository,
    private val jdbc: JdbcTemplate,
) : BookingRepository {

    override fun occupancyByLessonDate(slotId: Long, fromDate: LocalDate): Map<LocalDate, Int> =
        jpa.countByLessonDate(slotId, fromDate, BookingStatus.OCCUPYING)
            .associate { it.lessonDate to it.occupancy.toInt() }

    override fun occupancyOn(slotId: Long, lessonDate: LocalDate): Int =
        jpa.countOccupied(slotId, lessonDate, BookingStatus.OCCUPYING).toInt()

    override fun existsForSlot(slotId: Long): Boolean = jpa.existsBySlotId(slotId)

    override fun existingStudentBookings(
        studentId: Long,
        slotId: Long,
        fromDate: LocalDate,
        toDate: LocalDate,
    ): List<ExistingBooking> =
        jpa.findStudentBookings(studentId, slotId, fromDate, toDate)
            .map { ExistingBooking(it.lessonDate, it.status, it.cancellationReason) }

    override fun createDropIn(studentId: Long, slotId: Long, lessonDate: LocalDate): Booking {
        val entity = BookingEntity(
            studentId = studentId,
            slotId = slotId,
            lessonDate = lessonDate,
            type = BookingType.DROP_IN,
            status = BookingStatus.SCHEDULED,
            originalBookingId = null,
            cancellationReason = null,
            attendanceRecordedBy = null,
            attendanceRecordedAt = null,
        )
        try {
            return jpa.saveAndFlush(entity).toDomain()
        } catch (e: DataIntegrityViolationException) {
            // The code checks first; the unique index is the last barrier (DI-41).
            if (e.violates(ACTIVE_BOOKING_INDEX)) throw AlreadyBooked()
            throw e
        }
    }

    override fun insertFixedIfAbsent(studentId: Long, slotId: Long, lessonDates: Collection<LocalDate>): Int {
        if (lessonDates.isEmpty()) return 0
        // One multi-row statement: its update count is exact (a JDBC batch may report "no info" per row).
        return jdbc.update { connection ->
            val dates = lessonDates.sorted().map { java.sql.Date.valueOf(it) }.toTypedArray()
            connection.prepareStatement(
                """INSERT INTO booking (student_id, slot_id, lesson_date, type, status)
                   SELECT ?, ?, d, 'FIXED', 'SCHEDULED' FROM unnest(?::date[]) AS d
                   ON CONFLICT (student_id, slot_id, lesson_date) WHERE status <> 'CANCELLED' DO NOTHING""",
            ).apply {
                setLong(1, studentId)
                setLong(2, slotId)
                setArray(3, connection.createArrayOf("date", dates))
            }
        }
    }

    override fun cancelScheduledFixed(studentId: Long, slotId: Long, fromDate: LocalDate, reason: CancellationReason): Int =
        jdbc.update(
            """UPDATE booking SET status = 'CANCELLED', cancellation_reason = ?
               WHERE student_id = ? AND slot_id = ? AND type = 'FIXED' AND status = 'SCHEDULED' AND lesson_date >= ?""",
            reason.name, studentId, slotId, fromDate,
        )

    override fun findSlotId(bookingId: Long): Long? = jpa.findSlotId(bookingId)

    override fun lockById(bookingId: Long): Booking? = jpa.lockById(bookingId)?.toDomain()

    override fun save(booking: Booking) {
        // The row is already loaded in the use case transaction. It is written right away, so the plain
        // SQL reads and bulk updates of this adapter and of others see the change.
        val entity = checkNotNull(jpa.findByIdOrNull(booking.id)) { "Booking ${booking.id} disappeared" }
        entity.status = booking.status
        entity.cancellationReason = booking.cancellationReason
        jpa.flush()
    }

    private fun DataIntegrityViolationException.violates(indexName: String): Boolean =
        generateSequence<Throwable>(this) { it.cause }
            .filterIsInstance<SQLException>()
            .any { it.sqlState == UNIQUE_VIOLATION && it.message?.contains(indexName) == true }

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
        const val ACTIVE_BOOKING_INDEX = "booking_student_slot_date_active_ux"
    }
}
