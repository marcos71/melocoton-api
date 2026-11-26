package ar.melocoton.students.application

import ar.melocoton.students.application.port.StudentDirectoryEntry
import ar.melocoton.students.application.port.StudentQueries
import ar.melocoton.students.domain.StudentStatusFilter
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** RF-24, RF-62, D-29, DI-76: the students' name and phone, which the assistant teacher may read to find a student. */
@Service
class ListStudentDirectory(private val students: StudentQueries) {

    @Transactional(readOnly = true)
    fun list(status: StudentStatusFilter): List<StudentDirectoryEntry> = students.findDirectory(status)
}
