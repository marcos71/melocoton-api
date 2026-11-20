package ar.melocoton.students.application.port

import ar.melocoton.bookings.domain.BookingType
import ar.melocoton.students.domain.EnrollmentType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

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
    /** Scheduled classes that have not started, by date and start time (DI-46). */
    val upcomingLessons: List<UpcomingLesson> = emptyList(),
)

/** A scheduled class of a student (DI-46): a booking with its slot's day and time. */
data class UpcomingLesson(
    val bookingId: Long,
    val lessonDate: LocalDate,
    val slotId: Long,
    val dayOfWeek: DayOfWeek,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val type: BookingType,
)
