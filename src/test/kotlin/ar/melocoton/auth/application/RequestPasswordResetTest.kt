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
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class RequestPasswordResetTest {

    private val now = Instant.parse("2026-11-16T13:00:00Z")
    private val accounts = mockk<UserAccountRepository>()
    private val links = mockk<AccountLinkRepository>()
    private val tokens = mockk<AccountLinkTokens>()
    private val sender = mockk<AccountLinkSender>()
    private val afterCommit = mockk<AfterCommit>()
    private val useCase = RequestPasswordReset(accounts, links, tokens, sender, afterCommit, Clock.fixed(now, ZoneOffset.UTC))

    private fun account(active: Boolean = true) = UserAccount(
        id = 9, name = "Martín", email = "martin@test.local", phone = "1100000000", role = Role.STUDENT,
        passwordHash = null, active = active, failedLoginAttempts = 0, lockedUntil = null, sessionsValidFrom = now,
    )

    private fun verifyNothingSavedOrSent() {
        verify(exactly = 0) { links.save(any()) }
        verify(exactly = 0) { afterCommit.run(any()) }
        verify(exactly = 0) { sender.send(any(), any(), any()) }
    }

    @Test
    fun `CU01_2c_anUnknownEmailSavesAndSendsNothing`() {
        every { accounts.lockByEmail("nobody@test.local") } returns null

        useCase.request(" nobody@test.local ")

        verify(exactly = 1) { accounts.lockByEmail("nobody@test.local") }
        verifyNothingSavedOrSent()
        confirmVerified(accounts, links, sender, afterCommit)
    }

    @Test
    fun `DI33_anInactiveAccountSavesAndSendsNothing`() {
        every { accounts.lockByEmail("martin@test.local") } returns account(active = false)
        every { links.countCreatedSince(9, AccountLinkPurpose.PASSWORD_RESET, any()) } returns 0

        useCase.request("martin@test.local")

        verifyNothingSavedOrSent()
    }

    @Test
    fun `DI31_threeResetsInTheLastHourSaveAndSendNothing`() {
        every { accounts.lockByEmail("martin@test.local") } returns account()
        every {
            links.countCreatedSince(9, AccountLinkPurpose.PASSWORD_RESET, now.minus(Duration.ofHours(1)))
        } returns AccountLink.MAX_RESETS_PER_HOUR

        useCase.request("martin@test.local")

        verifyNothingSavedOrSent()
    }

    @Test
    fun `CU01_2c_anActiveAccountUnderTheLimitSavesAOneHourLinkAndSendsItAfterTheCommit`() {
        val acct = account()
        every { accounts.lockByEmail("martin@test.local") } returns acct
        every {
            links.countCreatedSince(9, AccountLinkPurpose.PASSWORD_RESET, now.minus(Duration.ofHours(1)))
        } returns AccountLink.MAX_RESETS_PER_HOUR - 1
        every { tokens.generate() } returns "raw-token"
        every { tokens.hash("raw-token") } returns "hash-of-raw-token"
        val saved = slot<AccountLink>()
        every { links.save(capture(saved)) } just runs
        val action = slot<() -> Unit>()
        every { afterCommit.run(capture(action)) } just runs
        every { sender.send(acct, AccountLinkPurpose.PASSWORD_RESET, "raw-token") } just runs

        useCase.request("martin@test.local")

        assertThat(saved.captured.purpose).isEqualTo(AccountLinkPurpose.PASSWORD_RESET)
        assertThat(saved.captured.userId).isEqualTo(9)
        assertThat(saved.captured.tokenHash).isEqualTo("hash-of-raw-token")
        assertThat(Duration.between(saved.captured.createdAt, saved.captured.expiresAt)).isEqualTo(Duration.ofHours(1))
        // Registered for after the commit, not sent yet.
        verify(exactly = 1) { afterCommit.run(any()) }
        verify(exactly = 0) { sender.send(any(), any(), any()) }

        action.captured()

        verify(exactly = 1) { sender.send(acct, AccountLinkPurpose.PASSWORD_RESET, "raw-token") }
    }

    @Test
    fun `DI33_ifSendingFailsRequestStillDoesNotThrow`() {
        every { accounts.lockByEmail("martin@test.local") } returns account()
        every { links.countCreatedSince(9, AccountLinkPurpose.PASSWORD_RESET, any()) } returns 0
        every { tokens.generate() } returns "raw-token"
        every { tokens.hash("raw-token") } returns "hash-of-raw-token"
        every { links.save(any()) } just runs
        every { afterCommit.run(any()) } answers { firstArg<() -> Unit>().invoke() }
        every { sender.send(any(), any(), any()) } throws IllegalStateException("smtp down")

        useCase.request("martin@test.local")

        verify(exactly = 1) { sender.send(any(), AccountLinkPurpose.PASSWORD_RESET, "raw-token") }
    }
}
