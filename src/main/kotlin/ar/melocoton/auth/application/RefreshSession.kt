package ar.melocoton.auth.application

import ar.melocoton.auth.application.port.SessionToken
import ar.melocoton.auth.application.port.SessionTokens
import ar.melocoton.shared.domain.SessionUser
import org.springframework.stereotype.Service
import java.time.Clock

/**
 * Issues a new token for a valid session, so it expires 30 days after the last use
 * instead of after the login (DI-28). The security filter already checked the session.
 */
@Service
class RefreshSession(private val tokens: SessionTokens, private val clock: Clock) {

    // Keeps the cut-off of the token being renewed, not the account's current one: if the password
    // changed meanwhile, the new token is just as invalid as the old one.
    fun refresh(user: SessionUser): SessionToken =
        tokens.issue(user.userId, user.role, user.sessionsValidFrom, clock.instant())
}
