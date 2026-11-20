package ar.melocoton.bookings.application

import ar.melocoton.bookings.application.port.BookingRepository
import ar.melocoton.bookings.domain.RollingWindow
import ar.melocoton.settings.application.port.SettingsRepository
import ar.melocoton.shared.domain.WorkshopTime
import ar.melocoton.slots.application.port.SlotRepository
import ar.melocoton.students.application.port.StudentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * RF-64, CP-30, DI-42: moves the rolling window forward, creating the fixed bookings that every active
 * monthly student with an active fixed slot is missing up to the window end. It uses the same domain
 * function as the assignment, so running it twice, even at the same time, creates no duplicates.
 * No endpoint and no timer yet: the scheduled job comes with iteration 7.
 *
 * One transaction. To keep the lock order of every booking use case (students before slots) across
 * several students, it locks all the students first (ascending id) and then all the slots (ascending id):
 * locking student by student would deadlock with an assignment that holds a student and wants a slot
 * this run already holds.
 */
@Service
class ExtendFixedBookingWindow(
    private val students: StudentRepository,
    private val slots: SlotRepository,
    private val bookings: BookingRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    private val generation = FixedBookingGeneration(bookings)

    /** Returns how many fixed bookings were created. */
    @Transactional
    fun extend(): Int {
        val now = WorkshopTime.now(clock)
        val windowEnd = RollingWindow.endDate(now.toLocalDate(), settings.get().makeupHorizonDays)

        // A student may have changed since the plain read, so each one is read again under its lock.
        val lockedStudents = students.findIdsWithActiveFixedSlot()
            .mapNotNull { students.lockById(it) }
            .filter { it.hasFixedSlotToFill }
        val lockedSlots = lockedStudents.mapNotNull { it.fixedSlotId }.distinct().sorted()
            .mapNotNull { slots.lockById(it) }
            .filter { it.active }
            .associateBy { it.id }

        return lockedStudents.sumOf { student ->
            val slot = lockedSlots[student.fixedSlotId] ?: return@sumOf 0
            generation.generate(student, slot, now, windowEnd)
        }
    }
}
