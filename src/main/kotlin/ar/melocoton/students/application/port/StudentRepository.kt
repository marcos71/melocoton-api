package ar.melocoton.students.application.port

import ar.melocoton.students.domain.Student

/**
 * Output port for the write side of students: creating the student part of an account and the fixed
 * slot, which the bookings use cases also use. The screen reads are in [StudentQueries] (DI-60).
 */
interface StudentRepository {

    /** Stores the student part of an account that already exists, and writes it right away. */
    fun create(student: Student)

    /** True when the id is a student's, in any state; false when it does not exist or is another role's. */
    fun exists(id: Long): Boolean

    /**
     * The student part of an account, with the account state, locked for update. It is the first lock of
     * every booking use case, taken before any slot (lock order: student, then slots by ascending id).
     * Null when the id is not a student's.
     */
    fun lockById(id: Long): Student?

    /** Writes the fixed slot of a student already locked in this transaction. */
    fun saveFixedSlot(student: Student)

    /**
     * Active students whose fixed slot is [slotId], not counting [excluding] (DI-66). Read it holding the
     * slot lock, after taking it: that is what makes the capacity limit safe under concurrency (D-43).
     */
    fun countFixedStudents(slotId: Long, excluding: Long? = null): Int

    /** [countFixedStudents] of each slot in one query. Slots without fixed students are absent from the map. */
    fun countFixedStudentsBySlot(slotIds: Collection<Long>): Map<Long, Int>

    /** Ids of the active monthly students whose fixed slot is active, ascending. Plain read: lock each student before acting on it. */
    fun findIdsWithActiveFixedSlot(): List<Long>
}
