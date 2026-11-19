package ar.melocoton.auth.application

import ar.melocoton.auth.application.port.AccountLinkRepository
import ar.melocoton.auth.application.port.AccountLinkSender
import ar.melocoton.auth.application.port.AccountLinkTokens
import ar.melocoton.auth.application.port.UserAccountRepository
import ar.melocoton.auth.domain.AccountAlreadyInactive
import ar.melocoton.auth.domain.AccountLink
import ar.melocoton.auth.domain.AccountLinkPurpose
import ar.melocoton.auth.domain.AssistantTeacherNotFound
import ar.melocoton.auth.domain.EmailAlreadyUsed
import ar.melocoton.auth.domain.NewAccount
import ar.melocoton.auth.domain.UserAccount
import ar.melocoton.shared.application.port.AfterCommit
import ar.melocoton.shared.domain.Role
import io.mockk.CapturingSlot
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
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
    private val links = mockk<AccountLinkRepository>()
    private val sender = mockk<AccountLinkSender>()
    private val afterCommit = mockk<AfterCommit>()
    private val tokens = mockk<AccountLinkTokens> {
        every { generate() } returns "raw-token"
        every { hash("raw-token") } returns "hash-of-raw-token"
    }

    /** Actions registered for after the commit; a rollback is simply never running them. */
    private val pending = mutableListOf<() -> Unit>()

    private fun create() = CreateAssistantTeacher(accounts, links, tokens, sender, afterCommit, clock)

    private fun commit() = pending.forEach { it() }

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

    private fun stubAfterCommit() {
        every { afterCommit.run(any()) } answers { pending += firstArg<() -> Unit>() }
    }

    @Test
    fun `RF53_creatingSendsAnInvitationValidForSevenDays`() {
        val created = slot<NewAccount>()
        val link = slot<AccountLink>()
        stubAccountCreation(created)
        stubAfterCommit()
        every { links.save(capture(link)) } just runs
        every { sender.send(any(), any(), any()) } just runs

        val result = create().create("Martín", " martin@test.local ", "1100000000")
        commit()

        assertThat(result.role).isEqualTo(Role.ASSISTANT_TEACHER)
        assertThat(created.captured.email).isEqualTo("martin@test.local")
        assertThat(link.captured.purpose).isEqualTo(AccountLinkPurpose.INVITATION)
        assertThat(link.captured.userId).isEqualTo(9)
        assertThat(link.captured.tokenHash).isEqualTo("hash-of-raw-token")
        assertThat(Duration.between(link.captured.createdAt, link.captured.expiresAt)).isEqualTo(Duration.ofDays(7))
        verify(exactly = 1) { sender.send(match { it.id == 9L }, AccountLinkPurpose.INVITATION, "raw-token") }
        confirmVerified(sender)
    }

    @Test
    fun `RF53_aDuplicateEmailSavesAndSendsNothing`() {
        every { accounts.create(any()) } throws EmailAlreadyUsed()

        assertThatThrownBy { create().create("Martín", "martin@test.local", "1100000000") }
            .isInstanceOf(EmailAlreadyUsed::class.java)

        verify(exactly = 0) { links.save(any()) }
        verify(exactly = 0) { afterCommit.run(any()) }
        verify(exactly = 0) { sender.send(any(), any(), any()) }
    }

    @Test
    fun `RF53_theInvitationIsNotSentUntilTheTransactionCommits`() {
        stubAccountCreation()
        stubAfterCommit()
        every { links.save(any()) } just runs
        every { sender.send(any(), any(), any()) } just runs

        create().create("Martín", "martin@test.local", "1100000000")
        verify(exactly = 0) { sender.send(any(), any(), any()) }

        commit()
        verify(exactly = 1) { sender.send(any(), any(), any()) }
    }

    @Test
    fun `RF53_ifSavingTheLinkFailsNothingIsLeftToSendAfterTheRollback`() {
        stubAccountCreation()
        stubAfterCommit()
        every { links.save(any()) } throws IllegalStateException("link insert failed")

        assertThatThrownBy { create().create("Martín", "martin@test.local", "1100000000") }
            .isInstanceOf(IllegalStateException::class.java)

        // The email is registered only after the link is saved, so a rollback leaves nothing to run.
        verify(exactly = 0) { afterCommit.run(any()) }
        assertThat(pending).isEmpty()
        verify(exactly = 0) { sender.send(any(), any(), any()) }
    }

    @Test
    fun `RF53_ifSendingFailsAfterTheCommitTheAccountIsStillCreated`() {
        stubAccountCreation()
        stubAfterCommit()
        every { links.save(any()) } just runs
        every { sender.send(any(), any(), any()) } throws IllegalStateException("smtp down")

        val result = create().create("Martín", "martin@test.local", "1100000000")

        assertThat(result.id).isEqualTo(9)
        commit() // the failure is logged, not thrown
        verify(exactly = 1) { sender.send(any(), any(), any()) }
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
