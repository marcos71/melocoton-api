package ar.melocoton.bookings.application

import ar.melocoton.bookings.application.port.BookingRepository
import ar.melocoton.bookings.domain.LessonKey
import ar.melocoton.slots.application.port.SlotRepository
import ar.melocoton.slots.domain.Slot
import ar.melocoton.slots.domain.SlotStatusFilter
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

class ListLessonsTest {

    private val slots = mockk<SlotRepository>()
    private val bookings = mockk<BookingRepository>()
    private val clock = Clock.fixed(Instant.parse("2026-11-19T15:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `DI56_theOccupancyOfAllTheSlotsAndDatesIsOneAggregateQuery`() {
        val tuesday = Slot(1, DayOfWeek.TUESDAY, LocalTime.of(18, 0), LocalTime.of(20, 0), 4, true)
        val wednesday = Slot(2, DayOfWeek.WEDNESDAY, LocalTime.of(10, 0), LocalTime.of(12, 0), 4, true)
        every { slots.findAll(SlotStatusFilter.ACTIVE) } returns listOf(tuesday, wednesday)
        every { bookings.occupancyInRange(listOf(1L, 2L), any(), any()) } returns
            mapOf(LessonKey(1, LocalDate.of(2026, 11, 24)) to 3)

        val lessons = ListLessons(slots, bookings, clock).list(LocalDate.of(2026, 11, 23), LocalDate.of(2026, 12, 6), null)

        assertThat(lessons.map { it.slotId to it.lessonDate }).containsExactly(
            1L to LocalDate.of(2026, 11, 24),
            2L to LocalDate.of(2026, 11, 25),
            1L to LocalDate.of(2026, 12, 1),
            2L to LocalDate.of(2026, 12, 2),
        )
        assertThat(lessons.map { it.occupancy }).containsExactly(3, 0, 0, 0)
        verify(exactly = 1) { bookings.occupancyInRange(any(), any(), any()) }
    }
}
