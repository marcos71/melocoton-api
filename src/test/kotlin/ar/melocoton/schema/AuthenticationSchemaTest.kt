package ar.melocoton.schema

import ar.melocoton.schema.SqlState.CHECK_VIOLATION
import ar.melocoton.schema.SqlState.UNIQUE_VIOLATION
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional

/**
 * V2 adds lockout, session cut-off and one-time account links (DI-28, DI-29, DI-31).
 * Runs as the API role; each test is rolled back.
 */
@SpringBootTest
@Transactional
class AuthenticationSchemaTest(@Autowired private val jdbc: JdbcTemplate) {

    private val f = SchemaFixtures(jdbc)

    private fun link(userId: Long, purpose: String = "INVITATION", hash: String = "hash-$userId-$purpose") =
        jdbc.update(
            "INSERT INTO account_link (user_id, purpose, token_hash, expires_at) VALUES (?, ?, ?, now() + interval '1 hour')",
            userId, purpose, hash,
        )

    @Test
    fun `a new user starts with no failed attempts, not locked, and sessions valid from its creation`() {
        val user = f.user()
        val row = jdbc.queryForMap(
            "SELECT failed_login_attempts, locked_until, sessions_valid_from IS NOT NULL AS has_from FROM app_user WHERE id = ?",
            user,
        )
        assertThat(row).containsEntry("failed_login_attempts", 0).containsEntry("has_from", true)
        assertThat(row["locked_until"]).isNull()
    }

    @Test
    fun `DI-31 failed attempts cannot be negative`() {
        val user = f.user()
        assertFailsWithSqlState(CHECK_VIOLATION) {
            jdbc.update("UPDATE app_user SET failed_login_attempts = -1 WHERE id = ?", user)
        }
    }

    @Test
    fun `DI-29 an invitation and a password reset link are accepted`() {
        val user = f.user()
        link(user, "INVITATION")
        link(user, "PASSWORD_RESET")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_link WHERE user_id = ?", Long::class.java, user))
            .isEqualTo(2)
    }

    @Test
    fun `an unknown link purpose is rejected`() {
        val user = f.user()
        assertFailsWithSqlState(CHECK_VIOLATION) { link(user, "LOGIN") }
    }

    @Test
    fun `two links cannot share a token hash`() {
        val user = f.user()
        link(user, hash = "same")
        assertFailsWithSqlState(UNIQUE_VIOLATION) { link(user, "PASSWORD_RESET", hash = "same") }
    }

    @Test
    fun `a link expires after it was created`() {
        val user = f.user()
        assertFailsWithSqlState(CHECK_VIOLATION) {
            jdbc.update(
                "INSERT INTO account_link (user_id, purpose, token_hash, expires_at) VALUES (?, 'INVITATION', 'h', now() - interval '1 second')",
                user,
            )
        }
    }

    @Test
    fun `a link cannot be used before it was created`() {
        val user = f.user()
        link(user, hash = "h")
        assertFailsWithSqlState(CHECK_VIOLATION) {
            jdbc.update("UPDATE account_link SET used_at = created_at - interval '1 second' WHERE token_hash = 'h'")
        }
    }
}
