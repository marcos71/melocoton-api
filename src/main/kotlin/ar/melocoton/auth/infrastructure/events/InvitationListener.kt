package ar.melocoton.auth.infrastructure.events

import ar.melocoton.auth.application.IssueInvitation
import ar.melocoton.auth.domain.AccountInvited
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Inbound adapter for [AccountInvited] (DI-54). A plain `@EventListener`, not a transactional one: it runs
 * synchronously in the publisher's thread and transaction, so a failure rolls the account back.
 */
@Component
class InvitationListener(private val issueInvitation: IssueInvitation) {

    @EventListener
    fun on(event: AccountInvited) = issueInvitation.issue(event.accountId, event.occurredAt)
}
