package ar.melocoton.auth.application

import ar.melocoton.auth.application.port.AccountLinkRepository
import ar.melocoton.auth.application.port.AccountLinkSender
import ar.melocoton.auth.application.port.AccountLinkTokens
import ar.melocoton.auth.application.port.UserAccountRepository
import ar.melocoton.auth.domain.AccountLink
import ar.melocoton.auth.domain.AccountLinkPurpose
import ar.melocoton.auth.domain.NewAccount
import ar.melocoton.auth.domain.UserAccount
import ar.melocoton.shared.application.port.AfterCommit
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** CU-02 D1, RF-53: the lead teacher invites an assistant teacher, who defines a password from the emailed link (D-11). */
@Service
class CreateAssistantTeacher(
    private val accounts: UserAccountRepository,
    private val links: AccountLinkRepository,
    private val tokens: AccountLinkTokens,
    private val sender: AccountLinkSender,
    private val afterCommit: AfterCommit,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun create(name: String, email: String, phone: String): UserAccount {
        val now = clock.instant()
        val new = NewAccount.assistantTeacher(name, email, phone, now)
        // A taken email fails here with EmailAlreadyUsed: the unique index is the only check (DI-34).
        val account = accounts.create(new)

        val token = tokens.generate()
        links.save(AccountLink.issue(account.id, AccountLinkPurpose.INVITATION, tokens.hash(token), now))
        // Sent only after the commit: an email cannot be recalled if the transaction rolls back.
        afterCommit.run {
            try {
                sender.send(account, AccountLinkPurpose.INVITATION, token)
            } catch (e: Exception) {
                // The account already exists, so the request must not fail. There is no way yet to
                // resend the invitation from the lead teacher's side; the log is the only trace.
                log.error("Could not send the invitation to assistant teacher {}", account.id, e)
            }
        }
        return account
    }
}
