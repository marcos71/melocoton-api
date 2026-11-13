package ar.melocoton.shared.infrastructure.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.SQLException

class DatabaseTimeoutDetectionTest {

    private fun wrapped(sqlState: String) = RuntimeException("outer", SQLException("db error", sqlState))

    @Test
    fun `a lock timeout anywhere in the cause chain is detected`() {
        assertThat(wrapped("55P03").isDatabaseTimeout()).isTrue()
    }

    @Test
    fun `a statement timeout anywhere in the cause chain is detected`() {
        assertThat(wrapped("57014").isDatabaseTimeout()).isTrue()
    }

    @Test
    fun `other SQL errors are not timeouts`() {
        assertThat(wrapped("23505").isDatabaseTimeout()).isFalse() // unique violation
    }

    @Test
    fun `errors without SQL causes are not timeouts`() {
        assertThat(IllegalStateException("boom").isDatabaseTimeout()).isFalse()
    }
}
