package ar.melocoton.auth.domain

import ar.melocoton.shared.domain.BusinessErrorKind
import ar.melocoton.shared.domain.BusinessRuleViolation
import java.time.Duration
import java.time.Instant

/** Why a link was sent; each purpose lasts a different time (DI-29). */
enum class AccountLinkPurpose(val validity: Duration) {
    /** First access: the person defines a password for a new account (D-11). */
    INVITATION(Duration.ofDays(7)),

    /** Forgotten password (CU-01 2c); also resends an invitation. */
    PASSWORD_RESET(Duration.ofHours(1)),
}

/**
 * A one-time link to define a password (D-11, DI-29). Only the hash of its token is kept,
 * so a copy of the database cannot be used to take over an account.
 */
class AccountLink(
    val id: Long?,
    val userId: Long,
    val purpose: AccountLinkPurpose,
    val tokenHash: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val usedAt: Instant?,
) {
    /** Fails with [LinkExpired] if the link was already used or has expired (CU-01 2f). */
    fun checkUsableAt(now: Instant) {
        if (usedAt != null || !now.isBefore(expiresAt)) throw LinkExpired()
    }

    companion object {
        /** Password reset links one email can ask for per hour (DI-31). */
        const val MAX_RESETS_PER_HOUR = 3

        fun issue(userId: Long, purpose: AccountLinkPurpose, tokenHash: String, now: Instant) = AccountLink(
            id = null, userId = userId, purpose = purpose, tokenHash = tokenHash,
            createdAt = now, expiresAt = now.plus(purpose.validity), usedAt = null,
        )
    }
}

/** The link expired, was already used, or its account was deactivated (CU-01 2f, DI-33). */
class LinkExpired : BusinessRuleViolation(
    code = "link_expired",
    message = "El enlace venció o ya no sirve. Pedí uno nuevo con «Olvidé mi contraseña».",
    kind = BusinessErrorKind.INVALID,
)
