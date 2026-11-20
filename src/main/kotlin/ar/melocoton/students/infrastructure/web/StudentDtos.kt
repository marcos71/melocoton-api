package ar.melocoton.students.infrastructure.web

import ar.melocoton.bookings.domain.BookingType
import ar.melocoton.shared.domain.PersonName
import ar.melocoton.shared.infrastructure.web.EMAIL_WITH_DOMAIN_DOT
import ar.melocoton.students.application.port.FixedSlotSummary
import ar.melocoton.students.application.port.StudentProfile
import ar.melocoton.students.application.port.StudentSummary
import ar.melocoton.students.application.port.UpcomingLesson
import ar.melocoton.students.domain.EnrollmentType
import com.fasterxml.jackson.annotation.JsonAnySetter
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Pattern
import java.time.LocalDate
import java.time.LocalTime

private const val TIME_EXAMPLE = "18:00"

/**
 * Body of POST /api/v1/students. Name, email, phone and enrollment type are required (DI-46). The text is
 * trimmed here; a blank or too long value is rejected by the domain with its own message.
 */
class CreateStudentRequest(
    name: String,
    email: String,
    phone: String,
    val enrollmentType: EnrollmentType,
    @field:Schema(
        type = "string",
        format = "date",
        example = "2026-11-02",
        description = "Only for MONTHLY, where it defaults to today; DROP_IN must not send it",
    )
    val monthlyStartDate: LocalDate? = null,
) {
    @field:Schema(maxLength = PersonName.MAX_LENGTH)
    val name: String = name.trim()

    // Required, blank and length rules live in the domain (NewStudent), so POST and PATCH answer the same.
    // The format stays here. @Email alone accepts "a@b"; the shared pattern asks for a dot in the domain,
    // as for assistant teachers. A blank value passes both on purpose: the domain rejects it as required.
    @field:Email
    @field:Pattern(regexp = "|$EMAIL_WITH_DOMAIN_DOT")
    val email: String = email.trim()

    val phone: String = phone.trim()
}

/**
 * Body of PATCH /api/v1/students/{id}: name and phone, at least one (DI-46). Any other field, the email
 * included, is rejected with 400 instead of being ignored.
 */
class UpdateStudentRequest(
    @field:Schema(maxLength = PersonName.MAX_LENGTH)
    val name: String? = null,
    val phone: String? = null,
) {
    /** Jackson calls this for every JSON field the class does not declare; failing here makes it a 400. */
    @JsonAnySetter
    @Schema(hidden = true)
    fun rejectUnknownField(field: String, value: Any?) {
        throw IllegalArgumentException("Field '$field' cannot be edited here")
    }
}

/** A student's fixed slot. */
data class FixedSlotResponse(
    val id: Long,
    @field:Schema(description = "ISO day of the week: 1 is Monday, 7 is Sunday")
    val dayOfWeek: Int,
    @field:Schema(type = "string", example = TIME_EXAMPLE, description = "HH:mm")
    val startTime: LocalTime,
    @field:Schema(type = "string", example = TIME_EXAMPLE, description = "HH:mm")
    val endTime: LocalTime,
)

/** A row of the students list. */
data class StudentItemResponse(
    val id: Long,
    val name: String,
    val enrollmentType: EnrollmentType,
    val fixedSlot: FixedSlotResponse?,
    val active: Boolean,
)

/** A student's profile data. */
data class StudentProfileResponse(
    val id: Long,
    val name: String,
    val email: String,
    val phone: String,
    val active: Boolean,
    val enrollmentType: EnrollmentType,
    @field:Schema(type = "string", format = "date", description = "Only for MONTHLY students")
    val monthlyStartDate: LocalDate?,
    val fixedSlot: FixedSlotResponse?,
    @field:Schema(description = "Scheduled classes that have not started, by date and start time")
    val upcomingLessons: List<UpcomingLessonResponse>,
)

/** A scheduled class of the student: a booking with its slot's day and time. */
data class UpcomingLessonResponse(
    val bookingId: Long,
    @field:Schema(type = "string", format = "date")
    val lessonDate: LocalDate,
    val slotId: Long,
    @field:Schema(description = "ISO day of the week: 1 is Monday, 7 is Sunday")
    val dayOfWeek: Int,
    @field:Schema(type = "string", example = TIME_EXAMPLE, description = "HH:mm")
    val startTime: LocalTime,
    @field:Schema(type = "string", example = TIME_EXAMPLE, description = "HH:mm")
    val endTime: LocalTime,
    val type: BookingType,
)

fun UpcomingLesson.toResponse() = UpcomingLessonResponse(
    bookingId = bookingId,
    lessonDate = lessonDate,
    slotId = slotId,
    dayOfWeek = dayOfWeek.value,
    startTime = startTime,
    endTime = endTime,
    type = type,
)

fun FixedSlotSummary.toResponse() =
    FixedSlotResponse(id = id, dayOfWeek = dayOfWeek.value, startTime = startTime, endTime = endTime)

fun StudentSummary.toResponse() = StudentItemResponse(
    id = id,
    name = name,
    enrollmentType = enrollmentType,
    fixedSlot = fixedSlot?.toResponse(),
    active = active,
)

fun StudentProfile.toResponse() = StudentProfileResponse(
    id = id,
    name = name,
    email = email,
    phone = phone,
    active = active,
    enrollmentType = enrollmentType,
    monthlyStartDate = monthlyStartDate,
    fixedSlot = fixedSlot?.toResponse(),
    upcomingLessons = upcomingLessons.map { it.toResponse() },
)
