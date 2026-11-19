package ar.melocoton.students.application

import ar.melocoton.students.application.port.StudentRepository
import ar.melocoton.students.domain.StudentNotFound
import ar.melocoton.students.domain.StudentProfile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** CU-02, DI-46: a student's profile. */
@Service
class GetStudent(private val students: StudentRepository) {

    @Transactional(readOnly = true)
    fun get(id: Long): StudentProfile = students.findProfile(id) ?: throw StudentNotFound()
}
