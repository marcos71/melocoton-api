package ar.melocoton.students.domain

import ar.melocoton.shared.domain.BusinessErrorKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class StudentBookingRulesTest {

    private fun monthly(active: Boolean = true, slot: Long? = null) =
        Student(1, EnrollmentType.MONTHLY, slot, LocalDate.of(2026, 3, 1), active)

    private fun dropIn(active: Boolean = true) = Student(2, EnrollmentType.DROP_IN, null, null, active)

    @Test
    fun `RF08_anActiveMonthlyStudentGetsAFixedSlotAndTheOldOneIsReturned`() {
        val student = monthly(slot = 3)

        val previous = student.assignFixedSlot(4)

        assertThat(previous).isEqualTo(3)
        assertThat(student.fixedSlotId).isEqualTo(4)
        assertThat(monthly().assignFixedSlot(4)).isNull()
    }

    @Test
    fun `RF08_aDropInStudentCannotHaveAFixedSlot`() {
        assertThatThrownBy { dropIn().assignFixedSlot(4) }.isInstanceOfSatisfying(EnrollmentTypeMismatch::class.java) {
            assertThat(it.code).isEqualTo("enrollment_type_mismatch")
            assertThat(it.message).isEqualTo("Solo un alumno mensual tiene turno fijo.")
            assertThat(it.kind).isEqualTo(BusinessErrorKind.CONFLICT)
        }
    }

    @Test
    fun `RF08_anInactiveStudentCannotHaveAFixedSlot`() {
        assertThatThrownBy { monthly(active = false).assignFixedSlot(4) }
            .isInstanceOfSatisfying(StudentInactive::class.java) { assertThat(it.code).isEqualTo("student_inactive") }
    }

    @Test
    fun `RF09_anActiveDropInStudentBooksDropInClasses`() {
        assertThatCode { dropIn().requireCanBookDropIn() }.doesNotThrowAnyException()
    }

    @Test
    fun `RF09_aMonthlyStudentCannotBookADropInClass`() {
        assertThatThrownBy { monthly().requireCanBookDropIn() }.isInstanceOfSatisfying(EnrollmentTypeMismatch::class.java) {
            assertThat(it.message).isEqualTo("La clase suelta es solo para alumnos de clase suelta.")
        }
    }

    @Test
    fun `RF09_anInactiveDropInStudentCannotBookADropInClass`() {
        assertThatThrownBy { dropIn(active = false).requireCanBookDropIn() }.isInstanceOf(StudentInactive::class.java)
    }

    @Test
    fun `RF64_onlyAnActiveMonthlyStudentWithAFixedSlotGetsFixedBookings`() {
        assertThat(monthly(slot = 3).hasFixedSlotToFill).isTrue()
        assertThat(monthly().hasFixedSlotToFill).isFalse()
        assertThat(monthly(active = false, slot = 3).hasFixedSlotToFill).isFalse()
    }

    @Test
    fun `DI46_upcomingLessonsDropTheOnesThatStartedAndAreOrderedByDateThenTime`() {
        val now = LocalDateTime.of(2026, 11, 19, 12, 0)
        fun lesson(id: Long, date: String, start: String) = UpcomingLesson(
            id, LocalDate.parse(date), 1, DayOfWeek.TUESDAY, LocalTime.parse(start), LocalTime.parse(start).plusHours(2),
            UpcomingLessonType.FIXED,
        )

        val result = listOf(
            lesson(1, "2026-11-24", "18:00"),
            lesson(2, "2026-11-19", "11:59"),
            lesson(3, "2026-11-19", "12:00"),
            lesson(4, "2026-11-19", "12:01"),
            lesson(5, "2026-11-24", "10:00"),
        ).notStarted(now)

        assertThat(result.map { it.bookingId }).containsExactly(4, 5, 1)
    }
}
