package ar.melocoton.auth.infrastructure.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder

class Argon2PasswordHasherTest {

    private val hasher = Argon2PasswordHasher()
    private val hash = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("una-contraseña-larga")!!

    @Test
    fun `RNF-03 the stored hash is Argon2id`() {
        assertThat(hash).startsWith("\$argon2id\$")
    }

    @Test
    fun `the right password matches`() {
        assertThat(hasher.matches("una-contraseña-larga", hash)).isTrue()
    }

    @Test
    fun `a wrong password does not match`() {
        assertThat(hasher.matches("otra", hash)).isFalse()
    }

    @Test
    fun `DI-30 without a hash nothing matches`() {
        assertThat(hasher.matches("una-contraseña-larga", null)).isFalse()
    }
}
