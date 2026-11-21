package ar.melocoton.schema

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

/** PostgreSQL SQL states the schema tests expect. */
object SqlState {
    const val CHECK_VIOLATION = "23514"
    const val UNIQUE_VIOLATION = "23505"
    const val INSUFFICIENT_PRIVILEGE = "42501"
}

/** Runs [block] and asserts it failed in the database with [sqlState]. */
fun assertFailsWithSqlState(sqlState: String, block: () -> Unit) {
    val error = catchThrowable(block)
    assertThat(error).describedAs("the statement should have been rejected").isNotNull
    val sqlError = requireNotNull(generateSequence(error) { it.cause }.filterIsInstance<SQLException>().firstOrNull()) {
        "Expected a database error, got: $error"
    }
    assertThat(sqlError.sqlState).describedAs(sqlError.message).isEqualTo(sqlState)
}

/** Inserts minimal valid rows, as the API role, so each test only varies what it checks. */
class SchemaFixtures(private val jdbc: JdbcTemplate) {

    private fun id(sql: String, vararg args: Any?): Long = jdbc.queryForObject(sql, Long::class.java, *args)!!

    fun user(role: String = "STUDENT", email: String = "${UUID.randomUUID()}@test.local"): Long = id(
        "INSERT INTO app_user (name, email, password_hash, role, phone) VALUES ('Test', ?, 'hash', ?, '1100000000') RETURNING id",
        email, role,
    )

    fun slot(): Long = id(
        "INSERT INTO slot (day_of_week, start_time, end_time) VALUES (2, '18:00', '20:00') RETURNING id",
    )

    fun monthlyStudent(slotId: Long? = null): Long {
        val userId = user()
        jdbc.update(
            "INSERT INTO student (user_id, enrollment_type, fixed_slot_id, monthly_start_date) VALUES (?, 'MONTHLY', ?, ?)",
            userId, slotId, LocalDate.of(2026, 3, 1),
        )
        return userId
    }

    fun dropInStudent(): Long {
        val userId = user()
        jdbc.update("INSERT INTO student (user_id, enrollment_type) VALUES (?, 'DROP_IN')", userId)
        return userId
    }

    fun booking(
        studentId: Long,
        slotId: Long,
        type: String = "FIXED",
        status: String = "SCHEDULED",
        originalBookingId: Long? = null,
        cancellationReason: String? = null,
        date: LocalDate = LocalDate.of(2026, 12, 1),
    ): Long = id(
        """INSERT INTO booking (student_id, slot_id, lesson_date, type, status, original_booking_id, cancellation_reason)
           VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""",
        studentId, slotId, date, type, status, originalBookingId, cancellationReason,
    )

    fun makeupRequest(studentId: Long, originalBookingId: Long, slotId: Long, status: String = "PENDING"): Long = id(
        """INSERT INTO makeup_request (student_id, status, created_by, created_at, original_booking_id, target_slot_id, target_date)
           VALUES (?, ?, ?, now(), ?, ?, '2026-12-15') RETURNING id""",
        studentId, status, studentId, originalBookingId, slotId,
    )

    fun piece(studentId: Long): Long = id(
        "INSERT INTO piece (student_id, name, status, created_at) VALUES (?, 'Bowl', 'IN_PROGRESS', now()) RETURNING id",
        studentId,
    )

    fun monthlyPayment(studentId: Long, recordedBy: Long): Long = id(
        """INSERT INTO payment (student_id, concept, period, amount, paid_on, method, recorded_by, recorded_at)
           VALUES (?, 'MONTHLY_FEE', '2026-11-01', 45000, '2026-11-05', 'CASH', ?, now()) RETURNING id""",
        studentId, recordedBy,
    )
}
