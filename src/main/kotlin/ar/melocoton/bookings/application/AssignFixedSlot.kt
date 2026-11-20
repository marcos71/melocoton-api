package ar.melocoton.bookings.application

import ar.melocoton.bookings.application.port.BookingRepository
import ar.melocoton.bookings.domain.CancellationReason
import ar.melocoton.bookings.domain.RollingWindow
import ar.melocoton.bookings.domain.firstUnstartedLessonDate
import ar.melocoton.settings.application.port.SettingsRepository
import ar.melocoton.shared.domain.WorkshopTime
import ar.melocoton.slots.application.port.SlotRepository
import ar.melocoton.slots.domain.LessonOccupancy
import ar.melocoton.slots.domain.Slot
import ar.melocoton.slots.domain.SlotNotFound
import ar.melocoton.students.application.port.StudentRepository
import ar.melocoton.students.domain.StudentNotFound
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** The slot a student now has and the future lessons of it that are over capacity (DI-43). */
class FixedSlotAssignment(val slot: Slot, val overbookedLessons: List<LessonOccupancy>)

/**
 * RF-08, DI-27, DI-42, DI-43: the lead teacher assigns or replaces a monthly student's fixed slot.
 * The fixed bookings of the rolling window are generated, even over capacity. When the student had another
 * slot, its scheduled fixed bookings of lessons that have not started are cancelled by enrollment change.
 *
 * Lock order (every booking use case): the student, then the slots by ascending id.
 */
@Service
class AssignFixedSlot(
    private val students: StudentRepository,
    private val slots: SlotRepository,
    private val bookings: BookingRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    private val generation = FixedBookingGeneration(bookings)

    @Transactional
    fun assign(studentId: Long, slotId: Long): FixedSlotAssignment {
        val now = WorkshopTime.now(clock)
        val student = students.lockById(studentId) ?: throw StudentNotFound()
        student.requireCanHaveFixedSlot()

        val previousSlotId = student.fixedSlotId
        val locked = listOfNotNull(previousSlotId, slotId).distinct().sorted().associateWith { slots.lockById(it) }
        val slot = locked.getValue(slotId) ?: throw SlotNotFound()
        slot.requireAcceptsBookings()

        val previous = previousSlotId?.let { locked[it] }
        if (previous != null && previous.id != slot.id) {
            bookings.cancelScheduledFixed(
                studentId, previous.id, firstUnstartedLessonDate(previous.startTime, now), CancellationReason.ENROLLMENT_CHANGE,
            )
        }
        student.assignFixedSlot(slotId)
        students.saveFixedSlot(student)

        val windowEnd = RollingWindow.endDate(now.toLocalDate(), settings.get().makeupHorizonDays)
        generation.generate(student, slot, now, windowEnd)

        val occupancy = bookings.occupancyByLessonDate(slotId, now.toLocalDate())
        return FixedSlotAssignment(slot, slot.overbookedLessons(occupancy, now))
    }
}
