package ar.melocoton.bookings.application

import ar.melocoton.bookings.FixedClockTestConfig
import ar.melocoton.bookings.domain.CapacityFull
import ar.melocoton.schema.SchemaFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDate
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Bookings under concurrency, as in production: each call commits on its own, so these tests are not
 * wrapped in a test transaction. The API role cannot delete, so every slot they create is deactivated
 * afterwards (the extension job only visits active slots). The clock is fixed at 2026-11-19.
 */
// Same annotations as StudentBookingEndpointTest, so both share one Spring context (and one connection pool).
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig::class)
class BookingConcurrencyTest(
    @Autowired private val bookDropInClass: BookDropInClass,
    @Autowired private val cancelDropInClass: CancelDropInClass,
    @Autowired private val assignFixedSlot: AssignFixedSlot,
    @Autowired private val extendFixedBookingWindow: ExtendFixedBookingWindow,
    @Autowired private val jdbc: JdbcTemplate,
) {
    private val fixtures = SchemaFixtures(jdbc)
    private val lessonDate = LocalDate.of(2026, 12, 1)

    /** Runs the [tasks] at the same moment, each on its own thread and transaction. */
    private fun <T> concurrently(tasks: List<() -> T>): List<Result<T>> {
        val barrier = CyclicBarrier(tasks.size)
        val pool = Executors.newFixedThreadPool(tasks.size)
        try {
            return tasks.map { task ->
                pool.submit<Result<T>> {
                    barrier.await()
                    runCatching { task() }
                }
            }.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }
    }

    private fun activeBookings(slotId: Long, date: LocalDate = lessonDate): Int = jdbc.queryForObject(
        "SELECT count(*) FROM booking WHERE slot_id = ? AND lesson_date = ? AND status IN ('SCHEDULED', 'PRESENT')",
        Int::class.java, slotId, date,
    )!!

    @Test
    fun `CP01_twoRequestsForTheLastSpot`() {
        repeat(5) {
            val slotId = fixtures.slot(capacity = 8)
            try {
                repeat(7) { fixtures.booking(fixtures.monthlyStudent(), slotId, date = lessonDate) }
                val students = List(2) { fixtures.dropInStudent() }

                val outcomes = concurrently(students.map { id -> { bookDropInClass.book(id, slotId, lessonDate).id } })

                assertThat(outcomes.count { it.isSuccess }).describedAs("saved: %s", outcomes).isEqualTo(1)
                assertThat(outcomes.single { it.isFailure }.exceptionOrNull()).isInstanceOf(CapacityFull::class.java)
                assertThat(activeBookings(slotId)).isEqualTo(8)
                assertThat(
                    jdbc.queryForObject("SELECT count(*) FROM booking WHERE slot_id = ? AND type = 'DROP_IN'", Int::class.java, slotId),
                ).isEqualTo(1)
            } finally {
                jdbc.update("UPDATE slot SET active = false WHERE id = ?", slotId)
            }
        }
    }

    @Test
    fun `CP01_cancellingADropInAndBookingTheFreedPlaceAtTheSameTimeLeavesNoDeadlockAndRespectsCapacity`() {
        repeat(5) {
            val slotId = fixtures.slot(capacity = 1)
            try {
                val studentA = fixtures.dropInStudent()
                val studentB = fixtures.dropInStudent()
                val bookingA = fixtures.booking(studentA, slotId, type = "DROP_IN", date = lessonDate)

                val outcomes = concurrently(
                    listOf(
                        { cancelDropInClass.cancel(studentA, bookingA); Unit },
                        { bookDropInClass.book(studentB, slotId, lessonDate); Unit },
                    ),
                )

                val (cancelOutcome, bookOutcome) = outcomes
                assertThat(cancelOutcome.exceptionOrNull()).describedAs("cancel: %s", outcomes).isNull()
                val statusA = jdbc.queryForObject("SELECT status FROM booking WHERE id = ?", String::class.java, bookingA)
                assertThat(activeBookings(slotId)).isLessThanOrEqualTo(1)
                if (bookOutcome.isSuccess) {
                    assertThat(statusA).isEqualTo("CANCELLED")
                    assertThat(activeBookings(slotId)).isEqualTo(1)
                } else {
                    assertThat(bookOutcome.exceptionOrNull()).describedAs("book: %s", outcomes).isInstanceOf(CapacityFull::class.java)
                    assertThat(statusA).isEqualTo("CANCELLED")
                    assertThat(activeBookings(slotId)).isZero()
                }
            } finally {
                jdbc.update("UPDATE slot SET active = false WHERE id = ?", slotId)
            }
        }
    }

    @Test
    fun `CP01_manyRequestsForFewSpotsSaveExactlyTheFreePlaces`() {
        val slotId = fixtures.slot(capacity = 4)
        try {
            repeat(2) { fixtures.booking(fixtures.monthlyStudent(), slotId, date = lessonDate) }
            val students = List(6) { fixtures.dropInStudent() }

            val outcomes = concurrently(students.map { id -> { bookDropInClass.book(id, slotId, lessonDate).id } })

            assertThat(outcomes.count { it.isSuccess }).describedAs("saved: %s", outcomes).isEqualTo(2)
            assertThat(outcomes.filter { it.isFailure }.map { it.exceptionOrNull() }).allMatch { it is CapacityFull }
            assertThat(activeBookings(slotId)).isEqualTo(4)
        } finally {
            jdbc.update("UPDATE slot SET active = false WHERE id = ?", slotId)
        }
    }

    @Test
    fun `DI41_theSameStudentBookingTheSameLessonTwiceAtOnceSavesOne`() {
        val slotId = fixtures.slot(capacity = 8)
        try {
            val studentId = fixtures.dropInStudent()

            val outcomes = concurrently(List(2) { { bookDropInClass.book(studentId, slotId, lessonDate).id } })

            assertThat(outcomes.count { it.isSuccess }).describedAs("saved: %s", outcomes).isEqualTo(1)
            assertThat(activeBookings(slotId)).isEqualTo(1)
        } finally {
            jdbc.update("UPDATE slot SET active = false WHERE id = ?", slotId)
        }
    }

    private fun fixedBookingCounts(studentId: Long): Pair<Int, Int> = jdbc.queryForObject(
        """SELECT count(*), count(DISTINCT lesson_date) FROM booking
           WHERE student_id = ? AND type = 'FIXED' AND status = 'SCHEDULED'""",
        { rs, _ -> rs.getInt(1) to rs.getInt(2) },
        studentId,
    )!!

    @Test
    fun `CP30_extendingTheWindowTwiceCreatesNoDuplicates`() {
        val slotId = fixtures.slot()
        try {
            val students = List(3) { fixtures.monthlyStudent(slotId) }

            extendFixedBookingWindow.extend()
            val afterFirst = students.map { fixedBookingCounts(it) }
            val secondRun = extendFixedBookingWindow.extend()
            val afterSecond = students.map { fixedBookingCounts(it) }

            // The slot is on Tuesdays: ten of them from 2026-11-24 to 2027-01-26.
            assertThat(afterFirst).containsOnly(10 to 10)
            assertThat(afterSecond).isEqualTo(afterFirst)
            assertThat(secondRun).isZero()
        } finally {
            jdbc.update("UPDATE slot SET active = false WHERE id = ?", slotId)
        }
    }

    @Test
    fun `CP30_twoRunsAtTheSameTimeCreateNoDuplicates`() {
        repeat(3) {
            val slotId = fixtures.slot()
            try {
                val students = List(3) { fixtures.monthlyStudent(slotId) }

                val outcomes = concurrently(List(2) { { extendFixedBookingWindow.extend() } })

                assertThat(outcomes.filter { it.isFailure }).describedAs("failed: %s", outcomes).isEmpty()
                assertThat(students.map { fixedBookingCounts(it) }).containsOnly(10 to 10)
            } finally {
                jdbc.update("UPDATE slot SET active = false WHERE id = ?", slotId)
            }
        }
    }

    @Test
    fun `CP30_theExtensionSkipsInactiveStudentsDropInStudentsAndInactiveSlots`() {
        val activeSlot = fixtures.slot()
        val inactiveSlot = fixtures.slot()
        try {
            val inactiveStudent = fixtures.monthlyStudent(activeSlot)
            jdbc.update("UPDATE app_user SET active = false WHERE id = ?", inactiveStudent)
            val onInactiveSlot = fixtures.monthlyStudent(inactiveSlot)
            jdbc.update("UPDATE slot SET active = false WHERE id = ?", inactiveSlot)
            val withoutSlot = fixtures.monthlyStudent()
            val dropIn = fixtures.dropInStudent()

            extendFixedBookingWindow.extend()

            listOf(inactiveStudent, onInactiveSlot, withoutSlot, dropIn).forEach {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM booking WHERE student_id = ?", Int::class.java, it)).isZero()
            }
        } finally {
            jdbc.update("UPDATE slot SET active = false WHERE id IN (?, ?)", activeSlot, inactiveSlot)
        }
    }

    @Test
    fun `CP30_anAssignmentAndAnExtensionAtTheSameTimeLeaveNoDuplicatesAndNoDeadlock`() {
        repeat(3) {
            val slotA = fixtures.slot()
            val slotB = fixtures.slot()
            try {
                val movers = List(3) { fixtures.monthlyStudent(slotA) }
                val others = List(2) { fixtures.monthlyStudent(slotB) }

                val outcomes = concurrently(
                    movers.map { id -> { assignFixedSlot.assign(id, slotB).slot.id } } +
                        List(2) { { extendFixedBookingWindow.extend() } },
                )

                assertThat(outcomes.filter { it.isFailure }).describedAs("failed: %s", outcomes).isEmpty()
                assertThat((movers + others).map { fixedBookingCounts(it) }).containsOnly(10 to 10)
            } finally {
                jdbc.update("UPDATE slot SET active = false WHERE id IN (?, ?)", slotA, slotB)
            }
        }
    }
}
