package ar.melocoton.shared.application.port

import ar.melocoton.shared.domain.SessionUser
import java.time.Instant

/**
 * Checked on every authenticated request: the token's user must still be active and the
 * token must carry the account's current session cut-off, which changes with the password (DI-28). Implemented by the `auth` module,
 * so `shared` does not depend on it.
 */
fun interface SessionUserLookup {
    /** The session's user, or null when the session is no longer valid. */
    fun validSession(userId: Long, sessionsValidFrom: Instant): SessionUser?
}
