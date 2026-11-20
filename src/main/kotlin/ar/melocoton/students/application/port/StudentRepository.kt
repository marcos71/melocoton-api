package ar.melocoton.students.application.port

import ar.melocoton.students.domain.Student
import ar.melocoton.students.domain.StudentFilter
import ar.melocoton.students.domain.StudentProfile
import ar.melocoton.students.domain.StudentSummary
import ar.melocoton.students.domain.UpcomingLesson
import java.time.LocalDate

/**
 * Output port for students. Reads are composed screens (DI-21): they join the account, the student and
 * the fixed slot in the adapter, so this module does not use the other modules' persistence.
 */
interface StudentRepository {

    /** Stores the student part of an account that already exists, and writes it right away. */
    fun create(student: Student)

    /** The profile, or null when the id is not a student's (it does not exist, or it is another role's). */
    fun findProfile(id: Long): StudentProfile?

    /** Students matching [filter], ordered by name ignoring case and then id. */
    fun findAll(filter: StudentFilter): List<StudentSummary>

    /**
     * The student part of an account, with the account state, locked for update. It is the first lock of
     * every booking use case, taken before any slot (lock order: student, then slots by ascending id).
     * Null when the id is not a student's.
     */
    fun lockById(id: Long): Student?

    /** Writes the fixed slot of a student already locked in this transaction. */
    fun saveFixedSlot(student: Student)

    /** Ids of the active monthly students whose fixed slot is active, ascending. Plain read: lock each student before acting on it. */
    fun findIdsWithActiveFixedSlot(): List<Long>

    /**
     * Scheduled bookings of the student for lessons on or after [fromDate], with their slot's day and time.
     * The date filter is coarse: whether today's lesson has started is for the domain to decide.
     */
    fun findUpcomingLessons(id: Long, fromDate: LocalDate): List<UpcomingLesson>
}
