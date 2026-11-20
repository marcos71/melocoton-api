package ar.melocoton.bookings.domain

import ar.melocoton.shared.domain.BusinessErrorKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class DropInRulesTest {

    // Thursday 19 November 2026, 12:00. The slot is on Tuesdays at 18:00.
    private val now = LocalDateTime.of(2026, 11, 19, 12, 0)
    private val start = LocalTime.of(18, 0)

    private fun validate(lessonDate: String, day: DayOfWeek = DayOfWeek.TUESDAY, at: LocalDateTime = now) =
        requireValidDropInDate(day, start, LocalDate.parse(lessonDate), at)

    private fun booking(
        studentId: Long = 5,
        type: BookingType = BookingType.DROP_IN,
        status: BookingStatus = BookingStatus.SCHEDULED,
        date: String = "2026-12-01",
    ) = Booking(10, studentId, 1, LocalDate.parse(date), type, status, null)

    // ---- the date of a drop-in booking ----

    @Test
    fun `RF09_aFutureLessonOfTheSlotIsValid`() {
        assertThatCode { validate("2026-11-24") }.doesNotThrowAnyException()
    }

    @Test
    fun `RF09_aDateOnAnotherWeekdayIsInvalid`() {
        assertThatThrownBy { validate("2026-11-25") }.isInstanceOfSatisfying(InvalidLessonDate::class.java) {
            assertThat(it.code).isEqualTo("invalid_lesson_date")
            assertThat(it.kind).isEqualTo(BusinessErrorKind.INVALID)
        }
    }

    @Test
    fun `DI44_theThreeMonthLimitIsInclusive`() {
        // Three months from 2026-11-19 is 2027-02-19, a Friday.
        assertThatCode { validate("2027-02-19", day = DayOfWeek.FRIDAY) }.doesNotThrowAnyException()
        assertThatThrownBy { validate("2027-02-26", day = DayOfWeek.FRIDAY) }
            .isInstanceOf(InvalidLessonDate::class.java)
    }

    @Test
    fun `DI44_theThreeMonthLimitFollowsTheCalendarMonthLength`() {
        // From 2026-11-30 the limit is 2027-02-28 (not 2027-03-02).
        val at = LocalDateTime.of(2026, 11, 30, 9, 0)
        assertThatCode { validate("2027-02-23", at = at) }.doesNotThrowAnyException()
        assertThatThrownBy { validate("2027-03-02", at = at) }.isInstanceOf(InvalidLessonDate::class.java)
    }

    @Test
    fun `RF09_aLessonThatHasStartedCannotBeBooked`() {
        // Tuesday 17 November 2026 was two days ago; today is a Thursday, so use a Thursday slot at 09:00.
        assertThatThrownBy { requireValidDropInDate(DayOfWeek.THURSDAY, LocalTime.of(9, 0), LocalDate.of(2026, 11, 19), now) }
            .isInstanceOfSatisfying(LessonAlreadyStarted::class.java) {
                assertThat(it.code).isEqualTo("lesson_already_started")
            }
        assertThatThrownBy { validate("2026-11-17") }.isInstanceOf(LessonAlreadyStarted::class.java)
    }

    @Test
    fun `RF09_aLessonTodayThatHasNotStartedCanBeBooked`() {
        assertThatCode { requireValidDropInDate(DayOfWeek.THURSDAY, LocalTime.of(18, 0), LocalDate.of(2026, 11, 19), now) }
            .doesNotThrowAnyException()
    }

    // ---- already booked ----

    @Test
    fun `DI41_anActiveBookingInTheLessonIsAlreadyBooked`() {
        val existing = listOf(ExistingBooking(LocalDate.of(2026, 12, 1), BookingStatus.SCHEDULED, null))

        assertThatThrownBy { requireNotAlreadyBooked(existing) }.isInstanceOfSatisfying(AlreadyBooked::class.java) {
            assertThat(it.code).isEqualTo("already_booked")
        }
    }

    @Test
    fun `DI41_aCancelledBookingDoesNotCountAsBooked`() {
        val existing = listOf(
            ExistingBooking(LocalDate.of(2026, 12, 1), BookingStatus.CANCELLED, CancellationReason.TEACHER),
        )

        assertThatCode { requireNotAlreadyBooked(existing) }.doesNotThrowAnyException()
        assertThatCode { requireNotAlreadyBooked(emptyList()) }.doesNotThrowAnyException()
    }

    // ---- cancelling a drop-in ----

    @Test
    fun `RF52_aScheduledDropInBeforeItStartsIsCancelledByTheTeacher`() {
        val booking = booking()

        booking.cancelDropIn(5, start, now)

        assertThat(booking.status).isEqualTo(BookingStatus.CANCELLED)
        assertThat(booking.cancellationReason).isEqualTo(CancellationReason.TEACHER)
    }

    @Test
    fun `RF52_aBookingOfAnotherStudentOrAnotherTypeIsNotFound`() {
        assertThatThrownBy { booking(studentId = 6).cancelDropIn(5, start, now) }
            .isInstanceOfSatisfying(BookingNotFound::class.java) { assertThat(it.code).isEqualTo("not_found") }
        assertThatThrownBy { booking(type = BookingType.FIXED).cancelDropIn(5, start, now) }
            .isInstanceOf(BookingNotFound::class.java)
        assertThatThrownBy { booking(type = BookingType.MAKEUP).cancelDropIn(5, start, now) }
            .isInstanceOf(BookingNotFound::class.java)
    }

    @Test
    fun `RF52_onlyAScheduledBookingCanBeCancelled`() {
        BookingStatus.entries.filter { it != BookingStatus.SCHEDULED }.forEach { status ->
            val booking = booking(status = status)

            assertThatThrownBy { booking.cancelDropIn(5, start, now) }
                .isInstanceOfSatisfying(BookingStatusConflict::class.java) { assertThat(it.code).isEqualTo("status_conflict") }
            assertThat(booking.status).isEqualTo(status)
        }
    }

    @Test
    fun `RF52_aLessonThatHasStartedCannotBeCancelled`() {
        val booking = booking(date = "2026-11-17")

        assertThatThrownBy { booking.cancelDropIn(5, start, now) }.isInstanceOf(LessonAlreadyStarted::class.java)
        assertThat(booking.status).isEqualTo(BookingStatus.SCHEDULED)
    }

    // ---- capacity ----

    @Test
    fun `RF11_aDropInNeedsAFreePlace`() {
        assertThatCode { requireFreePlace(capacity = 8, occupancy = 7) }.doesNotThrowAnyException()
        assertThatThrownBy { requireFreePlace(capacity = 8, occupancy = 8) }
            .isInstanceOfSatisfying(CapacityFull::class.java) {
                assertThat(it.code).isEqualTo("capacity_full")
                assertThat(it.message).isEqualTo("No queda lugar en esa clase.")
                assertThat(it.kind).isEqualTo(BusinessErrorKind.CONFLICT)
            }
    }

    @Test
    fun `DI43_anOverbookedLessonHasNoFreePlace`() {
        assertThatThrownBy { requireFreePlace(capacity = 8, occupancy = 10) }.isInstanceOf(CapacityFull::class.java)
    }
}
