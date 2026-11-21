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

class LessonTest {

    private val start = LocalTime.of(18, 0)
    private val end = LocalTime.of(20, 0)

    private fun lesson(capacity: Int = 8, occupancy: Int = 0, date: LocalDate = LocalDate.of(2026, 11, 24), now: LocalDateTime) =
        lessonOf(1, DayOfWeek.TUESDAY, start, end, capacity, date, occupancy, now)

    private val noon = LocalDateTime.of(2026, 11, 19, 12, 0)

    // ---- dates ----

    @Test
    fun `DI56_onlyDatesOnTheSlotWeekdayAreListedAndBothEdgesAreInclusive`() {
        // 2026-11-17 and 2026-12-01 are Tuesdays.
        val dates = lessonDatesInRange(DayOfWeek.TUESDAY, LocalDate.of(2026, 11, 17), LocalDate.of(2026, 12, 1))

        assertThat(dates).containsExactly(LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 24), LocalDate.of(2026, 12, 1))
    }

    @Test
    fun `DI56_aRangeJustInsideTheWeekdayEdgesLeavesThemOut`() {
        val dates = lessonDatesInRange(DayOfWeek.TUESDAY, LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 30))

        assertThat(dates).containsExactly(LocalDate.of(2026, 11, 24))
    }

    @Test
    fun `DI56_aSingleDayRangeOnTheWeekdayGivesOneDateAndOtherwiseNone`() {
        assertThat(lessonDatesInRange(DayOfWeek.TUESDAY, LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 24))).hasSize(1)
        assertThat(lessonDatesInRange(DayOfWeek.TUESDAY, LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 25))).isEmpty()
    }

    // ---- range ----

    @Test
    fun `DI56_aRangeOfExactlyThreeMonthsIsValidAndOneDayMoreIsNot`() {
        val from = LocalDate.of(2026, 11, 19)

        assertThatCode { requireValidLessonRange(from, from) }.doesNotThrowAnyException()
        assertThatCode { requireValidLessonRange(from, LocalDate.of(2027, 2, 19)) }.doesNotThrowAnyException()
        assertThatThrownBy { requireValidLessonRange(from, LocalDate.of(2027, 2, 20)) }
            .isInstanceOfSatisfying(InvalidDateRange::class.java) {
                assertThat(it.code).isEqualTo("invalid_date_range")
                assertThat(it.kind).isEqualTo(BusinessErrorKind.INVALID)
            }
    }

    @Test
    fun `DI56_anInvertedRangeIsInvalid`() {
        assertThatThrownBy { requireValidLessonRange(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 19)) }
            .isInstanceOf(InvalidDateRange::class.java)
    }

    // ---- derived fields ----

    @Test
    fun `DI56_freeSpotsIsCapacityMinusOccupancy`() {
        val lesson = lesson(capacity = 8, occupancy = 5, now = noon)

        assertThat(lesson.freeSpots).isEqualTo(3)
        assertThat(lesson.overbooked).isFalse()
    }

    @Test
    fun `DI56_aFullLessonHasNoFreeSpotsAndIsNotOverbooked`() {
        val lesson = lesson(capacity = 8, occupancy = 8, now = noon)

        assertThat(lesson.freeSpots).isZero()
        assertThat(lesson.overbooked).isFalse()
    }

    @Test
    fun `DI43_anOverbookedLessonNeverHasNegativeFreeSpots`() {
        val lesson = lesson(capacity = 8, occupancy = 10, now = noon)

        assertThat(lesson.freeSpots).isZero()
        assertThat(lesson.overbooked).isTrue()
        assertThat(lesson.occupancy).isEqualTo(10)
    }

    @Test
    fun `DI56_aLessonIsStartedFromItsStartTimeOnTheSameDay`() {
        val date = LocalDate.of(2026, 11, 24)

        assertThat(lesson(date = date, now = LocalDateTime.of(date, LocalTime.of(17, 59))).started).isFalse()
        assertThat(lesson(date = date, now = LocalDateTime.of(date, start)).started).isTrue()
        assertThat(lesson(date = date, now = LocalDateTime.of(date, LocalTime.of(18, 1))).started).isTrue()
    }

    @Test
    fun `DI56_lessonsOfASlotTakeTheOccupancyOfEachDateAndZeroWhenAbsent`() {
        val lessons = lessonsOfSlot(
            1, DayOfWeek.TUESDAY, start, end, 4,
            LocalDate.of(2026, 11, 17), LocalDate.of(2026, 12, 1),
            mapOf(LocalDate.of(2026, 11, 24) to 3), noon,
        )

        assertThat(lessons.map { it.lessonDate to it.occupancy }).containsExactly(
            LocalDate.of(2026, 11, 17) to 0,
            LocalDate.of(2026, 11, 24) to 3,
            LocalDate.of(2026, 12, 1) to 0,
        )
        assertThat(lessons.map { it.started }).containsExactly(true, false, false)
        assertThat(lessons.map { it.freeSpots }).containsExactly(4, 1, 4)
    }
}
