package ar.melocoton.bookings.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class FixedBookingsTest {

    // Thursday 19 November 2026, 12:00.
    private val now = LocalDateTime.of(2026, 11, 19, 12, 0)
    private val windowEnd = LocalDate.of(2027, 1, 31)
    private val longAgo = LocalDate.of(2026, 3, 1)

    private fun date(text: String) = LocalDate.parse(text)

    private fun dates(
        day: DayOfWeek = DayOfWeek.TUESDAY,
        start: String = "18:00",
        monthlyStart: LocalDate = longAgo,
        at: LocalDateTime = now,
        existing: List<ExistingBooking> = emptyList(),
    ) = fixedBookingDates(day, LocalTime.parse(start), monthlyStart, at, windowEnd, existing)

    // ---- RollingWindow ----

    @Test
    fun `DI42_theWindowEndsOnTheLastDayOfTheMonthOfTodayPlusTheHorizon`() {
        assertThat(RollingWindow.endDate(date("2026-11-19"), 60)).isEqualTo(date("2027-01-31"))
    }

    @Test
    fun `DI42_theWindowEndHandlesMonthBoundaries`() {
        // 2026-12-01 + 60 days is 2027-01-30: still January.
        assertThat(RollingWindow.endDate(date("2026-12-01"), 60)).isEqualTo(date("2027-01-31"))
        // 2026-12-02 + 60 days is 2027-01-31: the last day of the month.
        assertThat(RollingWindow.endDate(date("2026-12-02"), 60)).isEqualTo(date("2027-01-31"))
        // 2026-12-03 + 60 days is 2027-02-01: February, a 28-day month.
        assertThat(RollingWindow.endDate(date("2026-12-03"), 60)).isEqualTo(date("2027-02-28"))
        // From December the window crosses into the next year.
        assertThat(RollingWindow.endDate(date("2026-12-31"), 1)).isEqualTo(date("2027-01-31"))
        // A leap February.
        assertThat(RollingWindow.endDate(date("2028-01-01"), 40)).isEqualTo(date("2028-02-29"))
    }

    // ---- fixedBookingDates ----

    @Test
    fun `RF08_generatesOneDatePerWeekdayFromTodayToTheWindowEnd`() {
        assertThat(dates()).containsExactly(
            date("2026-11-24"), date("2026-12-01"), date("2026-12-08"), date("2026-12-15"), date("2026-12-22"),
            date("2026-12-29"), date("2027-01-05"), date("2027-01-12"), date("2027-01-19"), date("2027-01-26"),
        )
    }

    @Test
    fun `RF08_theWindowEndDayItselfIsIncluded`() {
        // 2027-01-31 is a Sunday.
        assertThat(dates(day = DayOfWeek.SUNDAY)).endsWith(date("2027-01-31"))
    }

    @Test
    fun `RF08_nothingIsGeneratedBeforeTheMonthlyStartDate`() {
        assertThat(dates(monthlyStart = date("2026-12-10")).first()).isEqualTo(date("2026-12-15"))
    }

    @Test
    fun `RF08_aLessonTodayThatHasNotStartedIsGenerated`() {
        assertThat(dates(day = DayOfWeek.THURSDAY).first()).isEqualTo(date("2026-11-19"))
    }

    @Test
    fun `RF08_aLessonTodayThatHasStartedIsNotGenerated`() {
        assertThat(dates(day = DayOfWeek.THURSDAY, start = "12:00").first()).isEqualTo(date("2026-11-26"))
        assertThat(dates(day = DayOfWeek.THURSDAY, start = "09:00").first()).isEqualTo(date("2026-11-26"))
    }

    @Test
    fun `RF08_datesThatAlreadyHaveABookingInAnyStateAreSkipped`() {
        val existing = listOf(
            ExistingBooking(date("2026-12-01"), BookingStatus.SCHEDULED, null),
            ExistingBooking(date("2026-12-08"), BookingStatus.CANCELLED, CancellationReason.STUDENT),
            ExistingBooking(date("2026-12-15"), BookingStatus.ABSENT_RECOVERABLE, null),
            ExistingBooking(date("2026-12-22"), BookingStatus.CANCELLED, CancellationReason.TEACHER),
        )

        assertThat(dates(existing = existing)).doesNotContain(
            date("2026-12-01"), date("2026-12-08"), date("2026-12-15"), date("2026-12-22"),
        )
        assertThat(dates(existing = existing)).hasSize(6)
    }

    @Test
    fun `DI42_datesCancelledByAnEnrollmentChangeAreGeneratedAgain`() {
        val existing = listOf(
            ExistingBooking(date("2026-12-01"), BookingStatus.CANCELLED, CancellationReason.ENROLLMENT_CHANGE),
            ExistingBooking(date("2026-12-08"), BookingStatus.SCHEDULED, null),
        )

        val result = dates(existing = existing)

        assertThat(result).contains(date("2026-12-01")).doesNotContain(date("2026-12-08"))
    }

    // ---- lesson time ----

    @Test
    fun `RF09_aLessonHasStartedWhenItsStartIsNotAfterNow`() {
        val start = LocalTime.of(12, 0)

        assertThat(lessonHasStarted(date("2026-11-19"), start, now)).isTrue()
        assertThat(lessonHasStarted(date("2026-11-19"), LocalTime.of(12, 1), now)).isFalse()
        assertThat(lessonHasStarted(date("2026-11-18"), LocalTime.of(23, 0), now)).isTrue()
        assertThat(lessonHasStarted(date("2026-11-20"), LocalTime.of(0, 0), now)).isFalse()
    }
}
