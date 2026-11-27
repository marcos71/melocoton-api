package ar.melocoton.students.domain

import ar.melocoton.shared.domain.PersonName
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** How a student attends (RN-03). */
enum class EnrollmentType { MONTHLY, DROP_IN }

/** Which students a list asks for, by their account state. */
enum class StudentStatusFilter { ACTIVE, INACTIVE, ALL }

/** What a student list narrows by; a null field does not filter (CU-02, RF-05). */
data class StudentFilter(
    val status: StudentStatusFilter = StudentStatusFilter.ACTIVE,
    val enrollmentType: EnrollmentType? = null,
    val slotId: Long? = null,
)

/**
 * The student part of a person's account: modality, fixed slot and monthly start date (RN-03, D-26).
 * The invariants of the schema are enforced by construction: a monthly student has a start date and a
 * drop-in student does not; only a monthly student can have a fixed slot.
 */
class Student(
    val userId: Long,
    val enrollmentType: EnrollmentType,
    fixedSlotId: Long?,
    val monthlyStartDate: LocalDate?,
    /** The account state (`app_user.active`); a new student is active. */
    val active: Boolean = true,
) {
    var fixedSlotId: Long? = fixedSlotId
        private set

    init {
        when (enrollmentType) {
            EnrollmentType.MONTHLY -> require(monthlyStartDate != null) { "A monthly student needs a start date" }
            EnrollmentType.DROP_IN -> {
                require(monthlyStartDate == null) { "A drop-in student has no monthly start date" }
                require(fixedSlotId == null) { "A drop-in student has no fixed slot" }
            }
        }
    }

    /** True when fixed bookings are generated for this student: active, monthly and with a fixed slot (RF-64). */
    val hasFixedSlotToFill: Boolean
        get() = active && enrollmentType == EnrollmentType.MONTHLY && fixedSlotId != null

    /**
     * Only an active monthly student has a fixed slot (RN-03, RF-08, DI-27).
     *
     * @throws EnrollmentTypeMismatch the student is drop-in
     * @throws StudentInactive the student is inactive
     */
    fun requireCanHaveFixedSlot() {
        if (enrollmentType != EnrollmentType.MONTHLY) {
            throw EnrollmentTypeMismatch("Solo un alumno mensual tiene turno fijo.")
        }
        requireActive()
    }

    /**
     * Only an active drop-in student books drop-in classes (RN-03, RF-09, DI-44).
     *
     * @throws EnrollmentTypeMismatch the student is monthly
     * @throws StudentInactive the student is inactive
     */
    fun requireCanBookDropIn() {
        if (enrollmentType != EnrollmentType.DROP_IN) {
            throw EnrollmentTypeMismatch("La clase suelta es solo para alumnos de clase suelta.")
        }
        requireActive()
    }

    /**
     * Only an active student owns new pieces (RF-24).
     *
     * @throws StudentInactive the student is inactive
     */
    fun requireActive() {
        if (!active) throw StudentInactive()
    }

    /**
     * Sets the fixed slot (RF-08, DI-27) and returns the previous one, null when there was none.
     * Assigning the slot the student already has is allowed.
     *
     * @throws EnrollmentTypeMismatch the student is drop-in
     * @throws StudentInactive the student is inactive
     */
    fun assignFixedSlot(slotId: Long): Long? {
        requireCanHaveFixedSlot()
        val previous = fixedSlotId
        fixedSlotId = slotId
        return previous
    }
}

/** A student to be created (CU-02, RF-04, RF-05, DI-46): already validated, without an id. */
class NewStudent private constructor(
    val name: String,
    val email: String,
    val phone: String,
    val enrollmentType: EnrollmentType,
    val monthlyStartDate: LocalDate?,
) {
    /** The student part, once the account exists. A new student never has a fixed slot (assigning one is a later step). */
    fun toStudent(userId: Long) = Student(userId, enrollmentType, fixedSlotId = null, monthlyStartDate = monthlyStartDate)

    companion object {
        /**
         * Name, email and phone are required and trimmed. A monthly student starts on [monthlyStartDate],
         * or [today] when it is null; a drop-in student has no start date (DI-46).
         * The email format is checked at the API boundary, as for assistant teachers.
         *
         * @param today the current date in the workshop zone
         * @throws InvalidStudent a text is blank, the name is longer than [PersonName.MAX_LENGTH], or a drop-in student comes with a start date
         * @throws InvalidMonthlyStartDate a monthly student's start date is outside the range of DI-58
         */
        fun create(
            name: String,
            email: String,
            phone: String,
            enrollmentType: EnrollmentType,
            monthlyStartDate: LocalDate?,
            today: LocalDate,
        ): NewStudent {
            val trimmedName = requireName(name)
            val trimmedEmail = requireText(email, "El email")
            val trimmedPhone = requireText(phone, "El teléfono")
            val startDate = when (enrollmentType) {
                EnrollmentType.MONTHLY -> (monthlyStartDate ?: today).also { requireValidMonthlyStartDate(it, today) }
                EnrollmentType.DROP_IN -> {
                    if (monthlyStartDate != null) {
                        throw InvalidStudent("Un alumno de clase suelta no tiene fecha de ingreso mensual.")
                    }
                    null
                }
            }
            return NewStudent(trimmedName, trimmedEmail, trimmedPhone, enrollmentType, startDate)
        }
    }
}

/**
 * The fields a PATCH changes: name and phone only (DI-46; the email and the modality are not editable here).
 * At least one is present and none is blank; the values are trimmed.
 *
 * @throws InvalidStudent nothing to change, a given text is blank, or the name is longer than [PersonName.MAX_LENGTH]
 */
class StudentChange(name: String?, phone: String?) {
    val name: String? = name?.let { requireName(it) }
    val phone: String? = phone?.let { requireText(it, "El teléfono") }

    init {
        if (name == null && phone == null) throw InvalidStudent("Tenés que indicar al menos un dato para cambiar.")
    }
}

private fun requireName(value: String): String = requireText(value, "El nombre").also {
    if (it.length > PersonName.MAX_LENGTH) {
        throw InvalidStudent("El nombre puede tener hasta ${PersonName.MAX_LENGTH} caracteres.")
    }
}

private fun requireText(value: String, label: String): String =
    value.trim().ifEmpty { throw InvalidStudent("$label es obligatorio.") }
