package ar.melocoton.auth.infrastructure.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class Argon2PasswordHasherTest {

    private val hasher = Argon2PasswordHasher()
    // Hashed by the hasher itself: what SetPassword stores.
    private val hash = hasher.hash("una-contraseña-larga")

    @Test
    fun `RNF-03 a new password is stored as Argon2id`() {
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
