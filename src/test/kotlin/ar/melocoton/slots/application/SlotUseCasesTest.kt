package ar.melocoton.slots.application

import ar.melocoton.bookings.application.port.BookingRepository
import ar.melocoton.slots.application.port.SlotRepository
import ar.melocoton.slots.domain.CapacityBelowOccupancy
import ar.melocoton.slots.domain.NewSlot
import ar.melocoton.slots.domain.Slot
import ar.melocoton.slots.domain.SlotChange
import ar.melocoton.slots.domain.SlotNotFound
import ar.melocoton.slots.domain.SlotOverlap
import ar.melocoton.slots.domain.SlotStatusFilter
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

class SlotUseCasesTest {

    // 15:00 UTC is 12:00 in Buenos Aires: Monday 16 November 2026.
    private val clock = Clock.fixed(Instant.parse("2026-11-16T15:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 11, 16)

    private val slots = mockk<SlotRepository>()
    private val bookings = mockk<BookingRepository>()

    private fun aSlot(id: Long = 1, start: String = "18:00", end: String = "20:00", capacity: Int = 8, active: Boolean = true) =
        Slot(id, DayOfWeek.TUESDAY, LocalTime.parse(start), LocalTime.parse(end), capacity, active)

    private fun stubLocks(target: Slot) {
        every { slots.lockSchedule() } just runs
        every { slots.lockById(target.id) } returns target
        every { slots.findActive() } returns listOf(target)
    }

    // ---- CreateSlot ----

    @Test
    fun `RF06_createChecksOverlapsAgainstTheLockedActiveSlotsAndStoresTheNewOne`() {
        val created = aSlot(id = 5, start = "20:00", end = "22:00")
        val stored: CapturingSlot<NewSlot> = slot()
        every { slots.lockSchedule() } just runs
        every { slots.findActive() } returns listOf(aSlot(id = 1, start = "18:00", end = "20:00"))
        every { slots.create(capture(stored)) } returns created

        val result = CreateSlot(slots).create(2, LocalTime.parse("20:00"), LocalTime.parse("22:00"), 8)

        assertThat(result).isSameAs(created)
        assertThat(stored.captured.startTime).isEqualTo(LocalTime.parse("20:00"))
        verifyOrder { slots.lockSchedule(); slots.findActive() }
    }

    @Test
    fun `DI25_createDoesNotStoreAnOverlappingSlot`() {
        every { slots.lockSchedule() } just runs
        every { slots.findActive() } returns listOf(aSlot(start = "18:00", end = "20:00"))

        assertThatThrownBy { CreateSlot(slots).create(2, LocalTime.parse("19:00"), LocalTime.parse("21:00"), 8) }
            .isInstanceOf(SlotOverlap::class.java)

        verify(exactly = 0) { slots.create(any()) }
    }

    // ---- ListSlots, GetSlot ----

    @Test
    fun `DI43_listShowsTheFutureLessonsOverCapacityOfEachSlot`() {
        val full = aSlot(id = 1, capacity = 2)
        val empty = aSlot(id = 2, capacity = 2)
        every { slots.findAll(SlotStatusFilter.ALL) } returns listOf(full, empty)
        every { bookings.occupancyByLessonDate(1, today) } returns mapOf(today.plusDays(1) to 3, today.plusDays(8) to 2)
        every { bookings.occupancyByLessonDate(2, today) } returns emptyMap()
        every { bookings.slotIdsWithBookings(listOf(1L, 2L)) } returns setOf(1L)

        val result = ListSlots(slots, bookings, clock).list(SlotStatusFilter.ALL)

        assertThat(result.map { it.slot.id }).containsExactly(1, 2)
        assertThat(result[0].overbookedLessons.map { it.lessonDate to it.occupancy }).containsExactly(today.plusDays(1) to 3)
        assertThat(result[1].overbookedLessons).isEmpty()
        // DI-63: one query for all the slots, not one per slot.
        assertThat(result.map { it.scheduleEditable }).containsExactly(false, true)
        verify(exactly = 1) { bookings.slotIdsWithBookings(any()) }
    }

    @Test
    fun `DI63_getReportsWhetherTheScheduleIsEditable`() {
        every { slots.findById(1) } returns aSlot(id = 1)
        every { bookings.occupancyByLessonDate(1, today) } returns emptyMap()
        every { bookings.existsForSlot(1) } returns false

        assertThat(GetSlot(slots, bookings, clock).get(1).scheduleEditable).isTrue()

        every { bookings.existsForSlot(1) } returns true

        assertThat(GetSlot(slots, bookings, clock).get(1).scheduleEditable).isFalse()
    }

    @Test
    fun `CU03_getOfAnUnknownSlotIsNotFound`() {
        every { slots.findById(99) } returns null

        assertThatThrownBy { GetSlot(slots, bookings, clock).get(99) }.isInstanceOf(SlotNotFound::class.java)
    }

    // ---- UpdateSlot ----

    @Test
    fun `DI45_updateLocksTheSlotsChecksTheCapacityAndSavesTheEdit`() {
        val target = aSlot(id = 1, capacity = 8)
        stubLocks(target)
        every { bookings.occupancyByLessonDate(1, today) } returns mapOf(today.plusDays(1) to 5)
        every { slots.save(target) } just runs
        every { bookings.existsForSlot(1) } returns true

        val result = UpdateSlot(slots, bookings, clock).update(1, SlotChange(null, null, null, 6))

        assertThat(result.slot.capacity).isEqualTo(6)
        assertThat(result.scheduleEditable).isFalse()
        verify { slots.save(target) }
        // Asked once, only for the response: the capacity alone does not need it (DI-63).
        verify(exactly = 1) { bookings.existsForSlot(1) }
    }

    @Test
    fun `CU03_E1_updateDoesNotSaveWhenTheCapacityIsBelowTheOccupancy`() {
        val target = aSlot(id = 1, capacity = 8)
        stubLocks(target)
        every { bookings.occupancyByLessonDate(1, today) } returns mapOf(today.plusDays(1) to 7)

        assertThatThrownBy { UpdateSlot(slots, bookings, clock).update(1, SlotChange(null, null, null, 4)) }
            .isInstanceOf(CapacityBelowOccupancy::class.java)

        verify(exactly = 0) { slots.save(any()) }
    }

    @Test
    fun `DI45_updateAsksWhetherTheSlotHasBookingsWhenTheScheduleChanges`() {
        val target = aSlot(id = 1)
        stubLocks(target)
        every { bookings.occupancyByLessonDate(1, today) } returns emptyMap()
        every { bookings.existsForSlot(1) } returns true

        assertThatThrownBy { UpdateSlot(slots, bookings, clock).update(1, SlotChange(null, LocalTime.parse("17:00"), null, null)) }
            .hasMessageContaining("reservas")

        verify(exactly = 0) { slots.save(any()) }
    }

    @Test
    fun `DI45_updateFindsAnInactiveSlotAndTheDomainRejectsIt`() {
        val inactive = aSlot(id = 3, active = false)
        every { slots.lockSchedule() } just runs
        every { slots.lockById(3) } returns inactive
        every { slots.findActive() } returns emptyList()
        every { bookings.occupancyByLessonDate(3, today) } returns emptyMap()

        assertThatThrownBy { UpdateSlot(slots, bookings, clock).update(3, SlotChange(null, null, null, 5)) }
            .hasMessageContaining("inactivo")
    }

    @Test
    fun `CU03_updateOfAnUnknownSlotIsNotFound`() {
        every { slots.lockSchedule() } just runs
        every { slots.lockById(99) } returns null

        assertThatThrownBy { UpdateSlot(slots, bookings, clock).update(99, SlotChange(null, null, null, 5)) }
            .isInstanceOf(SlotNotFound::class.java)
    }
}
