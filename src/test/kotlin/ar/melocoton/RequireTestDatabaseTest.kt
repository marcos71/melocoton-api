package ar.melocoton

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RequireTestDatabaseTest {

    @Test
    fun `allows a database whose name ends with _test`() {
        assertThatCode { requireTestDatabase("melocoton_test") }.doesNotThrowAnyException()
    }

    @Test
    fun `refuses the development database`() {
        assertThatThrownBy { requireTestDatabase("melocoton_dev") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("melocoton_dev")
    }

    @Test
    fun `refuses the old melocoton database`() {
        assertThatThrownBy { requireTestDatabase("melocoton") }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `refuses an unknown database name`() {
        assertThatThrownBy { requireTestDatabase(null) }
            .isInstanceOf(IllegalStateException::class.java)
    }
}
