package ar.melocoton.auth.infrastructure.security

import ar.melocoton.auth.application.port.AccountLinkTokens
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

/**
 * 32 random bytes per token (256 bits), stored as SHA-256 (DI-29). A slow hash like Argon2 is not
 * needed here: it protects short human passwords from guessing, and these tokens cannot be guessed.
 */
@Component
class RandomAccountLinkTokens : AccountLinkTokens {

    private val random = SecureRandom()

    override fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        // URL-safe alphabet without padding, so it fits in a link as is.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    override fun hash(token: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

    companion object {
        const val TOKEN_BYTES = 32
    }
}
