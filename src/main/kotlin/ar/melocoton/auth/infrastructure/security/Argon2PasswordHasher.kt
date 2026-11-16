package ar.melocoton.auth.infrastructure.security

import ar.melocoton.auth.application.port.PasswordHasher
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Component

/** Argon2id with Spring Security's recommended parameters (RNF-03, DI-07). */
@Component
class Argon2PasswordHasher : PasswordHasher {

    private val encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()

    // Compared against when there is no real hash, so every attempt costs the same (DI-30).
    // Built at startup, not on first use: otherwise the first unknown email would be slower.
    private val dummyHash: String = encoder.encode("not-a-real-password-for-timing")!!

    override fun matches(rawPassword: String, hash: String?): Boolean {
        if (hash == null) {
            encoder.matches(rawPassword, dummyHash)
            return false
        }
        return encoder.matches(rawPassword, hash)
    }
}
