package ar.melocoton.auth.infrastructure.seed

import ar.melocoton.auth.application.port.PasswordHasher
import ar.melocoton.auth.domain.PasswordPolicy
import ar.melocoton.shared.domain.Role
import ar.melocoton.shared.domain.WorkshopTime
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/**
 * Sample accounts for local development (plan, iteration 1), all with the password of
 * DEV_SEED_PASSWORD. Only exists with the `dev` profile, which `gradlew bootRun` turns on.
 *
 * Spring runs every [ApplicationRunner] bean once, right after startup. Accounts that already
 * exist are left alone, so a password changed from a link is kept.
 *
 * The student rows are written with plain SQL because the `students` module arrives in
 * iteration 2; this is sample data, not a use case.
 */
@Component
@Profile("dev")
class DevSeed(
    private val jdbc: JdbcTemplate,
    private val passwords: PasswordHasher,
    private val clock: Clock,
    @Value("\${melocoton.dev-seed.password}") private val password: String,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    private enum class Enrollment { MONTHLY, DROP_IN }

    private data class SampleAccount(
        val name: String,
        val email: String,
        val role: Role,
        val active: Boolean = true,
        val enrollment: Enrollment? = null,
    )

    @Transactional
    override fun run(args: ApplicationArguments) {
        seed()
    }

    /** Creates the missing sample accounts and returns the emails it created. */
    fun seed(): List<String> {
        check(runCatching { PasswordPolicy.check(password) }.isSuccess) {
            "DEV_SEED_PASSWORD must have ${PasswordPolicy.MIN_LENGTH} to ${PasswordPolicy.MAX_LENGTH} characters"
        }
        val hash = passwords.hash(password)
        val created = ACCOUNTS.filter { create(it, hash) }.map { it.email }
        log.info(
            "Dev seed: {} account(s) created {}. Every sample account uses DEV_SEED_PASSWORD.",
            created.size, created,
        )
        return created
    }

    private fun create(account: SampleAccount, hash: String): Boolean {
        // ON CONFLICT on the lower(email) unique index: an existing account is not touched.
        val id = jdbc.query(
            """INSERT INTO app_user (name, email, password_hash, role, phone, active)
               VALUES (?, ?, ?, ?, '1100000000', ?)
               ON CONFLICT ((lower(email))) DO NOTHING
               RETURNING id""",
            { rs, _ -> rs.getLong("id") },
            account.name, account.email, hash, account.role.name, account.active,
        ).singleOrNull() ?: return false

        when (account.enrollment) {
            Enrollment.MONTHLY -> jdbc.update(
                "INSERT INTO student (user_id, enrollment_type, monthly_start_date) VALUES (?, 'MONTHLY', ?)",
                id, LocalDate.now(clock.withZone(WorkshopTime.ZONE)).withDayOfMonth(1),
            )
            Enrollment.DROP_IN -> jdbc.update(
                "INSERT INTO student (user_id, enrollment_type) VALUES (?, 'DROP_IN')",
                id,
            )
            null -> Unit
        }
        return true
    }

    companion object {
        private val ACCOUNTS = listOf(
            SampleAccount("Nana Docente", "nana@melocoton.local", Role.LEAD_TEACHER),
            SampleAccount("Martín Secundario", "secundario@melocoton.local", Role.ASSISTANT_TEACHER),
            SampleAccount("Ana Mensual", "ana@melocoton.local", Role.STUDENT, enrollment = Enrollment.MONTHLY),
            SampleAccount("Bruno Suelta", "bruno@melocoton.local", Role.STUDENT, enrollment = Enrollment.DROP_IN),
            SampleAccount(
                "Carla Inactiva", "carla@melocoton.local", Role.STUDENT, active = false,
                enrollment = Enrollment.MONTHLY,
            ),
        )

        val EMAILS: List<String> = ACCOUNTS.map { it.email }
    }
}
