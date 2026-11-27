package ar.melocoton.students.application.port

import ar.melocoton.students.domain.StudentFilter
import ar.melocoton.students.domain.StudentStatusFilter
import java.time.LocalDate

/**
 * Output port for the composed reads of the students screens (PLAN :175, DI-21, DI-60): they join the
 * account, the student, the fixed slot and the bookings in the adapter, so this module does not use the
 * other modules' persistence.
 */
interface StudentQueries {

    /** The profile, or null when the id is not a student's (it does not exist, or it is another role's). */
    fun findProfile(id: Long): StudentProfile?

    /** Students matching [filter], ordered by name ignoring case and then id. */
    fun findAll(filter: StudentFilter): List<StudentSummary>

    /** Students in the given state with only name and phone, ordered by name ignoring case and then id (DI-76). */
    fun findDirectory(status: StudentStatusFilter): List<StudentDirectoryEntry>

    /**
     * Scheduled bookings of the student for lessons on or after [fromDate], with their slot's day and time.
     * The adapter returns them ordered by lesson date, start time and id.
     * The date filter is coarse: whether today's lesson has started is for the domain to decide.
     */
    fun findUpcomingLessons(id: Long, fromDate: LocalDate): List<UpcomingLesson>
}
