package ar.melocoton.shared.infrastructure.security

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.util.Base64

/**
 * Settings of the session token (DI-28), from `melocoton.session.*` in application.yml.
 *
 * Not a data class on purpose: its generated toString() would print the secret,
 * and anyone with the secret can forge a session for any user.
 *
 * @param secret base64 key that signs the tokens (HS256): at least 32 bytes, from the JWT_SECRET
 *   environment variable. Changing it closes every open session.
 * @param inactivity how long a token lasts without being refreshed.
 */
@ConfigurationProperties("melocoton.session")
class SessionProperties(secret: String, val inactivity: Duration) {

    /** The decoded key; fails at startup if it is too short to be safe for HS256. */
    val key: ByteArray = Base64.getDecoder().decode(secret).also {
        require(it.size >= MIN_KEY_BYTES) {
            "JWT_SECRET must be a base64 key of at least $MIN_KEY_BYTES bytes (it has ${it.size})"
        }
    }

    override fun toString() = "SessionProperties(secret=<hidden>, inactivity=$inactivity)"

    companion object {
        const val MIN_KEY_BYTES = 32
    }
}
