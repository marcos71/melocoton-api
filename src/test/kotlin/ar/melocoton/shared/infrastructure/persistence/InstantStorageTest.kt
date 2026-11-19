package ar.melocoton.shared.infrastructure.persistence

import ar.melocoton.auth.infrastructure.persistence.UserAccountJpaRepository
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.TimeZone
import java.util.UUID

/**
 * Instants are stored exactly, in UTC, whatever zone the JVM runs in (DI-14). Hibernate binds an
 * Instant as TIMESTAMP_UTC, so no `hibernate.jdbc.time_zone` setting is needed (and that setting
 * would shift times of day such as a slot start). The JVM zone is set to Buenos Aires, where the
 * workshop is, which is not UTC.
 */
@SpringBootTest
@Transactional
class InstantStorageTest(
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val users: UserAccountJpaRepository,
    @Autowired private val entityManager: EntityManager,
) {
    private val original: TimeZone = TimeZone.getDefault()

    @BeforeEach
    fun useBuenosAires() = TimeZone.setDefault(TimeZone.getTimeZone("America/Argentina/Buenos_Aires"))

    @AfterEach
    fun restoreZone() = TimeZone.setDefault(original)

    @Test
    fun `DI14_anInstantIsStoredInUtcAndReadBackExactly`() {
        val id = jdbc.queryForObject(
            "INSERT INTO app_user (name, email, password_hash, role, phone) VALUES ('Ana', ?, 'h', 'STUDENT', '1') RETURNING id",
            Long::class.java, "${UUID.randomUUID()}@test.local",
        )!!
        val instant = Instant.parse("2026-11-19T12:34:56.789Z")

        users.findById(id).orElseThrow().lockedUntil = instant
        entityManager.flush()
        entityManager.clear()

        val epoch = jdbc.queryForObject("SELECT extract(epoch FROM locked_until)::numeric FROM app_user WHERE id = ?", java.math.BigDecimal::class.java, id)!!
        assertThat(epoch.compareTo(java.math.BigDecimal("${instant.epochSecond}.789"))).isZero()
        assertThat(users.findById(id).orElseThrow().lockedUntil).isEqualTo(instant)
    }
}
