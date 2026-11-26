package ar.melocoton.bookings.application

import ar.melocoton.bookings.application.port.BookingRepository
import ar.melocoton.bookings.domain.Booking
import ar.melocoton.bookings.domain.BookingStatus
import ar.melocoton.bookings.domain.BookingType
import ar.melocoton.bookings.domain.CancellationReason
import ar.melocoton.bookings.domain.CapacityFull
import ar.melocoton.settings.application.port.SettingsRepository
import ar.melocoton.settings.domain.Settings
import ar.melocoton.slots.application.port.SlotRepository
import ar.melocoton.slots.domain.Slot
import ar.melocoton.slots.domain.SlotNotFound
import ar.melocoton.students.application.port.StudentRepository
import ar.melocoton.students.domain.EnrollmentType
import ar.melocoton.students.domain.Student
import ar.melocoton.students.domain.StudentNotFound
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
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

/**
 * The use cases coordinate ports and the domain: what they load, in which lock order and what they
 * save. The rules themselves are tested in the domain; the database behavior is in the endpoint and
 * concurrency tests.
 */
class BookingUseCasesTest {

    // Thursday 2026-11-19, 12:00 in Buenos Aires.
    private val clock = Clock.fixed(Instant.parse("2026-11-19T15:00:00Z"), ZoneOffset.UTC)

    private val students = mockk<StudentRepository>()
    private val slots = mockk<SlotRepository>()
    private val bookings = mockk<BookingRepository>(relaxed = true)
    private val settings = mockk<SettingsRepository> { every { get() } returns Settings(makeupHorizonDays = 60) }

    private fun slot(id: Long, active: Boolean = true) =
        Slot(id, DayOfWeek.TUESDAY, LocalTime.of(18, 0), LocalTime.of(20, 0), active)

    private fun monthly(id: Long, slotId: Long? = null) =
        Student(id, EnrollmentType.MONTHLY, slotId, LocalDate.of(2026, 3, 1))

    private fun dropIn(id: Long) = Student(id, EnrollmentType.DROP_IN, null, null)

    // ---- AssignFixedSlot ----

    @Test
    fun `DI27_replacingTheSlotLocksTheStudentThenBothSlotsInAscendingIdOrder`() {
        every { students.lockById(1) } returns monthly(1, slotId = 9)
        every { slots.lockById(9) } returns slot(9)
        every { slots.lockById(4) } returns slot(4)
        every { students.saveFixedSlot(any()) } just runs
        every { students.countFixedStudents(any(), any()) } returns 0
        every { bookings.existingStudentBookings(any(), any(), any(), any()) } returns emptyList()
        every { bookings.occupancyByLessonDate(any<Long>(), any()) } returns emptyMap()
        val useCase = AssignFixedSlot(students, slots, bookings, settings, clock)

        useCase.assign(1, 4)

        verifyOrder {
            students.lockById(1)
            slots.lockById(4)
            slots.lockById(9)
        }
        // From today on: it is 12:00 and the slot starts at 18:00, so no lesson of it has started that day.
        verify(exactly = 1) {
            bookings.cancelScheduledFixed(1, 9, LocalDate.of(2026, 11, 19), CancellationReason.ENROLLMENT_CHANGE)
        }
        verify(exactly = 1) { students.saveFixedSlot(match { it.fixedSlotId == 4L }) }
        // Ten Tuesdays up to 2027-01-26: the window ends on 2027-01-31.
        verify(exactly = 1) { bookings.insertFixedIfAbsent(1, 4, match { it.size == 10 && it.last() == LocalDate.of(2027, 1, 26) }) }
    }

    @Test
    fun `RF08_assigningTheSlotTheStudentAlreadyHasCancelsNothing`() {
        every { students.lockById(1) } returns monthly(1, slotId = 4)
        every { slots.lockById(4) } returns slot(4)
        every { students.saveFixedSlot(any()) } just runs
        every { students.countFixedStudents(any(), any()) } returns 0
        every { bookings.existingStudentBookings(any(), any(), any(), any()) } returns emptyList()
        every { bookings.occupancyByLessonDate(any<Long>(), any()) } returns emptyMap()

        AssignFixedSlot(students, slots, bookings, settings, clock).assign(1, 4)

        verify(exactly = 0) { bookings.cancelScheduledFixed(any(), any(), any(), any()) }
        verify(exactly = 1) { slots.lockById(4) }
    }

    @Test
    fun `DI66_aFullSlotRejectsTheAssignmentAndChangesNothing`() {
        every { students.lockById(1) } returns monthly(1, slotId = 9)
        every { slots.lockById(9) } returns slot(9)
        every { slots.lockById(4) } returns slot(4)
        every { students.countFixedStudents(4, excluding = 1) } returns 12

        assertThatThrownBy { AssignFixedSlot(students, slots, bookings, settings, clock).assign(1, 4) }
            .isInstanceOf(ar.melocoton.slots.domain.FixedStudentsFull::class.java)

        verify(exactly = 0) { students.saveFixedSlot(any()) }
        verify(exactly = 0) { bookings.cancelScheduledFixed(any(), any(), any(), any()) }
        verify(exactly = 0) { bookings.insertFixedIfAbsent(any(), any(), any()) }
    }

    @Test
    fun `DI66_theCountIsReadAfterTheSlotLockAndLeavesOutTheStudentBeingAssigned`() {
        every { students.lockById(1) } returns monthly(1, slotId = 4)
        every { slots.lockById(4) } returns slot(4)
        every { students.countFixedStudents(4, excluding = 1) } returns 11
        every { students.saveFixedSlot(any()) } just runs
        every { bookings.existingStudentBookings(any(), any(), any(), any()) } returns emptyList()
        every { bookings.occupancyByLessonDate(any<Long>(), any()) } returns emptyMap()

        AssignFixedSlot(students, slots, bookings, settings, clock).assign(1, 4)

        verifyOrder { slots.lockById(4); students.countFixedStudents(4, excluding = 1) }
    }

    @Test
    fun `RF08_aRejectedAssignmentChangesNothing`() {
        val useCase = AssignFixedSlot(students, slots, bookings, settings, clock)

        every { students.lockById(1) } returns null
        assertThatThrownBy { useCase.assign(1, 4) }.isInstanceOf(StudentNotFound::class.java)

        every { students.lockById(2) } returns dropIn(2)
        assertThatThrownBy { useCase.assign(2, 4) }.isInstanceOf(ar.melocoton.students.domain.EnrollmentTypeMismatch::class.java)

        every { students.lockById(3) } returns monthly(3)
        every { slots.lockById(4) } returns null
        assertThatThrownBy { useCase.assign(3, 4) }.isInstanceOf(SlotNotFound::class.java)

        every { slots.lockById(5) } returns slot(5, active = false)
        assertThatThrownBy { useCase.assign(3, 5) }.isInstanceOf(ar.melocoton.slots.domain.SlotNotAcceptingBookings::class.java)

        verify(exactly = 0) { students.saveFixedSlot(any()) }
        verify(exactly = 0) { bookings.insertFixedIfAbsent(any(), any(), any()) }
        verify(exactly = 0) { bookings.cancelScheduledFixed(any(), any(), any(), any()) }
    }

    // ---- ExtendFixedBookingWindow ----

    @Test
    fun `CP30_theExtensionLocksEveryStudentBeforeAnySlot`() {
        every { students.findIdsWithActiveFixedSlot() } returns listOf(1, 2, 3)
        every { students.lockById(1) } returns monthly(1, slotId = 8)
        every { students.lockById(2) } returns monthly(2, slotId = 5)
        every { students.lockById(3) } returns monthly(3, slotId = 8)
        every { slots.lockById(5) } returns slot(5)
        every { slots.lockById(8) } returns slot(8)
        every { bookings.existingStudentBookings(any(), any(), any(), any()) } returns emptyList()
        every { bookings.insertFixedIfAbsent(any(), any(), any()) } returns 10

        val created = ExtendFixedBookingWindow(students, slots, bookings, settings, clock).extend()

        assertThat(created).isEqualTo(30)
        verifyOrder {
            students.lockById(1)
            students.lockById(2)
            students.lockById(3)
            slots.lockById(5)
            slots.lockById(8)
        }
        verify(exactly = 1) { slots.lockById(8) }
    }

    @Test
    fun `CP30_theExtensionSkipsAStudentWhoChangedAndASlotThatWasDeactivated`() {
        every { students.findIdsWithActiveFixedSlot() } returns listOf(1, 2, 3)
        every { students.lockById(1) } returns Student(1, EnrollmentType.MONTHLY, 5, LocalDate.of(2026, 3, 1), active = false)
        every { students.lockById(2) } returns monthly(2, slotId = 6)
        every { students.lockById(3) } returns null
        every { slots.lockById(6) } returns slot(6, active = false)

        val created = ExtendFixedBookingWindow(students, slots, bookings, settings, clock).extend()

        assertThat(created).isZero()
        verify(exactly = 0) { bookings.insertFixedIfAbsent(any(), any(), any()) }
    }

    // ---- BookDropInClass ----

    @Test
    fun `RF11_aFullLessonSavesNothing`() {
        every { students.lockById(1) } returns dropIn(1)
        every { slots.lockById(4) } returns slot(4)
        every { bookings.existingStudentBookings(any(), any(), any(), any()) } returns emptyList()
        every { bookings.occupancyOn(4, LocalDate.of(2026, 12, 1)) } returns 12

        assertThatThrownBy { BookDropInClass(students, slots, bookings, clock).book(1, 4, LocalDate.of(2026, 12, 1)) }
            .isInstanceOf(CapacityFull::class.java)

        verify(exactly = 0) { bookings.createDropIn(any(), any(), any()) }
        verifyOrder {
            students.lockById(1)
            slots.lockById(4)
            bookings.occupancyOn(4, LocalDate.of(2026, 12, 1))
        }
    }

    @Test
    fun `RF09_aFreeLessonIsBooked`() {
        val created = Booking(7, 1, 4, LocalDate.of(2026, 12, 1), BookingType.DROP_IN, BookingStatus.SCHEDULED, null)
        every { students.lockById(1) } returns dropIn(1)
        every { slots.lockById(4) } returns slot(4)
        every { bookings.existingStudentBookings(any(), any(), any(), any()) } returns emptyList()
        every { bookings.occupancyOn(4, LocalDate.of(2026, 12, 1)) } returns 11
        every { bookings.createDropIn(1, 4, LocalDate.of(2026, 12, 1)) } returns created

        val result = BookDropInClass(students, slots, bookings, clock).book(1, 4, LocalDate.of(2026, 12, 1))

        assertThat(result).isSameAs(created)
    }

    // ---- CancelDropInClass ----

    @Test
    fun `RF52_cancelLocksTheStudentTheSlotAndTheBookingAndSavesIt`() {
        val booking = Booking(7, 1, 4, LocalDate.of(2026, 12, 1), BookingType.DROP_IN, BookingStatus.SCHEDULED, null)
        every { students.lockById(1) } returns dropIn(1)
        every { bookings.findSlotId(7) } returns 4
        every { slots.lockById(4) } returns slot(4)
        every { bookings.lockById(7) } returns booking

        val result = CancelDropInClass(students, slots, bookings, clock).cancel(1, 7)

        assertThat(result.status).isEqualTo(BookingStatus.CANCELLED)
        assertThat(result.cancellationReason).isEqualTo(CancellationReason.TEACHER)
        verifyOrder {
            students.lockById(1)
            slots.lockById(4)
            bookings.lockById(7)
            bookings.save(booking)
        }
    }
}
