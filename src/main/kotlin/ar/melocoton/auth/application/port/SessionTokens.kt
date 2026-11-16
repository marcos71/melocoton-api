package ar.melocoton.auth.application.port

import ar.melocoton.shared.domain.Role
import java.time.Instant

/** A signed session token and when it expires if it is not renewed (DI-28). */
data class SessionToken(val value: String, val expiresAt: Instant)

fun interface SessionTokens {
    /** [sessionsValidFrom] is the account's current session cut-off; the token carries it (DI-28). */
    fun issue(userId: Long, role: Role, sessionsValidFrom: Instant, now: Instant): SessionToken
}
