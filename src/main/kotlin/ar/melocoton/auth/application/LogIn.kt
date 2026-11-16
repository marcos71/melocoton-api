package ar.melocoton.auth.application

import ar.melocoton.auth.application.port.PasswordHasher
import ar.melocoton.auth.application.port.SessionToken
import ar.melocoton.auth.application.port.SessionTokens
import ar.melocoton.auth.application.port.UserAccountRepository
import ar.melocoton.auth.domain.InvalidCredentials
import ar.melocoton.auth.domain.UserAccount
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

data class LoggedIn(val token: SessionToken, val account: UserAccount)

/** CU-01: log in with email and password (RF-01, RF-45; DI-28, DI-30, DI-31). */
@Service
class LogIn(
    private val accounts: UserAccountRepository,
    private val passwords: PasswordHasher,
    private val tokens: SessionTokens,
    private val clock: Clock,
) {
    // A rejected attempt must still save the failed count and the lockout,
    // so InvalidCredentials does not roll the transaction back.
    @Transactional(noRollbackFor = [InvalidCredentials::class])
    fun logIn(email: String, password: String): LoggedIn {
        val now = clock.instant()
        val account = accounts.lockByEmail(email.trim())
        // Always hashed, even for an unknown email, so timing reveals nothing (DI-30).
        val matches = passwords.matches(password, account?.passwordHash)
        if (account == null) throw InvalidCredentials()
        try {
            account.logIn(matches, now)
        } finally {
            accounts.saveLoginState(account)
        }
        return LoggedIn(tokens.issue(account.id, account.role, account.sessionsValidFrom, now), account)
    }
}
