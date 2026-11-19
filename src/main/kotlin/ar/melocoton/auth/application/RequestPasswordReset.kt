package ar.melocoton.auth.application

import ar.melocoton.auth.application.port.AccountLinkRepository
import ar.melocoton.auth.application.port.AccountLinkSender
import ar.melocoton.auth.application.port.AccountLinkTokens
import ar.melocoton.auth.application.port.UserAccountRepository
import ar.melocoton.auth.domain.AccountLink
import ar.melocoton.auth.domain.AccountLinkPurpose
import ar.melocoton.shared.application.port.AfterCommit
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

/**
 * CU-01 2c: sends a link to define a new password; it also resends an invitation (02, F1).
 * The caller always gets the same answer, so it never learns whether the email exists (DI-33).
 * The email goes out after the commit, and a send failure is logged, not propagated.
 */
@Service
class RequestPasswordReset(
    private val accounts: UserAccountRepository,
    private val links: AccountLinkRepository,
    private val tokens: AccountLinkTokens,
    private val sender: AccountLinkSender,
    private val afterCommit: AfterCommit,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun request(email: String) {
        val now = clock.instant()
        // The row lock makes parallel requests count one after the other (DI-31).
        val account = accounts.lockByEmail(email.trim()) ?: return
        val recent = links.countCreatedSince(account.id, AccountLinkPurpose.PASSWORD_RESET, now.minus(Duration.ofHours(1)))
        if (!account.canRequestPasswordReset(recent)) return

        val token = tokens.generate()
        links.save(AccountLink.issue(account.id, AccountLinkPurpose.PASSWORD_RESET, tokens.hash(token), now))
        // Sent only after the commit: an email cannot be recalled if the transaction rolls back.
        afterCommit.run {
            try {
                sender.send(account, AccountLinkPurpose.PASSWORD_RESET, token)
            } catch (e: Exception) {
                // The answer must stay the same 202 whether or not the email goes out (DI-33).
                log.error("Could not send the password reset email to account {}", account.id, e)
            }
        }
    }
}
