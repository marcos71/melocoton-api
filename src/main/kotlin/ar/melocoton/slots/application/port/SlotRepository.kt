package ar.melocoton.slots.application.port

import ar.melocoton.slots.domain.NewSlot
import ar.melocoton.slots.domain.Slot
import ar.melocoton.slots.domain.SlotStatusFilter

/** Output port for slots. */
interface SlotRepository {

    fun create(slot: NewSlot): Slot

    fun findById(id: Long): Slot?

    /** Slots by status, ordered by day of week and start time. */
    fun findAll(status: SlotStatusFilter): List<Slot>

    /**
     * Takes the transaction-scoped lock that serializes every change to the weekly schedule (DI-25):
     * creating a slot or moving one. Call it BEFORE reading the active slots. A row lock is not enough
     * for the overlap check: in READ COMMITTED a `FOR UPDATE` that waited does not see slots the other
     * transaction inserted meanwhile, and with no active slots there is nothing to lock at all. An
     * advisory lock on a fixed key does not depend on rows; once it is granted, the next plain read
     * is a new statement that sees everything the previous holder committed. Released at commit or rollback.
     */
    fun lockSchedule()

    /** The slot, locked for update (D-43): a capacity check cannot race a booking that takes a place in it. */
    fun lockById(id: Long): Slot?

    /** All active slots, ordered by id. Plain read: take [lockSchedule] first when the result guards a write. */
    fun findActive(): List<Slot>

    /** Writes the editable fields (day, time, capacity) of a slot already loaded in this transaction. */
    fun save(slot: Slot)
}
