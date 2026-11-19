package ar.melocoton.bookings.infrastructure.persistence

import ar.melocoton.bookings.domain.BookingStatus
import org.springframework.data.jpa.repository.JpaRepository
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

    fun existsBySlotId(slotId: Long): Boolean
}

/** One row of the grouped occupancy query. */
interface OccupancyRow {
    val lessonDate: LocalDate
    val occupancy: Long
}
