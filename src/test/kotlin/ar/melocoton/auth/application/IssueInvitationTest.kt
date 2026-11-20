package ar.melocoton.auth.application

import ar.melocoton.auth.application.port.AccountLinkRepository
import ar.melocoton.auth.application.port.AccountLinkSender
import ar.melocoton.auth.application.port.AccountLinkTokens
import ar.melocoton.auth.application.port.UserAccountRepository
import ar.melocoton.auth.domain.AccountLink
import ar.melocoton.auth.domain.AccountLinkPurpose
import ar.melocoton.auth.domain.UserAccount
import ar.melocoton.shared.application.port.AfterCommit
import ar.melocoton.shared.domain.Role
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
import java.time.Duration
import java.time.Instant

class IssueInvitationTest {

    private val now = Instant.parse("2026-11-16T13:00:00Z")

    private val account = UserAccount(
        id = 9, name = "Martín", email = "martin@test.local", phone = "1100000000", role = Role.ASSISTANT_TEACHER,
        passwordHash = null, active = true, failedLoginAttempts = 0, lockedUntil = null, sessionsValidFrom = now,
    )

    private val accounts = mockk<UserAccountRepository> { every { findById(9) } returns account }
    private val links = mockk<AccountLinkRepository>()
    private val sender = mockk<AccountLinkSender>()
    private val afterCommit = mockk<AfterCommit>()
    private val tokens = mockk<AccountLinkTokens> {
        every { generate() } returns "raw-token"
        every { hash("raw-token") } returns "hash-of-raw-token"
    }

    /** Actions registered for after the commit; a rollback is simply never running them. */
    private val pending = mutableListOf<() -> Unit>()

    private fun issueInvitation() = IssueInvitation(accounts, links, tokens, sender, afterCommit)

    private fun commit() = pending.forEach { it() }

    private fun stubAfterCommit() {
        every { afterCommit.run(any()) } answers { pending += firstArg<() -> Unit>() }
    }

    @Test
    fun `DI54_issuingSavesAnInvitationLinkValidForSevenDaysAndSendsItAfterTheCommit`() {
        val link = slot<AccountLink>()
        stubAfterCommit()
        every { links.save(capture(link)) } just runs
        every { sender.send(any(), any(), any()) } just runs

        issueInvitation().issue(9, now)

        assertThat(link.captured.purpose).isEqualTo(AccountLinkPurpose.INVITATION)
        assertThat(link.captured.userId).isEqualTo(9)
        assertThat(link.captured.tokenHash).isEqualTo("hash-of-raw-token")
        assertThat(Duration.between(link.captured.createdAt, link.captured.expiresAt)).isEqualTo(Duration.ofDays(7))
        // Nothing is sent until the transaction commits.
        verify(exactly = 0) { sender.send(any(), any(), any()) }
        commit()
        verify(exactly = 1) { sender.send(match { it.id == 9L }, AccountLinkPurpose.INVITATION, "raw-token") }
        confirmVerified(sender)
    }

    @Test
    fun `DI54_ifSavingTheLinkFailsNothingIsLeftToSendAfterTheRollback`() {
        stubAfterCommit()
        every { links.save(any()) } throws IllegalStateException("link insert failed")

        assertThatThrownBy { issueInvitation().issue(9, now) }.isInstanceOf(IllegalStateException::class.java)

        // The email is registered only after the link is saved, so a rollback leaves nothing to run.
        verify(exactly = 0) { afterCommit.run(any()) }
        assertThat(pending).isEmpty()
        verify(exactly = 0) { sender.send(any(), any(), any()) }
    }

    @Test
    fun `RF53_ifSendingFailsAfterTheCommitTheFailureIsLoggedAndNotThrown`() {
        stubAfterCommit()
        every { links.save(any()) } just runs
        every { sender.send(any(), any(), any()) } throws IllegalStateException("smtp down")

        issueInvitation().issue(9, now)
        commit() // the failure is logged, not thrown

        verify(exactly = 1) { sender.send(any(), any(), any()) }
    }

    @Test
    fun `DI54_aMissingAccountFailsAndSavesNothing`() {
        every { accounts.findById(10) } returns null

        assertThatThrownBy { issueInvitation().issue(10, now) }.isInstanceOf(IllegalStateException::class.java)

        verify(exactly = 0) { links.save(any()) }
        verify(exactly = 0) { afterCommit.run(any()) }
    }
}
