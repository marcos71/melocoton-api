package ar.melocoton.auth.application

import ar.melocoton.auth.application.port.AccountLinkRepository
import ar.melocoton.auth.application.port.AccountLinkSender
import ar.melocoton.auth.application.port.AccountLinkTokens
import ar.melocoton.auth.domain.AccountLink
import ar.melocoton.auth.domain.AccountLinkPurpose
import ar.melocoton.auth.domain.UserAccount
import ar.melocoton.shared.application.port.AfterCommit
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * The invitation step shared by every flow that creates an account without a password (D-11, DI-29, DI-34, DI-46):
 * issue the link inside the current transaction and email it only after the commit. Not a bean and not a
 * use case: each use case builds it from the same ports (DI-21 does not let a use case inject another one).
 */
class AccountInvitations(
    private val links: AccountLinkRepository,
    private val tokens: AccountLinkTokens,
    private val sender: AccountLinkSender,
    private val afterCommit: AfterCommit,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Must run inside the transaction that created [account]. */
    fun issue(account: UserAccount, now: Instant) {
        val token = tokens.generate()
        links.save(AccountLink.issue(account.id, AccountLinkPurpose.INVITATION, tokens.hash(token), now))
        // Sent only after the commit: an email cannot be recalled if the transaction rolls back.
        afterCommit.run {
            try {
                sender.send(account, AccountLinkPurpose.INVITATION, token)
            } catch (e: Exception) {
                // The account already exists, so the request must not fail. There is no way yet to
                // resend the invitation from the lead teacher's side; the log is the only trace.
                log.error("Could not send the invitation to account {}", account.id, e)
            }
        }
    }
}
