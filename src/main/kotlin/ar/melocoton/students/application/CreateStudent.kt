package ar.melocoton.students.application

import ar.melocoton.auth.application.port.UserAccountRepository
import ar.melocoton.auth.domain.AccountInvited
import ar.melocoton.auth.domain.NewAccount
import ar.melocoton.shared.application.port.DomainEvents
import ar.melocoton.shared.domain.WorkshopTime
import ar.melocoton.students.application.port.StudentProfile
import ar.melocoton.students.application.port.StudentQueries
import ar.melocoton.students.application.port.StudentRepository
import ar.melocoton.students.domain.EnrollmentType
import ar.melocoton.students.domain.NewStudent
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/**
 * CU-02, RF-04, RF-05, DI-46: the lead teacher registers a student, who defines a password from the
 * emailed invitation (D-11). The account, the student part and the link are created in one transaction;
 * the invitation is issued by the AccountInvited event (DI-54) and the email goes out after the commit (DI-29, DI-34).
 */
@Service
class CreateStudent(
    private val accounts: UserAccountRepository,
    private val students: StudentRepository,
    private val studentQueries: StudentQueries,
    private val events: DomainEvents,
    private val clock: Clock,
) {
    @Transactional
    fun create(
        name: String,
        email: String,
        phone: String,
        enrollmentType: EnrollmentType,
        monthlyStartDate: LocalDate?,
    ): StudentProfile {
        val now = clock.instant()
        val today = LocalDate.now(clock.withZone(WorkshopTime.ZONE))
        val new = NewStudent.create(name, email, phone, enrollmentType, monthlyStartDate, today)

        // A taken email fails here with EmailAlreadyUsed: the unique index is the only check (DI-34).
        val account = accounts.create(NewAccount.student(new.name, new.email, new.phone, now))
        students.create(new.toStudent(account.id))
        events.publish(AccountInvited(account.id, now))
        return checkNotNull(studentQueries.findProfileWithLessons(account.id, WorkshopTime.now(clock))) { "Student ${account.id} was just created" }
    }
}
