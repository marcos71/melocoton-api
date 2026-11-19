package ar.melocoton.auth.domain

import ar.melocoton.shared.domain.BusinessErrorKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PasswordPolicyTest {

    @Test
    fun `DI-29 10 characters of any kind are enough`() {
        PasswordPolicy.check("aaaaaaaaaa")
    }

    @Test
    fun `DI-29 9 characters are too short`() {
        assertThatThrownBy { PasswordPolicy.check("aaaaaaaaa") }
            .isInstanceOf(WeakPassword::class.java)
            .hasMessage("La contraseña tiene que tener entre 10 y 128 caracteres.")
    }

    @Test
    fun `DI-33 128 characters are accepted and 129 are not`() {
        PasswordPolicy.check("a".repeat(128))

        assertThatThrownBy { PasswordPolicy.check("a".repeat(129)) }.isInstanceOf(WeakPassword::class.java)
    }

    @Test
    fun `characters are counted, not UTF-16 units`() {
        // Each emoji is one character but two UTF-16 units: 5 of them must still be too short.
        assertThatThrownBy { PasswordPolicy.check("🏺".repeat(5)) }.isInstanceOf(WeakPassword::class.java)
        PasswordPolicy.check("🏺".repeat(10))
    }

    @Test
    fun `a weak password is a validation error`() {
        assertThat(WeakPassword().code).isEqualTo("validation_error")
        assertThat(WeakPassword().kind).isEqualTo(BusinessErrorKind.INVALID)
    }
}
