package ar.melocoton.slots.infrastructure.persistence

import ar.melocoton.slots.application.port.SlotRepository
import ar.melocoton.slots.domain.NewSlot
import ar.melocoton.slots.domain.Slot
import ar.melocoton.slots.domain.SlotStatusFilter
import jakarta.persistence.EntityManager
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository

/** Adapter of [SlotRepository] over Spring Data JPA. */
@Repository
class SlotRepositoryJpa(
    private val jpa: SlotJpaRepository,
    private val entityManager: EntityManager,
) : SlotRepository {

    override fun create(slot: NewSlot): Slot = jpa.saveAndFlush(slot.toEntity()).toDomain()

    override fun findById(id: Long): Slot? = jpa.findByIdOrNull(id)?.toDomain()

    override fun findAll(status: SlotStatusFilter): List<Slot> {
        val active = when (status) {
            SlotStatusFilter.ACTIVE -> true
            SlotStatusFilter.INACTIVE -> false
            SlotStatusFilter.ALL -> null
        }
        return jpa.findAllByActive(active).map { it.toDomain() }
    }

    override fun lockSchedule() {
        // Waits (up to lock_timeout) while another transaction holds it. The wrapping SELECT turns the
        // void result of the function into a row Hibernate can read.
        entityManager.createNativeQuery("SELECT count(*) FROM (SELECT pg_advisory_xact_lock(:key)) AS locked")
            .setParameter("key", SCHEDULE_LOCK_KEY)
            .singleResult
    }

    override fun lockById(id: Long): Slot? = jpa.lockById(id)?.toDomain()

    override fun findActive(): List<Slot> = jpa.findActive().map { it.toDomain() }

    override fun save(slot: Slot) {
        // The row is already loaded in the use case transaction; the change is written at commit.
        val entity = checkNotNull(jpa.findByIdOrNull(slot.id)) { "Slot ${slot.id} disappeared" }
        entity.dayOfWeek = slot.dayOfWeek.value.toShort()
        entity.startTime = slot.startTime
        entity.endTime = slot.endTime
    }

    private companion object {
        /** Arbitrary fixed key of the schedule advisory lock ("SLOT" as ASCII). No other lock may use it. */
        const val SCHEDULE_LOCK_KEY = 0x534C4F54L
    }
}
