package ar.melocoton.auth.application

import ar.melocoton.auth.application.port.UserAccountRepository
import ar.melocoton.auth.domain.AccountAlreadyInactive
import ar.melocoton.auth.domain.AccountInvited
import ar.melocoton.auth.domain.AssistantTeacherNotFound
import ar.melocoton.auth.domain.EmailAlreadyUsed
import ar.melocoton.auth.domain.NewAccount
import ar.melocoton.auth.domain.UserAccount
import ar.melocoton.shared.application.port.DomainEvents
import ar.melocoton.shared.domain.Role
import io.mockk.CapturingSlot
import io.mockk.confirmVerified
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
import java.time.ZoneOffset

class AssistantTeacherUseCasesTest {

    private val now = Instant.parse("2026-11-16T13:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun account(role: Role, active: Boolean = true) = UserAccount(
        id = 9, name = "Martín", email = "martin@test.local", phone = "1100000000", role = role,
        passwordHash = null, active = active, failedLoginAttempts = 0, lockedUntil = null, sessionsValidFrom = now,
    )

    private val accounts = mockk<UserAccountRepository>()
    private val events = mockk<DomainEvents>()

    private fun create() = CreateAssistantTeacher(accounts, events, clock)

    private fun stubAccountCreation(created: CapturingSlot<NewAccount> = slot()) {
        every { accounts.create(capture(created)) } answers {
            val new = created.captured
            UserAccount(
                id = 9, name = new.name, email = new.email, phone = new.phone, role = new.role,
                passwordHash = null, active = new.active, failedLoginAttempts = 0, lockedUntil = null,
                sessionsValidFrom = new.sessionsValidFrom,
            )
        }
    }

    @Test
    fun `RF53_creatingCreatesTheAssistantTeacherAndPublishesAccountInvited`() {
        val created = slot<NewAccount>()
        stubAccountCreation(created)
        every { events.publish(any()) } just runs

        val result = create().create("Martín", " martin@test.local ", "1100000000")

        assertThat(result.role).isEqualTo(Role.ASSISTANT_TEACHER)
        assertThat(created.captured.email).isEqualTo("martin@test.local")
        verifyOrder {
            accounts.create(any())
            events.publish(AccountInvited(9, now))
        }
        confirmVerified(events)
    }

    @Test
    fun `RF53_aDuplicateEmailPublishesNothing`() {
        every { accounts.create(any()) } throws EmailAlreadyUsed()

        assertThatThrownBy { create().create("Martín", "martin@test.local", "1100000000") }
            .isInstanceOf(EmailAlreadyUsed::class.java)

        verify(exactly = 0) { events.publish(any()) }
    }

    @Test
    fun `RF53_deactivatingSavesTheInactiveAccount`() {
        every { accounts.lockById(9) } returns account(Role.ASSISTANT_TEACHER)
        val saved = slot<UserAccount>()
        every { accounts.saveActive(capture(saved)) } just runs

        val result = DeactivateAssistantTeacher(accounts).deactivate(9)

        assertThat(result.active).isFalse()
        assertThat(saved.captured.active).isFalse()
        verify(exactly = 1) { accounts.saveActive(any()) }
    }

    @Test
    fun `RF53_deactivatingAStudentOrAMissingIdIsNotFoundAndChangesNothing`() {
        every { accounts.lockById(9) } returns account(Role.STUDENT)
        every { accounts.lockById(10) } returns null
        val deactivate = DeactivateAssistantTeacher(accounts)

        assertThatThrownBy { deactivate.deactivate(9) }.isInstanceOf(AssistantTeacherNotFound::class.java)
        assertThatThrownBy { deactivate.deactivate(10) }.isInstanceOf(AssistantTeacherNotFound::class.java)

        verify(exactly = 0) { accounts.saveActive(any()) }
    }

    @Test
    fun `RF53_deactivatingAnInactiveAccountIsAStatusConflict`() {
        every { accounts.lockById(9) } returns account(Role.ASSISTANT_TEACHER, active = false)

        assertThatThrownBy { DeactivateAssistantTeacher(accounts).deactivate(9) }
            .isInstanceOf(AccountAlreadyInactive::class.java)
        verify(exactly = 0) { accounts.saveActive(any()) }
    }
}
