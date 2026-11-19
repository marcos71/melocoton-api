package ar.melocoton.slots.infrastructure.persistence

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface SlotJpaRepository : JpaRepository<SlotEntity, Long> {

    @Query("SELECT s FROM SlotEntity s WHERE s.active = true ORDER BY s.id")
    fun findActive(): List<SlotEntity>

    // PESSIMISTIC_WRITE adds FOR UPDATE.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SlotEntity s WHERE s.id = :id")
    fun lockById(id: Long): SlotEntity?

    // A null `active` means both states. cast(... as boolean) lets PostgreSQL type the null parameter.
    @Query(
        "SELECT s FROM SlotEntity s WHERE (cast(:active as boolean) IS NULL OR s.active = :active) " +
            "ORDER BY s.dayOfWeek, s.startTime, s.id",
    )
    fun findAllByActive(active: Boolean?): List<SlotEntity>
}
