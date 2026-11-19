package ar.melocoton.auth.infrastructure.security

import ar.melocoton.auth.application.port.SessionToken
import ar.melocoton.auth.application.port.SessionTokens
import ar.melocoton.shared.domain.Role
import ar.melocoton.shared.infrastructure.security.SESSIONS_VALID_FROM_CLAIM
import ar.melocoton.shared.infrastructure.security.SessionProperties
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Signs session tokens with the API's own key (HS256, DI-28). The subject is the user id;
 * the role travels as a claim only for the PWA, the API always reads it from the database.
 */
@Component
class JwtSessionTokens(
    private val encoder: JwtEncoder,
    private val properties: SessionProperties,
) : SessionTokens {

    override fun issue(userId: Long, role: Role, sessionsValidFrom: Instant, now: Instant): SessionToken {
        val expiresAt = now.plus(properties.inactivity)
        val claims = JwtClaimsSet.builder()
            .subject(userId.toString())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .claim("role", role.name)
            .claim(SESSIONS_VALID_FROM_CLAIM, sessionsValidFrom.toString())
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        val token = encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
        return SessionToken(token, expiresAt)
    }
}
