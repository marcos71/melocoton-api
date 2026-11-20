package ar.melocoton.students.application

import ar.melocoton.shared.domain.WorkshopTime
import ar.melocoton.students.application.port.StudentProfile
import ar.melocoton.students.application.port.StudentQueries
import ar.melocoton.students.domain.StudentNotFound
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** CU-02, DI-46: a student's profile. */
@Service
class GetStudent(private val students: StudentQueries, private val clock: Clock) {

    @Transactional(readOnly = true)
    fun get(id: Long): StudentProfile = students.findProfileWithLessons(id, WorkshopTime.now(clock)) ?: throw StudentNotFound()
}
