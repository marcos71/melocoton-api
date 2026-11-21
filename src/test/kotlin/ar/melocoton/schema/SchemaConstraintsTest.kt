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
 * The database rejects rows that break a rule it can check on its own, as the last
 * barrier behind the domain (02, 6.1). Runs as the API role; each test is rolled back.
 */
@SpringBootTest
@Transactional
class SchemaConstraintsTest(@Autowired private val jdbc: JdbcTemplate) {

    private val f = SchemaFixtures(jdbc)

    // --- A valid graph: the constraints are not stricter than the model --------------------

    @Test
    fun `a complete valid set of rows is accepted`() {
        val teacher = f.user("LEAD_TEACHER")
        val slot = f.slot()
        val student = f.monthlyStudent(slotId = slot)
        val absence = f.booking(student, slot, status = "ABSENT_RECOVERABLE")
        // A different date: a student has one active booking per lesson (V3, DI-41).
        f.booking(student, slot, type = "MAKEUP", originalBookingId = absence, date = java.time.LocalDate.of(2026, 12, 15))
        f.makeupRequest(student, absence, slot)
        val piece = f.piece(student)
        jdbc.update(
            """INSERT INTO piece_status_history (piece_id, new_status, event_type, occurred_at, user_id, operation_id)
               VALUES (?, 'IN_PROGRESS', 'CREATED', now(), ?, gen_random_uuid())""",
            piece, student,
        )
        f.monthlyPayment(student, recordedBy = teacher)
        jdbc.update(
            "INSERT INTO lesson_closure (slot_id, lesson_date, reason, created_by, created_at) VALUES (?, '2026-12-08', 'Feriado', ?, now())",
            slot, teacher,
        )
        val withdrawal = jdbc.queryForObject(
            "INSERT INTO withdrawal_request (student_id, status, created_by, created_at) VALUES (?, 'PENDING', ?, now()) RETURNING id",
            Long::class.java, student, student,
        )
        jdbc.update(
            "INSERT INTO student_audit (student_id, event, occurred_at, user_id, withdrawal_request_id) VALUES (?, 'WITHDRAWAL', now(), ?, ?)",
            student, teacher, withdrawal,
        )
        jdbc.update(
            "INSERT INTO setting_change (setting, previous_value, new_value, changed_at, user_id) VALUES ('payment_due_day', '10', '15', now(), ?)",
            teacher,
        )
        jdbc.update("INSERT INTO ai_call (student_id, created_at, result) VALUES (?, now(), 'IN_PROGRESS')", student)

        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking WHERE student_id = ?", Int::class.java, student))
            .isEqualTo(2)
    }

    // --- Users and students -------------------------------------------------------------------

    @Test
    fun `an email is unique regardless of case`() {
        f.user(email = "ana@test.local")
        assertFailsWithSqlState(UNIQUE_VIOLATION) { f.user(email = "ANA@test.local") }
    }

    @Test
    fun `an unknown role is rejected`() {
        assertFailsWithSqlState(CHECK_VIOLATION) { f.user(role = "ADMIN") }
    }

    @Test
    fun `RN-12 a monthly student needs a monthly start date`() {
        val user = f.user()
        assertFailsWithSqlState(CHECK_VIOLATION) {
            jdbc.update("INSERT INTO student (user_id, enrollment_type) VALUES (?, 'MONTHLY')", user)
        }
    }

    @Test
    fun `RN-03 a drop-in student has no monthly start date`() {
        val user = f.user()
        assertFailsWithSqlState(CHECK_VIOLATION) {
            jdbc.update(
                "INSERT INTO student (user_id, enrollment_type, monthly_start_date) VALUES (?, 'DROP_IN', '2026-03-01')",
                user,
            )
        }
    }

    @Test
    fun `RN-03 a drop-in student has no fixed slot`() {
        val user = f.user()
        val slot = f.slot()
        assertFailsWithSqlState(CHECK_VIOLATION) {
            jdbc.update("INSERT INTO student (user_id, enrollment_type, fixed_slot_id) VALUES (?, 'DROP_IN', ?)", user, slot)
        }
    }

    // --- Slots and lessons ---------------------------------------------------------------------

    @Test
    fun `a slot needs a positive capacity`() {
        assertFailsWithSqlState(CHECK_VIOLATION) { f.slot(capacity = 0) }
    }

    @Test
    fun `DI-64 a slot holds at most 12 students`() {
        assertFailsWithSqlState(CHECK_VIOLATION) { f.slot(capacity = 13) }
    }

    @Test
    fun `DI-64 a slot of exactly 12 students is accepted`() {
        f.slot(capacity = 12)
    }

    @Test
    fun `a slot ends after it starts`() {
        assertFailsWithSqlState(CHECK_VIOLATION) {
            jdbc.update("INSERT INTO slot (day_of_week, start_time, end_time, capacity) VALUES (2, '20:00', '18:00', 8)")
        }
    }

    @Test
    fun `a slot day is between Monday (1) and Sunday (7)`() {
        assertFailsWithSqlState(CHECK_VIOLATION) {
            jdbc.update("INSERT INTO slot (day_of_week, start_time, end_time, capacity) VALUES (8, '18:00', '20:00', 8)")
        }
    }

    @Test
    fun `RN-17 a lesson is closed only once`() {
        val slot = f.slot()
        val teacher = f.user("LEAD_TEACHER")
        val close = "INSERT INTO lesson_closure (slot_id, lesson_date, reason, created_by, created_at) VALUES (?, '2026-12-08', 'Feriado', ?, now())"
        jdbc.update(close, slot, teacher)
        assertFailsWithSqlState(UNIQUE_VIOLATION) { jdbc.update(close, slot, teacher) }
    }

    // --- Bookings ------------------------------------------------------------------------------

    @Test
    fun `RN-10 a make-up booking points to its original absence`() {
        val slot = f.slot()
        val student = f.monthlyStudent()
        assertFailsWithSqlState(CHECK_VIOLATION) { f.booking(student, slot, type = "MAKEUP") }
    }

    @Test
    fun `only make-up bookings point to an original absence`() {
        val slot = f.slot()
        val student = f.monthlyStudent()
        val absence = f.booking(student, slot, status = "ABSENT_RECOVERABLE")
        assertFailsWithSqlState(CHECK_VIOLATION) { f.booking(student, slot, type = "FIXED", originalBookingId = absence) }
    }

    @Test
    fun `a cancelled booking says why`() {
        val slot = f.slot()
        val student = f.monthlyStudent()
        assertFailsWithSqlState(CHECK_VIOLATION) { f.booking(student, slot, status = "CANCELLED") }
    }

    @Test
    fun `a cancelled booking with a reason is accepted`() {
        val slot = f.slot()
        val student = f.monthlyStudent()
        f.booking(student, slot, status = "CANCELLED", cancellationReason = "WORKSHOP_CLOSURE")
    }

    @Test
    fun `an unknown booking status is rejected`() {
        val slot = f.slot()
        val student = f.monthlyStudent()
        assertFailsWithSqlState(CHECK_VIOLATION) { f.booking(student, slot, status = "LATE") }
    }

    @Test
    fun `who recorded attendance and when go together`() {
        val slot = f.slot()
        val student = f.monthlyStudent()
        val booking = f.booking(student, slot)
        assertFailsWithSqlState(CHECK_VIOLATION) {
            jdbc.update("UPDATE booking SET status = 'PRESENT', attendance_recorded_by = ? WHERE id = ?", student, booking)
        }
    }

    // --- Make-up requests ----------------------------------------------------------------------

    @Test
    fun `RN-07 a student has a single open make-up request`() {
        val slot = f.slot()
        val student = f.monthlyStudent()
        val absence1 = f.booking(student, slot, status = "ABSENT_RECOVERABLE")
        val absence2 = f.booking(student, slot, status = "ABSENT_RECOVERABLE", date = java.time.LocalDate.of(2026, 12, 8))
        f.makeupRequest(student, absence1, slot, status = "PENDING")

        assertFailsWithSqlState(UNIQUE_VIOLATION) { f.makeupRequest(student, absence2, slot, status = "PENDING") }
    }

    @Test
    fun `RN-07 a pending and a counter-proposed request also count as two open ones`() {
        val slot = f.slot()
        val student = f.monthlyStudent()
        val absence = f.booking(student, slot, status = "ABSENT_RECOVERABLE")
        f.makeupRequest(student, absence, slot, status = "PENDING")

        assertFailsWithSqlState(UNIQUE_VIOLATION) {
            jdbc.update(
                """INSERT INTO makeup_request (student_id, status, created_by, created_at, original_booking_id,
                       target_slot_id, target_date, proposed_slot_id, proposed_date, expires_at)
                   VALUES (?, 'COUNTER_PROPOSED', ?, now(), ?, ?, '2026-12-15', ?, '2026-12-16', now())""",
                student, student, absence, slot, slot,
            )
        }
    }

    @Test
    fun `RN-07 closed requests do not block a new one`() {
        val slot = f.slot()
        val student = f.monthlyStudent()
        val absence = f.booking(student, slot, status = "ABSENT_RECOVERABLE")
        f.makeupRequest(student, absence, slot, status = "APPROVED")
        f.makeupRequest(student, absence, slot, status = "REJECTED")
        f.makeupRequest(student, absence, slot, status = "PENDING")
    }

    @Test
    fun `RN-19 a counter-proposal carries the proposed lesson and its deadline`() {
        val slot = f.slot()
        val student = f.monthlyStudent()
        val absence = f.booking(student, slot, status = "ABSENT_RECOVERABLE")
        assertFailsWithSqlState(CHECK_VIOLATION) { f.makeupRequest(student, absence, slot, status = "COUNTER_PROPOSED") }
    }

    // --- Pieces --------------------------------------------------------------------------------

    @Test
    fun `RN-20 a voided piece records when, who and why`() {
        val student = f.monthlyStudent()
        val piece = f.piece(student)
        assertFailsWithSqlState(CHECK_VIOLATION) {
            jdbc.update("UPDATE piece SET voided_at = now() WHERE id = ?", piece)
        }
    }

    @Test
    fun `an unknown piece status is rejected`() {
        val student = f.monthlyStudent()
        val piece = f.piece(student)
        assertFailsWithSqlState(CHECK_VIOLATION) {
            jdbc.update("UPDATE piece SET status = 'BROKEN' WHERE id = ?", piece)
        }
    }

    @Test
    fun `RN-05 a creation event has no previous status`() {
        val student = f.monthlyStudent()
        val piece = f.piece(student)
        assertFailsWithSqlState(CHECK_VIOLATION) {
            jdbc.update(
                """INSERT INTO piece_status_history (piece_id, previous_status, new_status, event_type, occurred_at, user_id, operation_id)
                   VALUES (?, 'IN_PROGRESS', 'IN_PROGRESS', 'CREATED', now(), ?, gen_random_uuid())""",
                piece, student,
            )
        }
    }

    @Test
    fun `RN-05 an advance records both statuses`() {
        val student = f.monthlyStudent()
        val piece = f.piece(student)
        assertFailsWithSqlState(CHECK_VIOLATION) {
            jdbc.update(
                """INSERT INTO piece_status_history (piece_id, new_status, event_type, occurred_at, user_id, operation_id)
                   VALUES (?, 'BISQUE_FIRED', 'ADVANCED', now(), ?, gen_random_uuid())""",
                piece, student,
            )
        }
    }

    // --- Payments ------------------------------------------------------------------------------

    private fun insertPayment(concept: String, period: String?, bookingId: Long?, amount: Int) {
        val student = f.dropInStudent()
        val teacher = f.user("LEAD_TEACHER")
        jdbc.update(
            """INSERT INTO payment (student_id, concept, period, booking_id, amount, paid_on, method, recorded_by, recorded_at)
               VALUES (?, ?, ?::date, ?, ?, '2026-11-05', 'TRANSFER', ?, now())""",
            student, concept, period, bookingId, amount, teacher,
        )
    }

    @Test
    fun `D-50 the amount is a positive whole number of pesos`() {
        assertFailsWithSqlState(CHECK_VIOLATION) { insertPayment("MONTHLY_FEE", "2026-11-01", null, 0) }
    }

    @Test
    fun `a monthly fee is for a month`() {
        assertFailsWithSqlState(CHECK_VIOLATION) { insertPayment("MONTHLY_FEE", null, null, 45000) }
    }

    @Test
    fun `a month is stored as its first day`() {
        assertFailsWithSqlState(CHECK_VIOLATION) { insertPayment("MONTHLY_FEE", "2026-11-15", null, 45000) }
    }

    @Test
    fun `a drop-in class payment is not for a month`() {
        assertFailsWithSqlState(CHECK_VIOLATION) { insertPayment("DROP_IN_CLASS", "2026-11-01", null, 9000) }
    }

    @Test
    fun `a drop-in class payment for a booking is accepted`() {
        val student = f.dropInStudent()
        val booking = f.booking(student, f.slot(), type = "DROP_IN")
        val teacher = f.user("LEAD_TEACHER")
        jdbc.update(
            """INSERT INTO payment (student_id, concept, booking_id, amount, paid_on, method, recorded_by, recorded_at)
               VALUES (?, 'DROP_IN_CLASS', ?, 9000, '2026-11-05', 'CASH', ?, now())""",
            student, booking, teacher,
        )
    }

    @Test
    fun `several months are paid as one payment per month`() {
        val student = f.monthlyStudent()
        val teacher = f.user("LEAD_TEACHER")
        listOf("2026-10-01", "2026-11-01").forEach { month ->
            jdbc.update(
                """INSERT INTO payment (student_id, concept, period, amount, paid_on, method, recorded_by, recorded_at)
                   VALUES (?, 'MONTHLY_FEE', ?::date, 45000, '2026-11-05', 'CASH', ?, now())""",
                student, month, teacher,
            )
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE student_id = ?", Int::class.java, student))
            .isEqualTo(2)
    }

    // --- Settings and AI calls -----------------------------------------------------------------

    @Test
    fun `settings is a single row`() {
        assertFailsWithSqlState(UNIQUE_VIOLATION) {
            jdbc.update(
                "INSERT INTO settings (payment_due_day, absence_max_age_months, counter_proposal_ttl_hours, makeup_horizon_days) VALUES (10, 3, 24, 60)",
            )
        }
    }

    @Test
    fun `the payment due day is a day of the month`() {
        assertFailsWithSqlState(CHECK_VIOLATION) { jdbc.update("UPDATE settings SET payment_due_day = 32") }
    }

    @Test
    fun `a setting change names one of the four settings`() {
        val teacher = f.user("LEAD_TEACHER")
        assertFailsWithSqlState(CHECK_VIOLATION) {
            jdbc.update(
                "INSERT INTO setting_change (setting, previous_value, new_value, changed_at, user_id) VALUES ('time_zone', 'a', 'b', now(), ?)",
                teacher,
            )
        }
    }

    @Test
    fun `RN-22 an AI call starts with zero tokens and cost`() {
        val student = f.monthlyStudent()
        jdbc.update("INSERT INTO ai_call (student_id, created_at, result) VALUES (?, now(), 'IN_PROGRESS')", student)
        val row = jdbc.queryForMap("SELECT input_tokens, output_tokens, cost_usd, retries FROM ai_call WHERE student_id = ?", student)
        assertThat(row["input_tokens"]).isEqualTo(0)
        assertThat(row["output_tokens"]).isEqualTo(0)
        assertThat(row["cost_usd"].toString().toBigDecimal()).isZero()
        assertThat(row["retries"]).isEqualTo(0) // smallint comes back as Integer
    }

    @Test
    fun `an AI call cannot have negative tokens`() {
        val student = f.monthlyStudent()
        assertFailsWithSqlState(CHECK_VIOLATION) {
            jdbc.update("INSERT INTO ai_call (student_id, created_at, result, input_tokens) VALUES (?, now(), 'OK', -1)", student)
        }
    }
}
