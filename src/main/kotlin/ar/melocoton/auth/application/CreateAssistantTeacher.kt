package ar.melocoton.auth.application

import ar.melocoton.auth.application.port.AccountLinkRepository
import ar.melocoton.auth.application.port.AccountLinkSender
import ar.melocoton.auth.application.port.AccountLinkTokens
import ar.melocoton.auth.application.port.UserAccountRepository
import ar.melocoton.auth.domain.NewAccount
import ar.melocoton.auth.domain.UserAccount
import ar.melocoton.shared.application.port.AfterCommit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** CU-02 D1, RF-53: the lead teacher invites an assistant teacher, who defines a password from the emailed link (D-11). */
@Service
class CreateAssistantTeacher(
    private val accounts: UserAccountRepository,
    links: AccountLinkRepository,
    tokens: AccountLinkTokens,
    sender: AccountLinkSender,
    afterCommit: AfterCommit,
    private val clock: Clock,
) {
    private val invitations = AccountInvitations(links, tokens, sender, afterCommit)

    @Transactional
    fun create(name: String, email: String, phone: String): UserAccount {
        val now = clock.instant()
        val new = NewAccount.assistantTeacher(name, email, phone, now)
        // A taken email fails here with EmailAlreadyUsed: the unique index is the only check (DI-34).
        val account = accounts.create(new)

        invitations.issue(account, now)
        return account
    }
}
