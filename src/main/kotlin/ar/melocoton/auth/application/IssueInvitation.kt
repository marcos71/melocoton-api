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
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Handles `AccountInvited` (D-11, DI-29, DI-34, DI-46, DI-54): issues the invitation link inside the
 * transaction that created the account and emails it only after the commit. It never opens a
 * transaction: it requires the publisher's, so an error here rolls the account back too.
 */
@Service
class IssueInvitation(
    private val accounts: UserAccountRepository,
    private val links: AccountLinkRepository,
    private val tokens: AccountLinkTokens,
    private val sender: AccountLinkSender,
    private val afterCommit: AfterCommit,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.MANDATORY)
    fun issue(accountId: Long, now: Instant) {
        val account = checkNotNull(accounts.findById(accountId)) { "Account $accountId was just created" }
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
