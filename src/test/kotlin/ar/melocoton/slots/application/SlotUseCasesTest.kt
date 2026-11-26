package ar.melocoton.slots.application

import ar.melocoton.bookings.application.port.BookingRepository
import ar.melocoton.slots.application.port.SlotRepository
import ar.melocoton.slots.domain.NewSlot
import ar.melocoton.slots.domain.Slot
import ar.melocoton.slots.domain.SlotChange
import ar.melocoton.slots.domain.SlotNotFound
import ar.melocoton.slots.domain.SlotOverlap
import ar.melocoton.slots.domain.SlotStatusFilter
import ar.melocoton.students.application.port.StudentRepository
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
    private val students = mockk<StudentRepository>()

    private fun aSlot(id: Long = 1, start: String = "18:00", end: String = "20:00", active: Boolean = true) =
        Slot(id, DayOfWeek.TUESDAY, LocalTime.parse(start), LocalTime.parse(end), active)

    private fun stubLocks(target: Slot) {
        every { slots.lockSchedule() } just runs
        every { slots.lockById(target.id) } returns target
        every { slots.findActive() } returns listOf(target)
        every { students.countFixedStudents(target.id) } returns 0
    }

    // ---- CreateSlot ----

    @Test
    fun `RF06_createChecksOverlapsAgainstTheLockedActiveSlotsAndStoresTheNewOne`() {
        val created = aSlot(id = 5, start = "20:00", end = "22:00")
        val stored: CapturingSlot<NewSlot> = slot()
        every { slots.lockSchedule() } just runs
        every { slots.findActive() } returns listOf(aSlot(id = 1, start = "18:00", end = "20:00"))
        every { slots.create(capture(stored)) } returns created

        val result = CreateSlot(slots).create(2, LocalTime.parse("20:00"), LocalTime.parse("22:00"))

        assertThat(result).isSameAs(created)
        assertThat(stored.captured.startTime).isEqualTo(LocalTime.parse("20:00"))
        verifyOrder { slots.lockSchedule(); slots.findActive() }
    }

    @Test
    fun `DI25_createDoesNotStoreAnOverlappingSlot`() {
        every { slots.lockSchedule() } just runs
        every { slots.findActive() } returns listOf(aSlot(start = "18:00", end = "20:00"))

        assertThatThrownBy { CreateSlot(slots).create(2, LocalTime.parse("19:00"), LocalTime.parse("21:00")) }
            .isInstanceOf(SlotOverlap::class.java)

        verify(exactly = 0) { slots.create(any()) }
    }

    // ---- ListSlots, GetSlot ----

    @Test
    fun `DI43_listShowsTheFutureLessonsOverCapacityOfEachSlot`() {
        val full = aSlot(id = 1)
        val empty = aSlot(id = 2)
        every { slots.findAll(SlotStatusFilter.ALL) } returns listOf(full, empty)
        // Slot 2 has no occupying booking, so it is absent from the map.
        every { bookings.occupancyByLessonDate(listOf(1L, 2L), today) } returns
            mapOf(1L to mapOf(today.plusDays(1) to 13, today.plusDays(8) to 12))
        every { bookings.slotIdsWithBookings(listOf(1L, 2L)) } returns setOf(1L)
        every { students.countFixedStudentsBySlot(listOf(1L, 2L)) } returns mapOf(1L to 2)

        val result = ListSlots(slots, bookings, students, clock).list(SlotStatusFilter.ALL)

        assertThat(result.map { it.slot.id }).containsExactly(1, 2)
        assertThat(result[0].overbookedLessons.map { it.lessonDate to it.occupancy }).containsExactly(today.plusDays(1) to 13)
        assertThat(result[1].overbookedLessons).isEmpty()
        // DI-63: one query for all the slots, not one per slot.
        assertThat(result.map { it.scheduleEditable }).containsExactly(false, true)
        verify(exactly = 1) { bookings.slotIdsWithBookings(any()) }
        verify(exactly = 1) { bookings.occupancyByLessonDate(listOf(1L, 2L), today) }
        verify(exactly = 0) { bookings.occupancyByLessonDate(any<Long>(), any()) }
        // DI-66: the fixed students of every slot in one query; a slot without any is 0.
        assertThat(result.map { it.fixedStudents }).containsExactly(2, 0)
        verify(exactly = 1) { students.countFixedStudentsBySlot(any()) }
    }

    @Test
    fun `DI63_getReportsWhetherTheScheduleIsEditable`() {
        every { slots.findById(1) } returns aSlot(id = 1)
        every { bookings.occupancyByLessonDate(1, today) } returns emptyMap()
        every { bookings.existsForSlot(1) } returns false
        every { students.countFixedStudents(1) } returns 3

        assertThat(GetSlot(slots, bookings, students, clock).get(1).scheduleEditable).isTrue()
        assertThat(GetSlot(slots, bookings, students, clock).get(1).fixedStudents).isEqualTo(3)

        every { bookings.existsForSlot(1) } returns true

        assertThat(GetSlot(slots, bookings, students, clock).get(1).scheduleEditable).isFalse()
    }

    @Test
    fun `CU03_getOfAnUnknownSlotIsNotFound`() {
        every { slots.findById(99) } returns null

        assertThatThrownBy { GetSlot(slots, bookings, students, clock).get(99) }.isInstanceOf(SlotNotFound::class.java)
    }

    // ---- UpdateSlot ----

    @Test
    fun `DI45_updateLocksTheSlotsChangesTheScheduleAndSavesTheEdit`() {
        val target = aSlot(id = 1)
        stubLocks(target)
        every { bookings.occupancyByLessonDate(1, today) } returns mapOf(today.plusDays(1) to 5)
        every { slots.save(target) } just runs
        every { bookings.existsForSlot(1) } returns false

        val result = UpdateSlot(slots, bookings, students, clock).update(1, SlotChange(null, LocalTime.parse("17:00"), null))

        assertThat(result.slot.startTime).isEqualTo(LocalTime.parse("17:00"))
        assertThat(result.scheduleEditable).isTrue()
        verify { slots.save(target) }
        // Asked once, for the schedule check and for the response (DI-63).
        verify(exactly = 1) { bookings.existsForSlot(1) }
        verifyOrder { slots.lockSchedule(); slots.lockById(1) }
    }

    @Test
    fun `DI45_updateAsksWhetherTheSlotHasBookingsWhenTheScheduleChanges`() {
        val target = aSlot(id = 1)
        stubLocks(target)
        every { bookings.occupancyByLessonDate(1, today) } returns emptyMap()
        every { bookings.existsForSlot(1) } returns true

        assertThatThrownBy { UpdateSlot(slots, bookings, students, clock).update(1, SlotChange(null, LocalTime.parse("17:00"), null)) }
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

        assertThatThrownBy { UpdateSlot(slots, bookings, students, clock).update(3, SlotChange(null, LocalTime.parse("17:00"), null)) }
            .hasMessageContaining("inactivo")
    }

    @Test
    fun `CU03_updateOfAnUnknownSlotIsNotFound`() {
        every { slots.lockSchedule() } just runs
        every { slots.lockById(99) } returns null

        assertThatThrownBy { UpdateSlot(slots, bookings, students, clock).update(99, SlotChange(null, LocalTime.parse("17:00"), null)) }
            .isInstanceOf(SlotNotFound::class.java)
    }
}
