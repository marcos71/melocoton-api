package ar.melocoton.students.domain

import java.time.DayOfWeek
import java.time.LocalDate
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
    val fixedSlotId: Long?,
    val monthlyStartDate: LocalDate?,
) {
    init {
        when (enrollmentType) {
            EnrollmentType.MONTHLY -> require(monthlyStartDate != null) { "A monthly student needs a start date" }
            EnrollmentType.DROP_IN -> {
                require(monthlyStartDate == null) { "A drop-in student has no monthly start date" }
                require(fixedSlotId == null) { "A drop-in student has no fixed slot" }
            }
        }
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
         * @throws InvalidStudent a text is blank, or a drop-in student comes with a start date
         */
        fun create(
            name: String,
            email: String,
            phone: String,
            enrollmentType: EnrollmentType,
            monthlyStartDate: LocalDate?,
            today: LocalDate,
        ): NewStudent {
            val trimmedName = requireText(name, "El nombre")
            val trimmedEmail = requireText(email, "El email")
            val trimmedPhone = requireText(phone, "El teléfono")
            val startDate = when (enrollmentType) {
                EnrollmentType.MONTHLY -> monthlyStartDate ?: today
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
 * @throws InvalidStudent nothing to change, or a given text is blank
 */
class StudentChange(name: String?, phone: String?) {
    val name: String? = name?.let { requireText(it, "El nombre") }
    val phone: String? = phone?.let { requireText(it, "El teléfono") }

    init {
        if (name == null && phone == null) throw InvalidStudent("Tenés que indicar al menos un dato para cambiar.")
    }
}

/** The fixed slot of a student, as the screens show it (D-26). */
data class FixedSlotSummary(val id: Long, val dayOfWeek: DayOfWeek, val startTime: LocalTime, val endTime: LocalTime)

/** A row of the students list (CU-02, RF-05). */
data class StudentSummary(
    val id: Long,
    val name: String,
    val enrollmentType: EnrollmentType,
    val fixedSlot: FixedSlotSummary?,
    val active: Boolean,
)

/** A student's profile data (CU-02, DI-46). Upcoming classes and payment standing come with their iterations. */
data class StudentProfile(
    val id: Long,
    val name: String,
    val email: String,
    val phone: String,
    val active: Boolean,
    val enrollmentType: EnrollmentType,
    val monthlyStartDate: LocalDate?,
    val fixedSlot: FixedSlotSummary?,
)

private fun requireText(value: String, label: String): String =
    value.trim().ifEmpty { throw InvalidStudent("$label es obligatorio.") }
