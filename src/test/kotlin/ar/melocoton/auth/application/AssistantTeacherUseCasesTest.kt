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

    /** In-memory accounts that record what the use cases did. */
    private class FakeAccounts(private val stored: UserAccount? = null, private val emailTaken: Boolean = false) :
        UserAccountRepository {
        val created = mutableListOf<NewAccount>()
        val savedActive = mutableListOf<Boolean>()

        override fun lockByEmail(email: String) = error("not used")
        override fun findById(id: Long) = error("not used")
        override fun lockById(id: Long) = stored?.takeIf { it.id == id }
        override fun saveLoginState(account: UserAccount) = error("not used")
        override fun savePassword(account: UserAccount) = error("not used")
        override fun create(account: NewAccount): UserAccount {
            if (emailTaken) throw EmailAlreadyUsed()
            created += account
            return UserAccount(
                id = 9, name = account.name, email = account.email, phone = account.phone, role = account.role,
                passwordHash = null, active = account.active, failedLoginAttempts = 0, lockedUntil = null,
                sessionsValidFrom = account.sessionsValidFrom,
            )
        }
        override fun findAllByRole(role: Role, active: Boolean?) = error("not used")
        override fun saveActive(account: UserAccount) {
            savedActive += account.active
        }
    }

    private class FakeLinks : AccountLinkRepository {
        val saved = mutableListOf<AccountLink>()
        override fun save(link: AccountLink) {
            saved += link
        }
        override fun lockByTokenHash(tokenHash: String) = error("not used")
        override fun countCreatedSince(userId: Long, purpose: AccountLinkPurpose, since: Instant) = error("not used")
        override fun useAllOpen(userId: Long, now: Instant) = error("not used")
    }

    private class FakeSender(private val failure: Exception? = null) : AccountLinkSender {
        val sent = mutableListOf<Triple<Long, AccountLinkPurpose, String>>()
        override fun send(account: UserAccount, purpose: AccountLinkPurpose, token: String) {
            failure?.let { throw it }
            sent += Triple(account.id, purpose, token)
        }
    }

    /** Holds the actions until [commit]; a rollback is simply never calling it. */
    private class FakeAfterCommit : AfterCommit {
        val pending = mutableListOf<() -> Unit>()
        override fun run(action: () -> Unit) {
            pending += action
        }
        fun commit() = pending.forEach { it() }
    }

    private fun create(
        accounts: UserAccountRepository,
        links: AccountLinkRepository,
        sender: AccountLinkSender,
        afterCommit: AfterCommit,
    ) = CreateAssistantTeacher(accounts, links, tokens, sender, afterCommit, clock)

    private val tokens = object : AccountLinkTokens {
        override fun generate() = "raw-token"
        override fun hash(token: String) = "hash-of-$token"
    }

    @Test
    fun `RF53_creatingSendsAnInvitationValidForSevenDays`() {
        val accounts = FakeAccounts()
        val links = FakeLinks()
        val sender = FakeSender()
        val afterCommit = FakeAfterCommit()

        val result = create(accounts, links, sender, afterCommit).create("Martín", " martin@test.local ", "1100000000")
        afterCommit.commit()

        assertThat(result.role).isEqualTo(Role.ASSISTANT_TEACHER)
        assertThat(accounts.created.single().email).isEqualTo("martin@test.local")
        val link = links.saved.single()
        assertThat(link.purpose).isEqualTo(AccountLinkPurpose.INVITATION)
        assertThat(link.userId).isEqualTo(9)
        assertThat(link.tokenHash).isEqualTo("hash-of-raw-token")
        assertThat(Duration.between(link.createdAt, link.expiresAt)).isEqualTo(Duration.ofDays(7))
        assertThat(sender.sent).containsExactly(Triple(9L, AccountLinkPurpose.INVITATION, "raw-token"))
    }

    @Test
    fun `RF53_aDuplicateEmailSavesAndSendsNothing`() {
        val accounts = FakeAccounts(emailTaken = true)
        val links = FakeLinks()
        val sender = FakeSender()
        val afterCommit = FakeAfterCommit()

        assertThatThrownBy {
            create(accounts, links, sender, afterCommit).create("Martín", "martin@test.local", "1100000000")
        }.isInstanceOf(EmailAlreadyUsed::class.java)

        assertThat(accounts.created).isEmpty()
        assertThat(links.saved).isEmpty()
        assertThat(afterCommit.pending).isEmpty()
        assertThat(sender.sent).isEmpty()
    }

    @Test
    fun `RF53_theInvitationIsNotSentUntilTheTransactionCommits`() {
        val sender = FakeSender()
        val afterCommit = FakeAfterCommit()

        create(FakeAccounts(), FakeLinks(), sender, afterCommit).create("Martín", "martin@test.local", "1100000000")
        assertThat(sender.sent).isEmpty()

        afterCommit.commit()
        assertThat(sender.sent).hasSize(1)
    }

    @Test
    fun `RF53_ifSavingTheLinkFailsNothingIsLeftToSendAfterTheRollback`() {
        val sender = FakeSender()
        val afterCommit = FakeAfterCommit()
        val failingLinks = object : AccountLinkRepository by FakeLinks() {
            override fun save(link: AccountLink) = throw IllegalStateException("link insert failed")
        }

        assertThatThrownBy {
            create(FakeAccounts(), failingLinks, sender, afterCommit).create("Martín", "martin@test.local", "1100000000")
        }.isInstanceOf(IllegalStateException::class.java)

        // The email is registered only after the link is saved, so a rollback leaves nothing to run.
        assertThat(afterCommit.pending).isEmpty()
        assertThat(sender.sent).isEmpty()
    }

    @Test
    fun `RF53_ifSendingFailsAfterTheCommitTheAccountIsStillCreated`() {
        val afterCommit = FakeAfterCommit()
        val sender = FakeSender(failure = IllegalStateException("smtp down"))

        val result = create(FakeAccounts(), FakeLinks(), sender, afterCommit).create("Martín", "martin@test.local", "1100000000")

        assertThat(result.id).isEqualTo(9)
        afterCommit.commit() // the failure is logged, not thrown
    }

    @Test
    fun `RF53_deactivatingSavesTheInactiveAccount`() {
        val accounts = FakeAccounts(stored = account(Role.ASSISTANT_TEACHER))

        val result = DeactivateAssistantTeacher(accounts).deactivate(9)

        assertThat(result.active).isFalse()
        assertThat(accounts.savedActive).containsExactly(false)
    }

    @Test
    fun `RF53_deactivatingAStudentOrAMissingIdIsNotFoundAndChangesNothing`() {
        val accounts = FakeAccounts(stored = account(Role.STUDENT))
        val deactivate = DeactivateAssistantTeacher(accounts)

        assertThatThrownBy { deactivate.deactivate(9) }.isInstanceOf(AssistantTeacherNotFound::class.java)
        assertThatThrownBy { deactivate.deactivate(10) }.isInstanceOf(AssistantTeacherNotFound::class.java)

        assertThat(accounts.savedActive).isEmpty()
    }

    @Test
    fun `RF53_deactivatingAnInactiveAccountIsAStatusConflict`() {
        val accounts = FakeAccounts(stored = account(Role.ASSISTANT_TEACHER, active = false))

        assertThatThrownBy { DeactivateAssistantTeacher(accounts).deactivate(9) }
            .isInstanceOf(AccountAlreadyInactive::class.java)
        assertThat(accounts.savedActive).isEmpty()
    }
}
