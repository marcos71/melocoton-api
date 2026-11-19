package ar.melocoton.auth.domain

import ar.melocoton.shared.domain.Role
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class UserAccountTest {

    private val now = Instant.parse("2026-11-15T13:00:00Z")

    private fun account(
        active: Boolean = true,
        failedLoginAttempts: Int = 0,
        lockedUntil: Instant? = null,
        sessionsValidFrom: Instant = now.minus(Duration.ofDays(1)),
    ) = UserAccount(
        id = 1, name = "Ana", email = "ana@test.local", role = Role.STUDENT, passwordHash = "hash",
        active = active, failedLoginAttempts = failedLoginAttempts, lockedUntil = lockedUntil,
        sessionsValidFrom = sessionsValidFrom,
    )

    private fun UserAccount.failLogin(at: Instant = now) =
        assertThatThrownBy { logIn(passwordMatches = false, now = at) }.isInstanceOf(InvalidCredentials::class.java)

    @Test
    fun `a correct password on an active account logs in and resets the failed attempts`() {
        val account = account(failedLoginAttempts = 3)

        account.logIn(passwordMatches = true, now = now)

        assertThat(account.failedLoginAttempts).isZero()
        assertThat(account.lockedUntil).isNull()
    }

    @Test
    fun `DI-31 a wrong password counts as a failed attempt`() {
        val account = account()

        account.failLogin()

        assertThat(account.failedLoginAttempts).isEqualTo(1)
        assertThat(account.isLockedAt(now)).isFalse()
    }

    @Test
    fun `DI-31 the fifth wrong password in a row locks the account for 15 minutes`() {
        val account = account()

        repeat(4) { account.failLogin() }
        assertThat(account.isLockedAt(now)).isFalse()
        account.failLogin()

        assertThat(account.lockedUntil).isEqualTo(now.plus(Duration.ofMinutes(15)))
        assertThat(account.isLockedAt(now.plus(Duration.ofMinutes(15)).minusSeconds(1))).isTrue()
        assertThat(account.isLockedAt(now.plus(Duration.ofMinutes(15)))).isFalse()
    }

    @Test
    fun `DI-31 while locked even the correct password is rejected, and the attempt does not count`() {
        val lockedUntil = now.plus(Duration.ofMinutes(10))
        val account = account(failedLoginAttempts = 5, lockedUntil = lockedUntil)

        assertThatThrownBy { account.logIn(passwordMatches = true, now = now) }
            .isInstanceOf(InvalidCredentials::class.java)
        assertThat(account.failedLoginAttempts).isEqualTo(5)
        assertThat(account.lockedUntil).isEqualTo(lockedUntil)
    }

    @Test
    fun `DI-31 after the lockout ends the correct password logs in`() {
        val account = account(failedLoginAttempts = 5, lockedUntil = now.minusSeconds(1))

        account.logIn(passwordMatches = true, now = now)

        assertThat(account.failedLoginAttempts).isZero()
        assertThat(account.lockedUntil).isNull()
    }

    @Test
    fun `DI-31 after the lockout ends a wrong password starts a fresh count`() {
        val account = account(failedLoginAttempts = 5, lockedUntil = now.minusSeconds(1))

        account.failLogin()

        assertThat(account.failedLoginAttempts).isEqualTo(1)
        assertThat(account.isLockedAt(now)).isFalse()
    }

    @Test
    fun `RF-45 DI-30 an inactive account with the correct password gets the same rejection`() {
        val account = account(active = false)

        assertThatThrownBy { account.logIn(passwordMatches = true, now = now) }
            .isInstanceOf(InvalidCredentials::class.java)
            .hasMessage("Email o contraseña incorrectos. Si dejaste el taller, consultá con la docente.")
        assertThat(account.failedLoginAttempts).isZero()
    }

    @Test
    fun `DI-28 a token issued under the current cut-off is accepted`() {
        assertThat(account(sessionsValidFrom = now).acceptsSession(now)).isTrue()
    }

    @Test
    fun `DI-28 a token issued under an earlier cut-off is rejected`() {
        assertThat(account(sessionsValidFrom = now).acceptsSession(now.minus(Duration.ofDays(1)))).isFalse()
    }

    @Test
    fun `DI-28 a token from a moment before a password change is rejected, even in the same second`() {
        val before = Instant.parse("2026-11-15T13:00:00.200Z")
        val afterChange = Instant.parse("2026-11-15T13:00:00.800Z")

        assertThat(account(sessionsValidFrom = afterChange).acceptsSession(before)).isFalse()
    }

    @Test
    fun `RF-45 an inactive account accepts no session`() {
        assertThat(account(active = false, sessionsValidFrom = now).acceptsSession(now)).isFalse()
    }
}
