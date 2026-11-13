package ar.melocoton

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Checks the connection settings that the rest of the API relies on:
 * test database, application role (DI-05), timeouts (plan, section 4) and UTC (DI-14).
 */
@SpringBootTest
class DatabaseConnectionTest(@Autowired private val jdbc: JdbcTemplate) {

    private fun query(sql: String): String = jdbc.queryForObject(sql, String::class.java)!!

    @Test
    fun `connects to the test database`() {
        assertThat(query("SELECT current_database()")).isEqualTo("melocoton_test")
    }

    @Test
    fun `queries run as the application role, not the owner`() {
        assertThat(query("SELECT current_user")).isEqualTo("melocoton_app")
    }

    @Test
    fun `every connection has the lock and statement timeouts`() {
        assertThat(query("SHOW lock_timeout")).isEqualTo("3s")
        assertThat(query("SHOW statement_timeout")).isEqualTo("5s")
    }

    @Test
    fun `transactions use read committed`() {
        assertThat(query("SHOW transaction_isolation")).isEqualTo("read committed")
    }

    @Test
    fun `the database session uses UTC`() {
        assertThat(query("SHOW timezone")).isEqualTo("UTC")
    }
}
