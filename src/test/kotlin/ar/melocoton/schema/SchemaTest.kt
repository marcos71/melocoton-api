package ar.melocoton.schema

import ar.melocoton.schema.SqlState.INSUFFICIENT_PRIVILEGE
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional

/**
 * The migrations create the MVP tables, seed the settings and grant the API role
 * exactly what it needs (DI-05; 02, 4.2 and 5). Runs as the API role; each test is rolled back.
 */
@SpringBootTest
@Transactional
class SchemaTest(@Autowired private val jdbc: JdbcTemplate) {

    private val fixtures = SchemaFixtures(jdbc)

    private val insertOnly = setOf("payment", "piece_status_history", "student_audit", "setting_change")
    private val updatable = setOf(
        "app_user", "student", "slot", "lesson_closure", "booking", "makeup_request",
        "withdrawal_request", "piece", "settings", "ai_call", "account_link",
    )

    private fun tables(): List<String> = jdbc.queryForList(
        "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
        String::class.java,
    ).filterNotNull()

    private fun can(privilege: String, table: String): Boolean =
        jdbc.queryForObject("SELECT has_table_privilege(current_user, ?, ?)", Boolean::class.java, table, privilege)!!

    @Test
    fun `the migrations create the 14 tables of V1 and account_link of V2`() {
        assertThat(tables()).containsExactlyInAnyOrderElementsOf(insertOnly + updatable)
    }

    @Test
    fun `settings has a single row with the initial values`() {
        // The JDBC driver returns smallint columns as Integer.
        val row = jdbc.queryForMap("SELECT * FROM settings")
        assertThat(row).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "id" to 1,
                "payment_due_day" to 10,
                "absence_max_age_months" to 3,
                "counter_proposal_ttl_hours" to 24,
                "makeup_horizon_days" to 60,
            ),
        )
    }

    @Test
    fun `every table has privileges for the API role, so no new table is forgotten`() {
        assertThat(tables()).allSatisfy { table ->
            assertThat(can("SELECT", table)).describedAs("SELECT on $table").isTrue()
            assertThat(can("INSERT", table)).describedAs("INSERT on $table").isTrue()
        }
    }

    @Test
    fun `the API role can update only the tables that are not insert-only`() {
        assertThat(updatable).allSatisfy { assertThat(can("UPDATE", it)).describedAs("UPDATE on $it").isTrue() }
        assertThat(insertOnly).allSatisfy { assertThat(can("UPDATE", it)).describedAs("UPDATE on $it").isFalse() }
    }

    @Test
    fun `the API role cannot delete from any table`() {
        assertThat(tables()).allSatisfy { assertThat(can("DELETE", it)).describedAs("DELETE on $it").isFalse() }
    }

    @Test
    fun `the API role cannot touch the migration history`() {
        assertThat(can("SELECT", "flyway_schema_history")).isFalse()
    }

    @Test
    fun `RN-06 a recorded payment cannot be updated`() {
        val student = fixtures.monthlyStudent()
        val payment = fixtures.monthlyPayment(student, recordedBy = fixtures.user("LEAD_TEACHER"))

        assertFailsWithSqlState(INSUFFICIENT_PRIVILEGE) {
            jdbc.update("UPDATE payment SET amount = 1 WHERE id = ?", payment)
        }
    }

    @Test
    fun `RN-06 a recorded payment cannot be deleted`() {
        val student = fixtures.monthlyStudent()
        val payment = fixtures.monthlyPayment(student, recordedBy = fixtures.user("LEAD_TEACHER"))

        assertFailsWithSqlState(INSUFFICIENT_PRIVILEGE) {
            jdbc.update("DELETE FROM payment WHERE id = ?", payment)
        }
    }

    @Test
    fun `RN-05 the piece history cannot be rewritten`() {
        assertFailsWithSqlState(INSUFFICIENT_PRIVILEGE) {
            jdbc.update("UPDATE piece_status_history SET event_type = 'CREATED'")
        }
    }

    @Test
    fun `the student audit cannot be rewritten`() {
        assertFailsWithSqlState(INSUFFICIENT_PRIVILEGE) {
            jdbc.update("UPDATE student_audit SET reason = 'x'")
        }
    }

    @Test
    fun `the history of setting changes cannot be rewritten`() {
        assertFailsWithSqlState(INSUFFICIENT_PRIVILEGE) {
            jdbc.update("UPDATE setting_change SET new_value = 'x'")
        }
    }

    @Test
    fun `RN-09 a student is never deleted, only deactivated`() {
        val student = fixtures.monthlyStudent()

        assertFailsWithSqlState(INSUFFICIENT_PRIVILEGE) {
            jdbc.update("DELETE FROM app_user WHERE id = ?", student)
        }
    }
}
