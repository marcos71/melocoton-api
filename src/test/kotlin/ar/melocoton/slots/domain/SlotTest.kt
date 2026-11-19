package ar.melocoton.slots.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class SlotTest {

    // Monday 16 November 2026, 12:00.
    private val now = LocalDateTime.of(2026, 11, 16, 12, 0)
    private val today = now.toLocalDate()

    private fun time(text: String) = LocalTime.parse(text)

    private fun slot(
        id: Long = 1,
        day: DayOfWeek = DayOfWeek.TUESDAY,
        start: String = "18:00",
        end: String = "20:00",
        capacity: Int = 8,
        active: Boolean = true,
    ) = Slot(id, day, time(start), time(end), capacity, active)

    private fun create(day: Int, start: String, end: String, capacity: Int = 8, active: List<Slot> = emptyList()) =
        NewSlot.create(day, time(start), time(end), capacity, active)

    private fun update(
        slot: Slot,
        change: SlotChange,
        active: List<Slot> = listOf(slot),
        hasBookings: Boolean = false,
        occupancy: Map<LocalDate, Int> = emptyMap(),
    ) = slot.update(change, active, { hasBookings }, occupancy, now)

    private fun change(day: Int? = null, start: String? = null, end: String? = null, capacity: Int? = null) =
        SlotChange(day, start?.let(::time), end?.let(::time), capacity)

    // ---- RF-06: creating ----

    @Test
    fun `RF06_aValidSlotIsCreated`() {
        val new = create(2, "18:00", "20:00", 8)

        assertThat(new.dayOfWeek).isEqualTo(DayOfWeek.TUESDAY)
        assertThat(new.startTime).isEqualTo(time("18:00"))
        assertThat(new.endTime).isEqualTo(time("20:00"))
        assertThat(new.capacity).isEqualTo(8)
    }

    @Test
    fun `RF06_aDayOutsideOneToSevenIsInvalid`() {
        assertThatThrownBy { create(0, "18:00", "20:00") }.isInstanceOf(InvalidSlot::class.java)
        assertThatThrownBy { create(8, "18:00", "20:00") }.isInstanceOf(InvalidSlot::class.java)
    }

    @Test
    fun `RF06_aStartThatIsNotBeforeTheEndIsInvalid`() {
        assertThatThrownBy { create(2, "20:00", "20:00") }.isInstanceOf(InvalidSlot::class.java)
        assertThatThrownBy { create(2, "21:00", "20:00") }.isInstanceOf(InvalidSlot::class.java)
    }

    @Test
    fun `RF06_aCapacityOfZeroOrLessIsInvalid`() {
        assertThatThrownBy { create(2, "18:00", "20:00", 0) }.isInstanceOf(InvalidSlot::class.java)
        assertThatThrownBy { create(2, "18:00", "20:00", -1) }.isInstanceOf(InvalidSlot::class.java)
    }

    // ---- DI-25: overlap ----

    @Test
    fun `DI25_aSlotOverlappingAnActiveOneOfTheSameDayIsRejectedWithTheConflictingSlot`() {
        val existing = slot(id = 7, day = DayOfWeek.TUESDAY, start = "18:00", end = "20:00")

        val error = catchBusinessError { create(2, "19:00", "21:00", active = listOf(existing)) }

        assertThat(error).isInstanceOf(SlotOverlap::class.java)
        assertThat(error.code).isEqualTo("slot_overlap")
        assertThat(error.message).isEqualTo("Se superpone con el turno del martes de 18:00 a 20:00.")
        assertThat(error.details).isEqualTo(SlotOverlapDetails(7, 2, time("18:00"), time("20:00")))
    }

    @Test
    fun `DI25_aSlotContainedInAnotherOverlaps`() {
        val existing = slot(start = "18:00", end = "22:00")

        assertThatThrownBy { create(2, "19:00", "20:00", active = listOf(existing)) }.isInstanceOf(SlotOverlap::class.java)
    }

    @Test
    fun `DI25_touchingSlotsDoNotOverlap`() {
        val existing = slot(start = "18:00", end = "20:00")

        create(2, "20:00", "22:00", active = listOf(existing))
        create(2, "16:00", "18:00", active = listOf(existing))
    }

    @Test
    fun `DI25_slotsOfOtherDaysDoNotOverlap`() {
        create(3, "18:00", "20:00", active = listOf(slot(day = DayOfWeek.TUESDAY)))
    }

    @Test
    fun `DI25_anInactiveSlotDoesNotCauseAnOverlap`() {
        create(2, "18:00", "20:00", active = listOf(slot(active = false)))
    }

    // ---- DI-45: editing ----

    @Test
    fun `CU03_aSlotWithNoBookingsChangesDayAndTime`() {
        val slot = slot()

        update(slot, change(day = 4, start = "10:00", end = "12:00"))

        assertThat(slot.dayOfWeek).isEqualTo(DayOfWeek.THURSDAY)
        assertThat(slot.startTime).isEqualTo(time("10:00"))
        assertThat(slot.endTime).isEqualTo(time("12:00"))
    }

    @Test
    fun `DI45_anEditThatTouchesNothingIsInvalid`() {
        assertThatThrownBy { change() }.isInstanceOf(InvalidSlot::class.java)
    }

    @Test
    fun `DI45_theMergedStartMustBeBeforeTheMergedEnd`() {
        val slot = slot(start = "18:00", end = "20:00")

        assertThatThrownBy { update(slot, change(start = "20:30")) }.isInstanceOf(InvalidSlot::class.java)
        assertThatThrownBy { update(slot, change(end = "17:00")) }.isInstanceOf(InvalidSlot::class.java)
        assertThat(slot.startTime).isEqualTo(time("18:00"))
    }

    @Test
    fun `DI45_anInactiveSlotCannotBeEdited`() {
        val slot = slot(active = false)

        val error = catchBusinessError { update(slot, change(capacity = 5)) }

        assertThat(error).isInstanceOf(SlotInactive::class.java)
        assertThat(error.code).isEqualTo("status_conflict")
    }

    @Test
    fun `DI45_dayOrTimeCannotChangeWhenTheSlotHasBookings`() {
        val slot = slot()

        val error = catchBusinessError { update(slot, change(start = "17:00"), hasBookings = true) }

        assertThat(error).isInstanceOf(SlotHasBookings::class.java)
        assertThat(error.code).isEqualTo("slot_has_bookings")
        assertThat(slot.startTime).isEqualTo(time("18:00"))
    }

    @Test
    fun `DI45_theCapacityCanChangeWhenTheSlotHasBookings`() {
        val slot = slot(capacity = 8)

        update(slot, change(capacity = 10), hasBookings = true)

        assertThat(slot.capacity).isEqualTo(10)
    }

    @Test
    fun `DI45_repeatingTheCurrentDayAndTimeIsNotAScheduleChange`() {
        val slot = slot()

        update(slot, change(day = 2, start = "18:00", end = "20:00", capacity = 6), hasBookings = true)

        assertThat(slot.capacity).isEqualTo(6)
    }

    @Test
    fun `DI45_hasBookingsIsNotAskedWhenOnlyTheCapacityChanges`() {
        val slot = slot()

        slot.update(change(capacity = 5), listOf(slot), { error("must not be asked") }, emptyMap(), now)

        assertThat(slot.capacity).isEqualTo(5)
    }

    @Test
    fun `DI25_movingASlotIgnoresItselfButNotTheOthers`() {
        val slot = slot(id = 1, start = "18:00", end = "20:00")
        val other = slot(id = 2, start = "20:00", end = "22:00")
        val active = listOf(slot, other)

        // Growing into its own old range is fine.
        update(slot, change(start = "17:00", end = "20:00"), active)
        assertThat(slot.startTime).isEqualTo(time("17:00"))

        // Reaching into the other slot is not.
        val error = catchBusinessError { update(slot, change(end = "21:00"), active) }
        assertThat(error).isInstanceOf(SlotOverlap::class.java)
        assertThat((error.details as SlotOverlapDetails).slotId).isEqualTo(2)
    }

    @Test
    fun `DI25_movingASlotToTouchAnotherIsAllowed`() {
        val slot = slot(id = 1, start = "18:00", end = "20:00")
        val other = slot(id = 2, start = "20:00", end = "22:00")

        update(slot, change(start = "16:00", end = "20:00"), listOf(slot, other))

        assertThat(slot.startTime).isEqualTo(time("16:00"))
    }

    @Test
    fun `DI25_movingASlotToAnotherDayChecksThatDay`() {
        val slot = slot(id = 1, day = DayOfWeek.TUESDAY)
        val wednesday = slot(id = 2, day = DayOfWeek.WEDNESDAY, start = "19:00", end = "21:00")

        assertThatThrownBy { update(slot, change(day = 3), listOf(slot, wednesday)) }.isInstanceOf(SlotOverlap::class.java)
    }

    // ---- CU-03 E1, E3: capacity vs future lessons ----

    @Test
    fun `CU03_E1_theCapacityCannotGoBelowTheOccupancyOfFutureLessonsAndEveryOffenderIsListed`() {
        val slot = slot(capacity = 10)
        val occupancy = mapOf(
            today.plusDays(1) to 9,
            today.plusDays(8) to 7,
            today.plusDays(15) to 4,
        )

        val error = catchBusinessError { update(slot, change(capacity = 6), occupancy = occupancy) }

        assertThat(error).isInstanceOf(CapacityBelowOccupancy::class.java)
        assertThat(error.code).isEqualTo("capacity_below_occupancy")
        assertThat(error.details).isEqualTo(
            CapacityBelowOccupancyDetails(
                listOf(
                    LessonOverCapacity(today.plusDays(1), occupancy = 9, spotsToFree = 3),
                    LessonOverCapacity(today.plusDays(8), occupancy = 7, spotsToFree = 1),
                ),
            ),
        )
        assertThat(slot.capacity).isEqualTo(10)
    }

    @Test
    fun `CU03_theCapacityCanEqualTheHighestFutureOccupancy`() {
        val slot = slot(capacity = 10)

        update(slot, change(capacity = 9), occupancy = mapOf(today.plusDays(1) to 9))

        assertThat(slot.capacity).isEqualTo(9)
    }

    @Test
    fun `CU03_E3_pastLessonsAreNeverConsidered`() {
        val slot = slot(capacity = 10)

        update(slot, change(capacity = 2), occupancy = mapOf(today.minusDays(7) to 9, today.minusDays(1) to 9))

        assertThat(slot.capacity).isEqualTo(2)
    }

    @Test
    fun `CU03_aLessonTodayThatAlreadyStartedIsNotFuture`() {
        // The slot starts at 11:00 and it is 12:00: today's lesson has started.
        val started = slot(start = "11:00", end = "13:00", capacity = 10)
        update(started, change(capacity = 2), occupancy = mapOf(today to 9))
        assertThat(started.capacity).isEqualTo(2)

        // Starting at 18:00, today's lesson has not started yet and counts.
        val ahead = slot(start = "18:00", end = "20:00", capacity = 10)
        assertThatThrownBy { update(ahead, change(capacity = 2), occupancy = mapOf(today to 9)) }
            .isInstanceOf(CapacityBelowOccupancy::class.java)
    }

    @Test
    fun `CU03_aLessonStartingExactlyNowHasStarted`() {
        val slot = slot(start = "12:00", end = "14:00", capacity = 10)

        update(slot, change(capacity = 2), occupancy = mapOf(today to 9))

        assertThat(slot.capacity).isEqualTo(2)
    }

    // ---- DI-43: overbooked lessons ----

    @Test
    fun `DI43_overbookedLessonsAreTheFutureOnesAboveCapacityByDate`() {
        val slot = slot(capacity = 8)
        val occupancy = mapOf(
            today.plusDays(15) to 10,
            today.plusDays(8) to 8,
            today.plusDays(1) to 9,
            today.minusDays(7) to 12,
        )

        assertThat(slot.overbookedLessons(occupancy, now)).containsExactly(
            LessonOccupancy(today.plusDays(1), 9),
            LessonOccupancy(today.plusDays(15), 10),
        )
    }

    private fun catchBusinessError(block: () -> Unit): ar.melocoton.shared.domain.BusinessRuleViolation =
        org.assertj.core.api.Assertions.catchThrowableOfType(ar.melocoton.shared.domain.BusinessRuleViolation::class.java) { block() }
}
