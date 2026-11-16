package ar.melocoton.auth.application

import ar.melocoton.auth.application.port.AccountLinkRepository
import ar.melocoton.auth.application.port.AccountLinkTokens
import ar.melocoton.auth.application.port.PasswordHasher
import ar.melocoton.auth.application.port.UserAccountRepository
import ar.melocoton.auth.domain.LinkExpired
import ar.melocoton.auth.domain.PasswordPolicy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** CU-01 2c and 2e: defines the password from an invitation or reset link (D-11; DI-29, DI-33). */
@Service
class SetPassword(
    private val accounts: UserAccountRepository,
    private val links: AccountLinkRepository,
    private val tokens: AccountLinkTokens,
    private val passwords: PasswordHasher,
    private val clock: Clock,
) {
    @Transactional
    fun set(token: String, newPassword: String) {
        // Checked first: a password out of range should not use up the link.
        PasswordPolicy.check(newPassword)
        val now = clock.instant()
        val link = links.lockByTokenHash(tokens.hash(token)) ?: throw LinkExpired()
        link.checkUsableAt(now)
        val account = checkNotNull(accounts.lockById(link.userId)) { "Link ${link.id} has no user" }

        account.changePassword(passwords.hash(newPassword), now)
        accounts.savePassword(account)
        links.useAllOpen(account.id, now)
    }
}
