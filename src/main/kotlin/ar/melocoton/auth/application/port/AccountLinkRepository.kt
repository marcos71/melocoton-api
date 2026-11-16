package ar.melocoton.auth.application.port

import ar.melocoton.auth.domain.AccountLink
import ar.melocoton.auth.domain.AccountLinkPurpose
import java.time.Instant

interface AccountLinkRepository {
    fun save(link: AccountLink)

    /** Loads a link by the hash of its token and locks its row, so two uses of it run one after the other. */
    fun lockByTokenHash(tokenHash: String): AccountLink?

    /** Links of [purpose] created for the user from [since] on (DI-31). */
    fun countCreatedSince(userId: Long, purpose: AccountLinkPurpose, since: Instant): Int

    /** Marks every unused link of the user as used at [now], so none of them works again (DI-33). */
    fun useAllOpen(userId: Long, now: Instant)
}
