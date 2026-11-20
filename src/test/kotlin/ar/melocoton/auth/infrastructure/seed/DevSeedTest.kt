package ar.melocoton.auth.infrastructure.seed

import ar.melocoton.auth.application.port.PasswordHasher
import ar.melocoton.bookings.application.AssignFixedSlot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The sample accounts of local development, against the test database. The seed is built by
 * hand here: with the `test` profile Spring does not create it. Each test is rolled back.
 */
@SpringBootTest
@Transactional
class DevSeedTest(
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val passwords: PasswordHasher,
    @Autowired private val context: ApplicationContext,
    @Autowired private val assignFixedSlot: AssignFixedSlot,
) {
    // 2026-11-16 at 01:00 UTC is still November 15 in Buenos Aires.
    private val clock = Clock.fixed(Instant.parse("2026-11-16T01:00:00Z"), ZoneOffset.UTC)

    private fun seed(password: String = PASSWORD) = DevSeed(jdbc, passwords, clock, assignFixedSlot, password)

    private fun row(email: String): Map<String, Any?> = jdbc.queryForMap(
        """SELECT u.role, u.active, u.password_hash, s.enrollment_type, s.monthly_start_date
           FROM app_user u LEFT JOIN student s ON s.user_id = u.id WHERE u.email = ?""",
        email,
    )

    @Test
    fun `creates a lead teacher, an assistant teacher and three students`() {
        val created = seed().seed()

        assertThat(created).containsExactlyElementsOf(DevSeed.EMAILS)
        assertThat(row("nana@melocoton.local")).containsEntry("role", "LEAD_TEACHER").containsEntry("enrollment_type", null)
        assertThat(row("secundario@melocoton.local")).containsEntry("role", "ASSISTANT_TEACHER")
        assertThat(row("ana@melocoton.local"))
            .containsEntry("role", "STUDENT")
            .containsEntry("enrollment_type", "MONTHLY")
            .containsEntry("monthly_start_date", java.sql.Date.valueOf(LocalDate.of(2026, 11, 1)))
        assertThat(row("bruno@melocoton.local"))
            .containsEntry("enrollment_type", "DROP_IN")
            .containsEntry("monthly_start_date", null)
        assertThat(row("carla@melocoton.local")).containsEntry("active", false)
    }

    @Test
    fun `CU02_leavesSlotsForTheStudentsScreenAndDoesNotRepeatThem`() {
        // The API role cannot delete, so slots left by other tests may exist: the seed adds two only when there are none.
        val before = jdbc.queryForObject("SELECT count(*) FROM slot", Int::class.java)!!

        seed().seed()
        val after = jdbc.queryForObject("SELECT count(*) FROM slot", Int::class.java)!!
        seed().seed()

        assertThat(after).isEqualTo(if (before == 0) 2 else before)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM slot", Int::class.java)).isEqualTo(after)
    }

    @Test
    fun `RF08_theActiveMonthlySampleStudentGetsTheFirstSlotAndItsBookingsOnlyOnce`() {
        seed().seed()

        val ana = jdbc.queryForObject("SELECT id FROM app_user WHERE email = 'ana@melocoton.local'", Long::class.java)!!
        val slotId = jdbc.queryForObject("SELECT fixed_slot_id FROM student WHERE user_id = ?", Long::class.javaObjectType, ana)
        assertThat(slotId).isNotNull()
        assertThat(slotId).isEqualTo(jdbc.queryForObject("SELECT min(id) FROM slot WHERE active", Long::class.java))
        val count = { jdbc.queryForObject("SELECT count(*) FROM booking WHERE student_id = ? AND type = 'FIXED' AND status = 'SCHEDULED'", Int::class.java, ana)!! }
        assertThat(count()).isGreaterThan(0)

        val before = count()
        seed().seed()

        assertThat(count()).isEqualTo(before)
        // Neither the drop-in student nor the inactive monthly one gets a slot.
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM student s JOIN app_user u ON u.id = s.user_id " +
                    "WHERE u.email IN ('bruno@melocoton.local', 'carla@melocoton.local') AND s.fixed_slot_id IS NOT NULL",
                Int::class.java,
            ),
        ).isZero()
    }

    @Test
    fun `every sample account logs in with DEV_SEED_PASSWORD`() {
        seed().seed()

        DevSeed.EMAILS.forEach {
            assertThat(passwords.matches(PASSWORD, row(it)["password_hash"] as String)).isTrue()
        }
    }

    @Test
    fun `running it again creates nothing`() {
        seed().seed()

        assertThat(seed().seed()).isEmpty()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email LIKE '%@melocoton.local'", Int::class.java))
            .isEqualTo(DevSeed.EMAILS.size)
    }

    @Test
    fun `an existing account keeps its password`() {
        seed().seed()

        seed("otra-contraseña-de-prueba").seed()

        assertThat(passwords.matches(PASSWORD, row("ana@melocoton.local")["password_hash"] as String)).isTrue()
    }

    @Test
    fun `a short DEV_SEED_PASSWORD stops the startup with a clear message`() {
        assertThatThrownBy { seed("corta").seed() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("DEV_SEED_PASSWORD must have 10 to 128 characters")
    }

    @Test
    fun `outside the dev profile Spring does not create the seed`() {
        assertThat(context.getBeanNamesForType(DevSeed::class.java)).isEmpty()
    }

    companion object {
        private const val PASSWORD = "contraseña-de-prueba"
    }
}
