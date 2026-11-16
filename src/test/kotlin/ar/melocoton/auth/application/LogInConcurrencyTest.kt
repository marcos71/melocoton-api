package ar.melocoton.auth.application

import ar.melocoton.auth.domain.InvalidCredentials
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Wrong passwords sent at the same time are all counted (DI-31). Not wrapped in a test
 * transaction: each attempt commits on its own, as in production. Rows are not deleted
 * (the API role cannot); the test database is recreated on every run (DI-19).
 */
@SpringBootTest
class LogInConcurrencyTest(
    @Autowired private val logIn: LogIn,
    @Autowired private val jdbc: JdbcTemplate,
) {

    @Test
    fun `DI-31 ten wrong passwords in parallel still lock the account`() {
        val email = "${UUID.randomUUID()}@test.local"
        val hash = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("una-contraseña-larga")
        val id = jdbc.queryForObject(
            "INSERT INTO app_user (name, email, password_hash, role, phone) VALUES ('Ana', ?, ?, 'STUDENT', '1100000000') RETURNING id",
            Long::class.java, email, hash,
        )!!

        val attempts = 10
        val start = CountDownLatch(1)
        val rejected = java.util.concurrent.atomic.AtomicInteger()
        val pool = Executors.newFixedThreadPool(attempts)
        repeat(attempts) {
            pool.submit {
                start.await()
                try {
                    logIn.logIn(email, "otra-contraseña")
                } catch (_: InvalidCredentials) {
                    rejected.incrementAndGet()
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue()

        val row = jdbc.queryForMap("SELECT failed_login_attempts, locked_until FROM app_user WHERE id = ?", id)
        assertThat(rejected.get()).isEqualTo(attempts)
        // Without the row lock, parallel attempts overwrite each other's count and it stays below 5.
        assertThat(row["failed_login_attempts"]).isEqualTo(5)
        assertThat((row["locked_until"] as java.sql.Timestamp).toInstant()).isAfter(Instant.now())
    }
}
