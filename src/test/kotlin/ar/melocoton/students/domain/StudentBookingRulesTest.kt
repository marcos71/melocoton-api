package ar.melocoton.students.domain

import ar.melocoton.shared.domain.BusinessErrorKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

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
}
