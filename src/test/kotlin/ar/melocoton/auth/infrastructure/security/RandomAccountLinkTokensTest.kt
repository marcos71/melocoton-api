package ar.melocoton.auth.infrastructure.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RandomAccountLinkTokensTest {

    private val tokens = RandomAccountLinkTokens()

    @Test
    fun `a token carries 32 random bytes in URL-safe characters`() {
        val token = tokens.generate()

        // 32 bytes in base64 without padding are 43 characters.
        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+")
    }

    @Test
    fun `every token is different`() {
        assertThat((1..100).map { tokens.generate() }.toSet()).hasSize(100)
    }

    @Test
    fun `DI-29 the stored hash is the SHA-256 of the token, never the token`() {
        val token = tokens.generate()
        val hash = tokens.hash(token)

        assertThat(hash).hasSize(64).matches("[0-9a-f]+").doesNotContain(token)
        assertThat(tokens.hash(token)).isEqualTo(hash)
        assertThat(tokens.hash("abc"))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }
}
