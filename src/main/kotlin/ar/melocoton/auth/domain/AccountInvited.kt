package ar.melocoton.auth.domain

import ar.melocoton.shared.domain.DomainEvent
import java.time.Instant

/**
 * An account was created without a password and must receive an invitation link (D-11, DI-29, DI-34, DI-46).
 *
 * Published by `CreateStudent` (students) and `CreateAssistantTeacher` (auth), inside the transaction that
 * created the account. Handled by `IssueInvitation` through `InvitationListener` (auth), in that same
 * transaction: the link is saved with the account and the email goes out after the commit (DI-54).
 */
data class AccountInvited(val accountId: Long, val occurredAt: Instant) : DomainEvent
