package ar.melocoton.bookings.infrastructure.persistence

import ar.melocoton.bookings.application.port.BookingRepository
import ar.melocoton.bookings.domain.BookingStatus
import org.springframework.stereotype.Repository
import java.time.LocalDate

/** Adapter of [BookingRepository] over Spring Data JPA. */
@Repository
class BookingRepositoryJpa(private val jpa: BookingJpaRepository) : BookingRepository {

    override fun occupancyByLessonDate(slotId: Long, fromDate: LocalDate): Map<LocalDate, Int> =
        jpa.countByLessonDate(slotId, fromDate, BookingStatus.OCCUPYING)
            .associate { it.lessonDate to it.occupancy.toInt() }

    override fun existsForSlot(slotId: Long): Boolean = jpa.existsBySlotId(slotId)
}
