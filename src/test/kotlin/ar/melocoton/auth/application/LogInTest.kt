package ar.melocoton.auth.application

import ar.melocoton.auth.application.port.PasswordHasher
import ar.melocoton.auth.application.port.SessionToken
import ar.melocoton.auth.application.port.SessionTokens
import ar.melocoton.auth.application.port.UserAccountRepository
import ar.melocoton.auth.domain.InvalidCredentials
import ar.melocoton.auth.domain.NewAccount
import ar.melocoton.auth.domain.UserAccount
import ar.melocoton.shared.domain.Role
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class LogInTest {

    private val now = Instant.parse("2026-11-15T13:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    /** In-memory accounts that record what the use case saved. */
    private class FakeAccounts(vararg accounts: UserAccount) : UserAccountRepository {
        val byEmail = accounts.associateBy { it.email.lowercase() }
        val saved = mutableListOf<Pair<Int, Instant?>>()
        override fun lockByEmail(email: String) = byEmail[email.lowercase()]
        override fun findById(id: Long) = byEmail.values.find { it.id == id }
        override fun lockById(id: Long) = findById(id)
        override fun saveLoginState(account: UserAccount) {
            saved += account.failedLoginAttempts to account.lockedUntil
        }
        override fun savePassword(account: UserAccount) = error("Logging in never changes the password")
        override fun create(account: NewAccount) = error("Logging in never creates accounts")
        override fun findAllByRole(role: Role, active: Boolean?) = error("Logging in never lists accounts")
        override fun saveActive(account: UserAccount) = error("Logging in never changes the active flag")
        override fun saveContact(account: UserAccount) = error("Logging in never changes the contact data")
    }

    /** The password is "right"; the hash is ignored. Records every check, to prove timing work happens. */
    private class FakePasswords : PasswordHasher {
        val checkedHashes = mutableListOf<String?>()
        override fun hash(rawPassword: String) = error("Logging in never hashes a new password")
        override fun matches(rawPassword: String, hash: String?): Boolean {
            checkedHashes += hash
            return hash != null && rawPassword == "right"
        }
    }

    private val tokens = SessionTokens { userId, role, cutOff, at ->
        SessionToken("token-$userId-$role-$cutOff", at.plus(Duration.ofDays(30)))
    }

    private fun account(active: Boolean = true) = UserAccount(
        id = 7, name = "Ana", email = "ana@test.local", phone = "1100000000", role = Role.STUDENT, passwordHash = "hash",
        active = active, failedLoginAttempts = 0, lockedUntil = null, sessionsValidFrom = now.minusSeconds(60),
    )

    @Test
    fun `RF-01 the right password returns a token for the account`() {
        val accounts = FakeAccounts(account())
        val result = LogIn(accounts, FakePasswords(), tokens, clock).logIn("ana@test.local", "right")

        // The token carries the account's current session cut-off (DI-28).
        assertThat(result.token)
            .isEqualTo(SessionToken("token-7-STUDENT-${now.minusSeconds(60)}", now.plus(Duration.ofDays(30))))
        assertThat(result.account.name).isEqualTo("Ana")
        assertThat(accounts.saved).containsExactly(0 to null)
    }

    @Test
    fun `the email is trimmed and compared ignoring case`() {
        val result = LogIn(FakeAccounts(account()), FakePasswords(), tokens, clock).logIn("  ANA@test.local ", "right")

        assertThat(result.account.id).isEqualTo(7)
    }

    @Test
    fun `DI-31 a wrong password is saved as a failed attempt even though the login fails`() {
        val accounts = FakeAccounts(account())

        assertThatThrownBy { LogIn(accounts, FakePasswords(), tokens, clock).logIn("ana@test.local", "wrong") }
            .isInstanceOf(InvalidCredentials::class.java)
        assertThat(accounts.saved).containsExactly(1 to null)
    }

    @Test
    fun `DI-30 an unknown email gets the same rejection and the password is still checked`() {
        val passwords = FakePasswords()

        assertThatThrownBy { LogIn(FakeAccounts(), passwords, tokens, clock).logIn("nobody@test.local", "right") }
            .isInstanceOf(InvalidCredentials::class.java)
        assertThat(passwords.checkedHashes).containsExactly(null)
    }

    @Test
    fun `RF-45 an inactive account is rejected`() {
        assertThatThrownBy { LogIn(FakeAccounts(account(active = false)), FakePasswords(), tokens, clock).logIn("ana@test.local", "right") }
            .isInstanceOf(InvalidCredentials::class.java)
    }
}
