package ar.melocoton.students.domain

import ar.melocoton.shared.domain.BusinessErrorKind
import ar.melocoton.shared.domain.PersonName
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

class StudentTest {

    private val today = LocalDate.of(2026, 11, 19)

    private fun new(
        name: String = "Lucía",
        email: String = "lucia@test.local",
        phone: String = "1155550000",
        type: EnrollmentType = EnrollmentType.MONTHLY,
        start: LocalDate? = null,
    ) = NewStudent.create(name, email, phone, type, start, today)

    // ---- NewStudent ----

    @Test
    fun `RF04_aMonthlyStudentWithoutStartDateStartsToday`() {
        val student = new(type = EnrollmentType.MONTHLY, start = null)

        assertThat(student.monthlyStartDate).isEqualTo(today)
        assertThat(student.enrollmentType).isEqualTo(EnrollmentType.MONTHLY)
    }

    @Test
    fun `RF04_aMonthlyStudentKeepsTheStartDateItGets`() {
        assertThat(new(start = LocalDate.of(2026, 12, 1)).monthlyStartDate).isEqualTo(LocalDate.of(2026, 12, 1))
    }

    @Test
    fun `RF05_aDropInStudentHasNoStartDate`() {
        assertThat(new(type = EnrollmentType.DROP_IN).monthlyStartDate).isNull()
    }

    @Test
    fun `RF05_aDropInStudentWithAStartDateIsInvalid`() {
        assertThatThrownBy { new(type = EnrollmentType.DROP_IN, start = today) }
            .isInstanceOfSatisfying(InvalidStudent::class.java) {
                assertThat(it.code).isEqualTo("validation_error")
                assertThat(it.kind).isEqualTo(BusinessErrorKind.INVALID)
            }
    }

    @Test
    fun `RF04_theTextFieldsAreTrimmed`() {
        val student = new(name = "  Lucía ", email = " lucia@test.local ", phone = " 1155550000 ")

        assertThat(student.name).isEqualTo("Lucía")
        assertThat(student.email).isEqualTo("lucia@test.local")
        assertThat(student.phone).isEqualTo("1155550000")
    }

    @Test
    fun `RF04_aBlankNameEmailOrPhoneIsInvalid`() {
        assertThatThrownBy { new(name = "  ") }.isInstanceOf(InvalidStudent::class.java)
        assertThatThrownBy { new(email = "") }.isInstanceOf(InvalidStudent::class.java)
        assertThatThrownBy { new(phone = " ") }.isInstanceOf(InvalidStudent::class.java)
    }

    @Test
    fun `RF04_aNameOfUpTo80CharactersIsValidAndOneLongerIsNot`() {
        val max = "a".repeat(PersonName.MAX_LENGTH)

        assertThat(new(name = max).name).isEqualTo(max)
        assertThat(new(name = " $max ").name).isEqualTo(max)
        assertThatThrownBy { new(name = max + "a") }.isInstanceOfSatisfying(InvalidStudent::class.java) {
            assertThat(it.message).isEqualTo("El nombre puede tener hasta 80 caracteres.")
            assertThat(it.code).isEqualTo("validation_error")
            assertThat(it.kind).isEqualTo(BusinessErrorKind.INVALID)
        }
    }

    @Test
    fun `DI46_aChangedNameOfUpTo80CharactersIsValidAndOneLongerIsNot`() {
        val max = "a".repeat(PersonName.MAX_LENGTH)

        assertThat(StudentChange(max, null).name).isEqualTo(max)
        assertThatThrownBy { StudentChange(max + "a", null) }.isInstanceOfSatisfying(InvalidStudent::class.java) {
            assertThat(it.message).isEqualTo("El nombre puede tener hasta 80 caracteres.")
        }
    }

    @Test
    fun `RF04_theStudentPartHasNoFixedSlot`() {
        val student = new().toStudent(userId = 7)

        assertThat(student.userId).isEqualTo(7)
        assertThat(student.fixedSlotId).isNull()
        assertThat(student.monthlyStartDate).isEqualTo(today)
    }

    // ---- Student invariants (the schema CHECKs) ----

    @Test
    fun `RF04_aMonthlyStudentWithoutStartDateCannotBeBuilt`() {
        assertThatThrownBy { Student(1, EnrollmentType.MONTHLY, fixedSlotId = null, monthlyStartDate = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `RF05_aDropInStudentWithStartDateOrFixedSlotCannotBeBuilt`() {
        assertThatThrownBy { Student(1, EnrollmentType.DROP_IN, fixedSlotId = null, monthlyStartDate = today) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { Student(1, EnrollmentType.DROP_IN, fixedSlotId = 3, monthlyStartDate = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `RF04_aMonthlyStudentMayHaveAFixedSlot`() {
        assertThat(Student(1, EnrollmentType.MONTHLY, fixedSlotId = 3, monthlyStartDate = today).fixedSlotId).isEqualTo(3)
    }

    // ---- StudentChange ----

    @Test
    fun `RF04_aChangeWithNothingInItIsInvalid`() {
        assertThatThrownBy { StudentChange(null, null) }.isInstanceOf(InvalidStudent::class.java)
    }

    @Test
    fun `RF04_aChangeTrimsAndRejectsBlankValues`() {
        val change = StudentChange(" Lucía G. ", null)

        assertThat(change.name).isEqualTo("Lucía G.")
        assertThat(change.phone).isNull()
        assertThatThrownBy { StudentChange("  ", null) }.isInstanceOf(InvalidStudent::class.java)
        assertThatThrownBy { StudentChange(null, "") }.isInstanceOf(InvalidStudent::class.java)
    }

    @Test
    fun `RF04_aChangeMayCarryOnlyThePhone`() {
        val change = StudentChange(null, "1166667777")

        assertThat(change.name).isNull()
        assertThat(change.phone).isEqualTo("1166667777")
    }
}
