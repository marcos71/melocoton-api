package ar.melocoton.shared.infrastructure.security

import ar.melocoton.shared.application.port.SessionUserLookup
import ar.melocoton.shared.domain.SessionUser
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import java.time.Instant

/**
 * Claim with the account's session cut-off the token was issued under (DI-28). It is compared
 * for equality, so a password change closes even a token issued a moment before it.
 */
const val SESSIONS_VALID_FROM_CLAIM = "sessionsValidFrom"

/**
 * What Spring Security keeps for the current request once the token is accepted. Controllers
 * receive [SessionUser] with `@AuthenticationPrincipal`; the role becomes the authority
 * `ROLE_<role>`, which is what `hasRole(...)` checks.
 */
class SessionAuthentication(private val user: SessionUser, private val jwt: Jwt) :
    AbstractAuthenticationToken(listOf(SimpleGrantedAuthority("ROLE_${user.role.name}"))) {

    init {
        isAuthenticated = true
    }

    override fun getPrincipal(): SessionUser = user

    override fun getCredentials(): Jwt = jwt
}

/**
 * Turns a token whose signature and expiry are valid into the request's user, after checking
 * in the database that the session still holds (DI-28). Throwing here makes Spring answer 401.
 */
class SessionAuthenticationConverter(private val sessions: SessionUserLookup) :
    Converter<Jwt, AbstractAuthenticationToken> {

    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        val userId = jwt.subject?.toLongOrNull() ?: throw InvalidBearerTokenException("Token without a user")
        val sessionsValidFrom = jwt.getClaimAsString(SESSIONS_VALID_FROM_CLAIM)
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: throw InvalidBearerTokenException("Token without a session cut-off")
        val user = sessions.validSession(userId, sessionsValidFrom)
            ?: throw InvalidBearerTokenException("The session is no longer valid")
        return SessionAuthentication(user, jwt)
    }
}
