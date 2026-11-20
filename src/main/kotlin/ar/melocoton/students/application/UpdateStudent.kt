package ar.melocoton.students.application

import ar.melocoton.auth.application.port.UserAccountRepository
import ar.melocoton.shared.domain.WorkshopTime
import ar.melocoton.students.application.port.StudentProfile
import ar.melocoton.students.application.port.StudentQueries
import ar.melocoton.students.application.port.StudentRepository
import ar.melocoton.students.domain.StudentChange
import ar.melocoton.students.domain.StudentNotFound
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** CU-02, DI-46: the lead teacher edits a student's name and phone. Email and modality are not editable here. */
@Service
class UpdateStudent(
    private val accounts: UserAccountRepository,
    private val students: StudentRepository,
    private val studentQueries: StudentQueries,
    private val clock: Clock,
) {
    @Transactional
    fun update(id: Long, change: StudentChange): StudentProfile {
        // Being a student means having a student row: another role's id looks like a missing one (DI-34).
        if (!students.exists(id)) throw StudentNotFound()
        val account = accounts.lockById(id) ?: throw StudentNotFound()
        account.changeContact(change.name, change.phone)
        accounts.saveContact(account)
        return checkNotNull(studentQueries.findProfileWithLessons(id, WorkshopTime.now(clock))) { "Student $id disappeared" }
    }
}
