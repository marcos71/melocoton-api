package ar.melocoton.students.application.port

import ar.melocoton.students.domain.Student
import ar.melocoton.students.domain.StudentFilter
import ar.melocoton.students.domain.StudentProfile
import ar.melocoton.students.domain.StudentSummary

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
}
