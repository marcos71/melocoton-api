package ar.melocoton.auth.application

import ar.melocoton.auth.application.port.AccountLinkRepository
import ar.melocoton.auth.application.port.AccountLinkSender
import ar.melocoton.auth.application.port.AccountLinkTokens
import ar.melocoton.auth.application.port.UserAccountRepository
import ar.melocoton.auth.domain.AccountLink
import ar.melocoton.auth.domain.AccountLinkPurpose
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

/**
 * CU-01 2c: sends a link to define a new password; it also resends an invitation (02, F1).
 * The caller always gets the same answer, so it never learns whether the email exists (DI-33).
 */
@Service
class RequestPasswordReset(
    private val accounts: UserAccountRepository,
    private val links: AccountLinkRepository,
    private val tokens: AccountLinkTokens,
    private val sender: AccountLinkSender,
    private val clock: Clock,
) {
    @Transactional
    fun request(email: String) {
        val now = clock.instant()
        // The row lock makes parallel requests count one after the other (DI-31).
        val account = accounts.lockByEmail(email.trim()) ?: return
        val recent = links.countCreatedSince(account.id, AccountLinkPurpose.PASSWORD_RESET, now.minus(Duration.ofHours(1)))
        if (!account.canRequestPasswordReset(recent)) return

        val token = tokens.generate()
        links.save(AccountLink.issue(account.id, AccountLinkPurpose.PASSWORD_RESET, tokens.hash(token), now))
        sender.send(account, AccountLinkPurpose.PASSWORD_RESET, token)
    }
}
