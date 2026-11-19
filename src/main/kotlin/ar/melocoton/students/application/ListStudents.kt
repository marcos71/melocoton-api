package ar.melocoton.students.application

import ar.melocoton.students.application.port.StudentRepository
import ar.melocoton.students.domain.StudentFilter
import ar.melocoton.students.domain.StudentSummary
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** CU-02, RF-05: the students list, filtered by state, modality and fixed slot. */
@Service
class ListStudents(private val students: StudentRepository) {

    @Transactional(readOnly = true)
    fun list(filter: StudentFilter): List<StudentSummary> = students.findAll(filter)
}
