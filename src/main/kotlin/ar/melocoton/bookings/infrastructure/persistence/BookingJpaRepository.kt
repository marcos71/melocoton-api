package ar.melocoton.bookings.infrastructure.persistence

import ar.melocoton.bookings.domain.BookingStatus
import ar.melocoton.bookings.domain.CancellationReason
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate

interface BookingJpaRepository : JpaRepository<BookingEntity, Long> {

    // The statuses come from the domain (BookingStatus.OCCUPYING), so the rule is not repeated here.
    @Query(
        "SELECT b.lessonDate AS lessonDate, count(b) AS occupancy FROM BookingEntity b " +
            "WHERE b.slotId = :slotId AND b.lessonDate >= :fromDate AND b.status IN :statuses " +
            "GROUP BY b.lessonDate",
    )
    fun countByLessonDate(slotId: Long, fromDate: LocalDate, statuses: Collection<BookingStatus>): List<OccupancyRow>

    @Query(
        "SELECT count(b) FROM BookingEntity b " +
            "WHERE b.slotId = :slotId AND b.lessonDate = :lessonDate AND b.status IN :statuses",
    )
    fun countOccupied(slotId: Long, lessonDate: LocalDate, statuses: Collection<BookingStatus>): Long

    fun existsBySlotId(slotId: Long): Boolean

    // A projection (not the entities), so the answer always comes from the database and not from the persistence context.
    @Query(
        "SELECT b.lessonDate AS lessonDate, b.status AS status, b.cancellationReason AS cancellationReason " +
            "FROM BookingEntity b " +
            "WHERE b.studentId = :studentId AND b.slotId = :slotId AND b.lessonDate BETWEEN :fromDate AND :toDate",
    )
    fun findStudentBookings(studentId: Long, slotId: Long, fromDate: LocalDate, toDate: LocalDate): List<ExistingRow>

    @Query("SELECT b.slotId FROM BookingEntity b WHERE b.id = :id")
    fun findSlotId(id: Long): Long?

    // PESSIMISTIC_WRITE adds FOR UPDATE.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM BookingEntity b WHERE b.id = :id")
    fun lockById(id: Long): BookingEntity?
}

/** One row of the grouped occupancy query. */
interface OccupancyRow {
    val lessonDate: LocalDate
    val occupancy: Long
}

/** One row of the student's bookings query. */
interface ExistingRow {
    val lessonDate: LocalDate
    val status: BookingStatus
    val cancellationReason: CancellationReason?
}
