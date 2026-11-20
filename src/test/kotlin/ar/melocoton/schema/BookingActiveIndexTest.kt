package ar.melocoton.schema

import ar.melocoton.schema.SqlState.UNIQUE_VIOLATION
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** V3: a student has at most one active booking per lesson (DI-41). Each test is rolled back. */
@SpringBootTest
@Transactional
class BookingActiveIndexTest(@Autowired private val jdbc: JdbcTemplate) {

    private val fixtures = SchemaFixtures(jdbc)
    private val date = LocalDate.of(2026, 12, 1)

    @Test
    fun `DI41_theActiveBookingIndexExistsAndIsPartial`() {
        val definition = jdbc.queryForObject(
            "SELECT indexdef FROM pg_indexes WHERE indexname = 'booking_student_slot_date_active_ux'", String::class.java,
        )

        assertThat(definition).contains("UNIQUE").contains("(student_id, slot_id, lesson_date)").contains("status <> 'CANCELLED'")
    }

    @Test
    fun `DI41_aSecondActiveBookingInTheSameLessonIsRejected`() {
        val slot = fixtures.slot()
        val student = fixtures.monthlyStudent()
        fixtures.booking(student, slot, date = date)

        assertFailsWithSqlState(UNIQUE_VIOLATION) { fixtures.booking(student, slot, type = "DROP_IN", date = date) }
    }

    @Test
    fun `DI41_aCancelledBookingDoesNotBlockBookingAgain`() {
        val slot = fixtures.slot()
        val student = fixtures.monthlyStudent()
        fixtures.booking(student, slot, status = "CANCELLED", cancellationReason = "STUDENT", date = date)
        fixtures.booking(student, slot, status = "CANCELLED", cancellationReason = "ENROLLMENT_CHANGE", date = date)

        fixtures.booking(student, slot, date = date)

        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking WHERE student_id = ?", Int::class.java, student)).isEqualTo(3)
    }

    @Test
    fun `DI41_differentLessonsOrStudentsDoNotCollide`() {
        val slot = fixtures.slot()
        val otherSlot = fixtures.slot()
        val student = fixtures.monthlyStudent()
        val otherStudent = fixtures.monthlyStudent()
        fixtures.booking(student, slot, date = date)

        fixtures.booking(student, slot, date = date.plusDays(7))
        fixtures.booking(student, otherSlot, date = date)
        fixtures.booking(otherStudent, slot, date = date)
    }
}
