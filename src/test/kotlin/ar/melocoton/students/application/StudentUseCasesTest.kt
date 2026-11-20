package ar.melocoton.students.application

import ar.melocoton.auth.application.port.UserAccountRepository
import ar.melocoton.auth.domain.AccountInvited
import ar.melocoton.auth.domain.EmailAlreadyUsed
import ar.melocoton.auth.domain.NewAccount
import ar.melocoton.auth.domain.UserAccount
import ar.melocoton.bookings.domain.BookingType
import ar.melocoton.shared.application.port.DomainEvents
import ar.melocoton.shared.domain.Role
import ar.melocoton.students.application.port.StudentProfile
import ar.melocoton.students.application.port.StudentQueries
import ar.melocoton.students.application.port.StudentRepository
import ar.melocoton.students.application.port.UpcomingLesson
import ar.melocoton.students.domain.EnrollmentType
import ar.melocoton.students.domain.InvalidStudent
import ar.melocoton.students.domain.Student
import ar.melocoton.students.domain.StudentChange
import ar.melocoton.students.domain.StudentFilter
import ar.melocoton.students.domain.StudentNotFound
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
import java.time.LocalTime
import java.time.ZoneOffset

class StudentUseCasesTest {

    // 01:00 UTC on the 20th is still the 19th in Buenos Aires.
    private val now = Instant.parse("2026-11-20T01:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 11, 19)

    private val accounts = mockk<UserAccountRepository>()
    private val students = mockk<StudentRepository>()
    private val queries = mockk<StudentQueries>()
    private val events = mockk<DomainEvents>()

    private fun account(name: String = "Lucía", phone: String = "1155550000") = UserAccount(
        id = 9, name = name, email = "lucia@test.local", phone = phone, role = Role.STUDENT,
        passwordHash = null, active = true, failedLoginAttempts = 0, lockedUntil = null, sessionsValidFrom = now,
    )

    private fun profile(type: EnrollmentType = EnrollmentType.MONTHLY, start: LocalDate? = today) = StudentProfile(
        id = 9, name = "Lucía", email = "lucia@test.local", phone = "1155550000", active = true,
        enrollmentType = type, monthlyStartDate = start, fixedSlot = null,
    )

    private fun createStudent() = CreateStudent(accounts, students, queries, events, clock)

    private fun stubCreation(createdAccount: io.mockk.CapturingSlot<NewAccount> = slot()) {
        every { accounts.create(capture(createdAccount)) } answers { account() }
        every { students.create(any()) } just runs
        every { events.publish(any()) } just runs
        every { queries.findProfile(9) } returns profile()
        every { queries.findUpcomingLessons(9, today) } returns emptyList()
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
        every { queries.findProfile(9) } returns profile(EnrollmentType.DROP_IN, null)

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
        every { students.exists(9) } returns true
        every { queries.findProfile(9) } returns profile()
        every { queries.findUpcomingLessons(9, today) } returns emptyList()
        every { accounts.lockById(9) } returns account()
        every { accounts.saveContact(capture(saved)) } just runs

        UpdateStudent(accounts, students, queries, clock).update(9, StudentChange(" Lucía G. ", null))

        assertThat(saved.captured.name).isEqualTo("Lucía G.")
        assertThat(saved.captured.phone).isEqualTo("1155550000")
        verify(exactly = 1) { accounts.saveContact(any()) }
    }

    @Test
    fun `RF04_editingAnIdThatIsNotAStudentIsNotFoundAndChangesNothing`() {
        every { students.exists(9) } returns false

        assertThatThrownBy { UpdateStudent(accounts, students, queries, clock).update(9, StudentChange("Lucía G.", null)) }
            .isInstanceOf(StudentNotFound::class.java)

        verify(exactly = 0) { accounts.lockById(any()) }
        verify(exactly = 0) { accounts.saveContact(any()) }
        verify(exactly = 0) { queries.findProfile(any()) }
    }

    // ---- GetStudent, ListStudents ----

    @Test
    fun `DI46_theProfileListsOnlyTheClassesThatHaveNotStarted`() {
        // The clock is 2026-11-19 22:00 in Buenos Aires.
        fun lesson(id: Long, date: LocalDate, start: String) = UpcomingLesson(
            id, date, 1, date.dayOfWeek, LocalTime.parse(start), LocalTime.parse(start).plusHours(2), BookingType.FIXED,
        )
        every { queries.findProfile(9) } returns profile()
        every { queries.findUpcomingLessons(9, today) } returns listOf(
            lesson(1, today, "18:00"),
            lesson(2, today, "23:00"),
            lesson(3, today.plusDays(7), "18:00"),
        )

        val result = GetStudent(queries, clock).get(9)

        assertThat(result.upcomingLessons.map { it.bookingId }).containsExactly(2, 3)
    }

    @Test
    fun `RF05_aMissingStudentIsNotFound`() {
        every { queries.findProfile(5) } returns null

        assertThatThrownBy { GetStudent(queries, clock).get(5) }.isInstanceOf(StudentNotFound::class.java)
    }

    @Test
    fun `RF05_listingPassesTheFilterToThePort`() {
        val filter = StudentFilter(enrollmentType = EnrollmentType.DROP_IN, slotId = 3)
        every { queries.findAll(filter) } returns emptyList()

        assertThat(ListStudents(queries).list(filter)).isEmpty()
        verify(exactly = 1) { queries.findAll(filter) }
    }
}
