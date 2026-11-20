package ar.melocoton.students.application

import ar.melocoton.auth.application.port.UserAccountRepository
import ar.melocoton.auth.domain.AccountInvited
import ar.melocoton.auth.domain.EmailAlreadyUsed
import ar.melocoton.auth.domain.NewAccount
import ar.melocoton.auth.domain.UserAccount
import ar.melocoton.shared.application.port.DomainEvents
import ar.melocoton.shared.domain.Role
import ar.melocoton.students.application.port.StudentRepository
import ar.melocoton.students.domain.EnrollmentType
import ar.melocoton.students.domain.InvalidStudent
import ar.melocoton.students.domain.Student
import ar.melocoton.students.domain.StudentChange
import ar.melocoton.students.domain.StudentFilter
import ar.melocoton.students.domain.StudentNotFound
import ar.melocoton.students.domain.StudentProfile
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class StudentUseCasesTest {

    // 01:00 UTC on the 20th is still the 19th in Buenos Aires.
    private val now = Instant.parse("2026-11-20T01:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 11, 19)

    private val accounts = mockk<UserAccountRepository>()
    private val students = mockk<StudentRepository>()
    private val events = mockk<DomainEvents>()

    private fun account(name: String = "Lucía", phone: String = "1155550000") = UserAccount(
        id = 9, name = name, email = "lucia@test.local", phone = phone, role = Role.STUDENT,
        passwordHash = null, active = true, failedLoginAttempts = 0, lockedUntil = null, sessionsValidFrom = now,
    )

    private fun profile(type: EnrollmentType = EnrollmentType.MONTHLY, start: LocalDate? = today) = StudentProfile(
        id = 9, name = "Lucía", email = "lucia@test.local", phone = "1155550000", active = true,
        enrollmentType = type, monthlyStartDate = start, fixedSlot = null,
    )

    private fun createStudent() = CreateStudent(accounts, students, events, clock)

    private fun stubCreation(createdAccount: io.mockk.CapturingSlot<NewAccount> = slot()) {
        every { accounts.create(capture(createdAccount)) } answers { account() }
        every { students.create(any()) } just runs
        every { events.publish(any()) } just runs
        every { students.findProfile(9) } returns profile()
    }

    // ---- CreateStudent ----

    @Test
    fun `RF04_creatingAStudentSavesTheAccountAndTheStudentAndPublishesAccountInvited`() {
        val newAccount = slot<NewAccount>()
        val student = slot<Student>()
        stubCreation(newAccount)
        every { students.create(capture(student)) } just runs

        val result = createStudent().create(" Lucía ", " lucia@test.local ", "1155550000", EnrollmentType.MONTHLY, null)

        assertThat(newAccount.captured.role).isEqualTo(Role.STUDENT)
        assertThat(newAccount.captured.email).isEqualTo("lucia@test.local")
        assertThat(student.captured.userId).isEqualTo(9)
        assertThat(student.captured.enrollmentType).isEqualTo(EnrollmentType.MONTHLY)
        assertThat(student.captured.fixedSlotId).isNull()
        // The workshop's today, not the UTC date.
        assertThat(student.captured.monthlyStartDate).isEqualTo(today)
        verifyOrder {
            accounts.create(any())
            students.create(any())
            events.publish(AccountInvited(9, now))
        }
        assertThat(result.id).isEqualTo(9)
    }

    @Test
    fun `RF05_aDropInStudentIsSavedWithoutStartDate`() {
        val student = slot<Student>()
        stubCreation()
        every { students.create(capture(student)) } just runs
        every { students.findProfile(9) } returns profile(EnrollmentType.DROP_IN, null)

        createStudent().create("Lucía", "lucia@test.local", "1155550000", EnrollmentType.DROP_IN, null)

        assertThat(student.captured.enrollmentType).isEqualTo(EnrollmentType.DROP_IN)
        assertThat(student.captured.monthlyStartDate).isNull()
    }

    @Test
    fun `RF05_aDropInStudentWithAStartDateIsRejectedBeforeSavingAnything`() {
        assertThatThrownBy {
            createStudent().create("Lucía", "lucia@test.local", "1155550000", EnrollmentType.DROP_IN, today)
        }.isInstanceOf(InvalidStudent::class.java)

        verify(exactly = 0) { accounts.create(any()) }
        verify(exactly = 0) { students.create(any()) }
        verify(exactly = 0) { events.publish(any()) }
    }

    @Test
    fun `RF04_aDuplicateEmailSavesAndPublishesNothing`() {
        every { accounts.create(any()) } throws EmailAlreadyUsed()

        assertThatThrownBy {
            createStudent().create("Lucía", "lucia@test.local", "1155550000", EnrollmentType.MONTHLY, null)
        }.isInstanceOf(EmailAlreadyUsed::class.java)

        verify(exactly = 0) { students.create(any()) }
        verify(exactly = 0) { events.publish(any()) }
    }

    // ---- UpdateStudent ----

    @Test
    fun `RF04_editingChangesTheNameAndPhoneThroughTheAccountPort`() {
        val saved = slot<UserAccount>()
        every { students.findProfile(9) } returns profile()
        every { accounts.lockById(9) } returns account()
        every { accounts.saveContact(capture(saved)) } just runs

        UpdateStudent(accounts, students).update(9, StudentChange(" Lucía G. ", null))

        assertThat(saved.captured.name).isEqualTo("Lucía G.")
        assertThat(saved.captured.phone).isEqualTo("1155550000")
        verify(exactly = 1) { accounts.saveContact(any()) }
    }

    @Test
    fun `RF04_editingAnIdThatIsNotAStudentIsNotFoundAndChangesNothing`() {
        every { students.findProfile(9) } returns null

        assertThatThrownBy { UpdateStudent(accounts, students).update(9, StudentChange("Lucía G.", null)) }
            .isInstanceOf(StudentNotFound::class.java)

        verify(exactly = 0) { accounts.lockById(any()) }
        verify(exactly = 0) { accounts.saveContact(any()) }
    }

    // ---- GetStudent, ListStudents ----

    @Test
    fun `RF05_aMissingStudentIsNotFound`() {
        every { students.findProfile(5) } returns null

        assertThatThrownBy { GetStudent(students).get(5) }.isInstanceOf(StudentNotFound::class.java)
    }

    @Test
    fun `RF05_listingPassesTheFilterToThePort`() {
        val filter = StudentFilter(enrollmentType = EnrollmentType.DROP_IN, slotId = 3)
        every { students.findAll(filter) } returns emptyList()

        assertThat(ListStudents(students).list(filter)).isEmpty()
        verify(exactly = 1) { students.findAll(filter) }
    }
}
