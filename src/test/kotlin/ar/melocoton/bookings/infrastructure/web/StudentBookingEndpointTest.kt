package ar.melocoton.bookings.infrastructure.web

import ar.melocoton.auth.infrastructure.security.JwtSessionTokens
import ar.melocoton.bookings.FixedClockTestConfig
import ar.melocoton.bookings.application.port.BookingRepository
import ar.melocoton.bookings.domain.AlreadyBooked
import ar.melocoton.schema.SchemaFixtures
import ar.melocoton.shared.domain.Role
import ar.melocoton.slots.domain.Slot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.util.UUID

/**
 * Fixed slots, drop-in bookings and their cancellation by the lead teacher, end to end against the test
 * database (RF-08, RF-09, RF-11, RF-52; DI-27, DI-41 to DI-46). The clock is fixed at Thursday 2026-11-19,
 * 12:00 in Buenos Aires, so the rolling window ends on 2027-01-31. Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FixedClockTestConfig::class)
class StudentBookingEndpointTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val json: ObjectMapper,
    @Autowired private val tokens: JwtSessionTokens,
    @Autowired private val bookingRepository: BookingRepository,
) {
    private val fixtures = SchemaFixtures(jdbc)

    /** The Tuesdays from today to the window end. */
    private val tuesdays = listOf(
        "2026-11-24", "2026-12-01", "2026-12-08", "2026-12-15", "2026-12-22",
        "2026-12-29", "2027-01-05", "2027-01-12", "2027-01-19", "2027-01-26",
    )

    private fun tokenFor(role: Role): String {
        val id = jdbc.queryForObject(
            """INSERT INTO app_user (name, email, password_hash, role, phone, active)
               VALUES ('Ana', ?, 'hash', ?, '1100000000', true) RETURNING id""",
            Long::class.javaObjectType, "${UUID.randomUUID()}@test.local", role.name,
        )!!
        val validFrom = jdbc.queryForObject("SELECT sessions_valid_from FROM app_user WHERE id = ?", java.time.Instant::class.java, id)!!
        return tokens.issue(id, role, validFrom, FixedClockTestConfig.NOW).value
    }

    private val lead get() = tokenFor(Role.LEAD_TEACHER)

    private fun slot(day: Int = 2, start: String = "18:00", end: String = "20:00", active: Boolean = true): Long =
        jdbc.queryForObject(
            "INSERT INTO slot (day_of_week, start_time, end_time, active) VALUES (?, ?::time, ?::time, ?) RETURNING id",
            Long::class.java, day, start, end, active,
        )!!

    private fun student(
        monthly: Boolean = true,
        active: Boolean = true,
        slotId: Long? = null,
        start: LocalDate = LocalDate.of(2026, 3, 1),
    ): Long {
        val userId = jdbc.queryForObject(
            """INSERT INTO app_user (name, email, password_hash, role, phone, active)
               VALUES ('Alumno', ?, 'hash', 'STUDENT', '1100000000', ?) RETURNING id""",
            Long::class.java, "${UUID.randomUUID()}@test.local", active,
        )!!
        if (monthly) {
            jdbc.update(
                "INSERT INTO student (user_id, enrollment_type, fixed_slot_id, monthly_start_date) VALUES (?, 'MONTHLY', ?, ?)",
                userId, slotId, start,
            )
        } else {
            jdbc.update("INSERT INTO student (user_id, enrollment_type) VALUES (?, 'DROP_IN')", userId)
        }
        return userId
    }

    private fun assign(token: String?, studentId: Any, body: Map<String, Any?>) = mvc.put("/api/v1/students/$studentId/fixed-slot") {
        token?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
        contentType = MediaType.APPLICATION_JSON
        content = json.writeValueAsString(body)
    }

    private fun assign(studentId: Long, slotId: Long) = assign(lead, studentId, mapOf("slotId" to slotId))

    private fun book(token: String?, studentId: Any, body: Map<String, Any?>) = mvc.post("/api/v1/students/$studentId/drop-in-bookings") {
        token?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
        contentType = MediaType.APPLICATION_JSON
        content = json.writeValueAsString(body)
    }

    private fun book(studentId: Long, slotId: Long, date: String) = book(lead, studentId, mapOf("slotId" to slotId, "lessonDate" to date))

    private fun cancel(token: String?, studentId: Any, bookingId: Any) =
        mvc.post("/api/v1/students/$studentId/drop-in-bookings/$bookingId/cancel") {
            token?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
        }

    private fun cancel(studentId: Long, bookingId: Long) = cancel(lead, studentId, bookingId)

    private fun profile(studentId: Long) = mvc.get("/api/v1/students/$studentId") {
        header(HttpHeaders.AUTHORIZATION, "Bearer $lead")
    }

    private fun idOf(result: ResultActionsDsl): Long = json.readTree(result.andReturn().response.contentAsString)["id"].asLong()

    private fun ResultActionsDsl.isError(status: Int, code: String, message: String? = null) = andExpect {
        status { isEqualTo(status) }
        jsonPath("$.error.code") { value(code) }
        message?.let { jsonPath("$.error.message") { value(it) } }
    }

    private fun bookingDates(studentId: Long, slotId: Long, type: String = "FIXED", status: String = "SCHEDULED"): List<String> =
        jdbc.queryForList(
            "SELECT lesson_date::text FROM booking WHERE student_id = ? AND slot_id = ? AND type = ? AND status = ? ORDER BY lesson_date",
            String::class.java, studentId, slotId, type, status,
        ).filterNotNull()

    /** Occupies [count] places of a lesson with scheduled bookings of other students. */
    private fun fill(slotId: Long, date: String, count: Int, status: String = "SCHEDULED") = repeat(count) {
        fixtures.booking(
            student(), slotId, type = "FIXED", status = status, date = LocalDate.parse(date),
            cancellationReason = if (status == "CANCELLED") "STUDENT" else null,
        )
    }

    // ---- assigning a fixed slot ----

    @Test
    fun `RF08_assigningAFixedSlotCreatesBookingsForTheWholeWindow`() {
        val slotId = slot()
        val studentId = student()

        assign(studentId, slotId).andExpect {
            status { isOk() }
            jsonPath("$.fixedSlot.id") { value(slotId) }
            jsonPath("$.fixedSlot.dayOfWeek") { value(2) }
            jsonPath("$.fixedSlot.startTime") { value("18:00") }
            jsonPath("$.fixedSlot.endTime") { value("20:00") }
            jsonPath("$.overbookedLessons") { isEmpty() }
        }

        assertThat(jdbc.queryForObject("SELECT fixed_slot_id FROM student WHERE user_id = ?", Long::class.java, studentId)).isEqualTo(slotId)
        assertThat(bookingDates(studentId, slotId)).containsExactlyElementsOf(tuesdays)
    }

    @Test
    fun `RF08_nothingIsGeneratedBeforeTheMonthlyStartDate`() {
        val slotId = slot()
        val studentId = student(start = LocalDate.of(2026, 12, 10))

        assign(studentId, slotId).andExpect { status { isOk() } }

        assertThat(bookingDates(studentId, slotId)).containsExactlyElementsOf(tuesdays.drop(3))
    }

    @Test
    fun `RF08_assigningTheSameSlotAgainIsIdempotent`() {
        val slotId = slot()
        val studentId = student()

        assign(studentId, slotId).andExpect { status { isOk() } }
        assign(studentId, slotId).andExpect { status { isOk() } }

        assertThat(bookingDates(studentId, slotId)).containsExactlyElementsOf(tuesdays)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking WHERE student_id = ?", Int::class.java, studentId)).isEqualTo(10)
    }

    @Test
    fun `RF08_theSameSlotAgainFillsTheMissingDatesOnly`() {
        val slotId = slot()
        val studentId = student(slotId = slotId)
        fixtures.booking(studentId, slotId, date = LocalDate.of(2026, 12, 1))
        fixtures.booking(studentId, slotId, status = "CANCELLED", cancellationReason = "STUDENT", date = LocalDate.of(2026, 12, 8))

        assign(studentId, slotId).andExpect { status { isOk() } }

        // The cancelled one by the student is not generated again; every other date is.
        assertThat(bookingDates(studentId, slotId)).containsExactlyElementsOf(tuesdays - "2026-12-08")
    }

    @Test
    fun `DI27_replacingTheSlotCancelsTheOldFutureFixedBookingsAndKeepsThePastOnes`() {
        // The old slot is on Thursdays at 09:00: today's lesson has already started.
        val oldSlot = slot(day = 4, start = "09:00", end = "11:00")
        val newSlot = slot(day = 3, start = "18:00", end = "20:00")
        val studentId = student()
        assign(studentId, oldSlot).andExpect { status { isOk() } }
        val thursdays = bookingDates(studentId, oldSlot)
        assertThat(thursdays).first().isEqualTo("2026-11-26")
        fixtures.booking(studentId, oldSlot, date = LocalDate.of(2026, 11, 12))
        fixtures.booking(studentId, oldSlot, date = LocalDate.of(2026, 11, 19))
        jdbc.update("UPDATE booking SET status = 'PRESENT' WHERE student_id = ? AND lesson_date = '2026-12-03'", studentId)

        assign(studentId, newSlot).andExpect {
            status { isOk() }
            jsonPath("$.fixedSlot.id") { value(newSlot) }
        }

        assertThat(jdbc.queryForObject("SELECT fixed_slot_id FROM student WHERE user_id = ?", Long::class.java, studentId)).isEqualTo(newSlot)
        // Past and started lessons, and attendance already recorded, are untouched.
        assertThat(bookingDates(studentId, oldSlot)).containsExactly("2026-11-12", "2026-11-19")
        assertThat(bookingDates(studentId, oldSlot, status = "PRESENT")).containsExactly("2026-12-03")
        val cancelled = jdbc.queryForList(
            "SELECT DISTINCT cancellation_reason FROM booking WHERE student_id = ? AND slot_id = ? AND status = 'CANCELLED'",
            String::class.java, studentId, oldSlot,
        )
        assertThat(cancelled).containsExactly("ENROLLMENT_CHANGE")
        assertThat(bookingDates(studentId, oldSlot, status = "CANCELLED")).containsExactlyElementsOf(thursdays - "2026-12-03")
        // The new slot has its own Wednesdays, from tomorrow.
        assertThat(bookingDates(studentId, newSlot)).first().isEqualTo("2026-11-25")
        assertThat(bookingDates(studentId, newSlot)).hasSize(10)
    }

    @Test
    fun `DI42_goingBackToAPreviousSlotGeneratesItsBookingsAgain`() {
        val slotA = slot(day = 2)
        val slotB = slot(day = 3)
        val studentId = student()

        assign(studentId, slotA).andExpect { status { isOk() } }
        assign(studentId, slotB).andExpect { status { isOk() } }
        assign(studentId, slotA).andExpect { status { isOk() } }

        assertThat(bookingDates(studentId, slotA)).containsExactlyElementsOf(tuesdays)
        assertThat(bookingDates(studentId, slotA, status = "CANCELLED")).containsExactlyElementsOf(tuesdays)
        assertThat(bookingDates(studentId, slotB)).isEmpty()
        assertThat(bookingDates(studentId, slotB, status = "CANCELLED")).hasSize(10)
    }

    @Test
    fun `DI43_aFixedAssignmentMayOverbookADateFilledByEarlierBookings`() {
        val slotId = slot()
        fill(slotId, "2026-12-01", Slot.CAPACITY)
        fill(slotId, "2026-12-08", Slot.CAPACITY - 1)
        val studentId = student()

        assign(studentId, slotId).andExpect {
            status { isOk() }
            jsonPath("$.overbookedLessons.length()") { value(1) }
            jsonPath("$.overbookedLessons[0].lessonDate") { value("2026-12-01") }
            jsonPath("$.overbookedLessons[0].occupancy") { value(13) }
            jsonPath("$.overbookedLessons[0].capacity") { value(12) }
        }

        assertThat(bookingDates(studentId, slotId)).containsExactlyElementsOf(tuesdays)
    }

    @Test
    fun `DI66_aSlotWithTwelveFixedStudentsRejectsAnotherOne`() {
        val slotId = slot()
        repeat(Slot.CAPACITY) { student(slotId = slotId) }
        val newcomer = student()

        assign(newcomer, slotId).andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("fixed_students_full") }
            jsonPath("$.error.message") { value("El turno ya tiene 12 alumnos fijos, que es su cupo.") }
            jsonPath("$.error.details.fixedStudents") { value(12) }
            jsonPath("$.error.details.capacity") { value(12) }
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking WHERE student_id = ?", Int::class.java, newcomer)).isZero()
        assertThat(jdbc.queryForObject("SELECT fixed_slot_id FROM student WHERE user_id = ?", Long::class.javaObjectType, newcomer)).isNull()
    }

    @Test
    fun `DI66_aStudentAlreadyInAFullSlotCanBeAssignedItAgain`() {
        val slotId = slot()
        repeat(Slot.CAPACITY - 1) { student(slotId = slotId) }
        val member = student(slotId = slotId)

        assign(member, slotId).andExpect { status { isOk() } }
    }

    @Test
    fun `DI66_inactiveStudentsDoNotTakeAFixedPlace`() {
        val slotId = slot()
        repeat(Slot.CAPACITY) { student(slotId = slotId, active = false) }

        assign(student(), slotId).andExpect { status { isOk() } }
    }

    @Test
    fun `DI66_withOnePlaceLeftTheAssignmentSucceedsEvenIfADropInFilledADate`() {
        val slotId = slot()
        repeat(Slot.CAPACITY - 1) { student(slotId = slotId) }
        fill(slotId, "2026-12-01", Slot.CAPACITY)

        assign(student(), slotId).andExpect {
            status { isOk() }
            jsonPath("$.overbookedLessons.length()") { value(1) }
            jsonPath("$.overbookedLessons[0].occupancy") { value(13) }
        }
    }

    @Test
    fun `RF08_aDropInStudentCannotHaveAFixedSlot`() {
        val studentId = student(monthly = false)

        assign(studentId, slot()).isError(409, "enrollment_type_mismatch", "Solo un alumno mensual tiene turno fijo.")

        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking WHERE student_id = ?", Int::class.java, studentId)).isZero()
    }

    @Test
    fun `RF08_anInactiveStudentCannotHaveAFixedSlot`() {
        assign(student(active = false), slot()).isError(409, "student_inactive")
    }

    @Test
    fun `RF08_anInactiveSlotCannotBeAssigned`() {
        val studentId = student()

        assign(studentId, slot(active = false)).isError(409, "slot_inactive")

        assertThat(jdbc.queryForObject("SELECT fixed_slot_id FROM student WHERE user_id = ?", Long::class.javaObjectType, studentId)).isNull()
    }

    @Test
    fun `RF08_aSlotThatDoesNotExistIsNotFound`() {
        assign(student(), 999_999_999L).isError(404, "not_found")
    }

    @Test
    fun `RF08_anIdThatIsNotAStudentIsNotFound`() {
        val slotId = slot()
        val leadId = fixtures.user("LEAD_TEACHER")

        assign(leadId, slotId).isError(404, "not_found")
        assign(999_999_999L, slotId).isError(404, "not_found")
        assign(lead, "abc", mapOf("slotId" to slotId)).isError(404, "not_found")
    }

    @Test
    fun `RF08_aBodyWithoutTheSlotIsAValidationError`() {
        val studentId = student()

        assign(lead, studentId, emptyMap()).isError(400, "validation_error")
        assign(lead, studentId, mapOf("slotId" to "abc")).isError(400, "validation_error")
    }

    // ---- booking a drop-in class ----

    @Test
    fun `RF09_aDropInStudentBooksAClass`() {
        val slotId = slot()
        val studentId = student(monthly = false)

        val result = book(studentId, slotId, "2026-11-24").andExpect {
            status { isCreated() }
            jsonPath("$.id") { isNumber() }
            jsonPath("$.slotId") { value(slotId) }
            jsonPath("$.lessonDate") { value("2026-11-24") }
            jsonPath("$.type") { value("DROP_IN") }
            jsonPath("$.status") { value("SCHEDULED") }
        }

        val row = jdbc.queryForMap("SELECT student_id, slot_id, type, status FROM booking WHERE id = ?", idOf(result))
        assertThat(row).containsEntry("student_id", studentId).containsEntry("slot_id", slotId)
            .containsEntry("type", "DROP_IN").containsEntry("status", "SCHEDULED")
    }

    @Test
    fun `RF09_aMonthlyStudentCannotBookADropInClass`() {
        book(student(), slot(), "2026-11-24")
            .isError(409, "enrollment_type_mismatch", "La clase suelta es solo para alumnos de clase suelta.")
    }

    @Test
    fun `RF09_anInactiveDropInStudentCannotBook`() {
        book(student(monthly = false, active = false), slot(), "2026-11-24").isError(409, "student_inactive")
    }

    @Test
    fun `RF09_anInactiveSlotDoesNotAcceptDropInBookings`() {
        book(student(monthly = false), slot(active = false), "2026-11-24").isError(409, "slot_inactive")
    }

    @Test
    fun `RF09_aSlotThatDoesNotExistOrAnIdThatIsNotAStudentIsNotFound`() {
        book(student(monthly = false), 999_999_999L, "2026-11-24").isError(404, "not_found")
        book(999_999_999L, slot(), "2026-11-24").isError(404, "not_found")
    }

    @Test
    fun `DI44_theDateMustFallOnTheSlotsWeekday`() {
        book(student(monthly = false), slot(), "2026-11-25").isError(400, "invalid_lesson_date")
    }

    @Test
    fun `DI44_theDateCanBeUpToThreeMonthsAheadAndNotMore`() {
        // Three months from 2026-11-19 is 2027-02-19, a Friday.
        val slotId = slot(day = 5)

        book(student(monthly = false), slotId, "2027-02-19").andExpect { status { isCreated() } }
        book(student(monthly = false), slotId, "2027-02-26").isError(400, "invalid_lesson_date")
    }

    @Test
    fun `DI44_aLessonThatHasAlreadyStartedCannotBeBooked`() {
        val studentId = student(monthly = false)

        // Today is Thursday and it is 12:00: the 09:00 lesson started, the 18:00 one has not.
        book(studentId, slot(day = 4, start = "09:00", end = "11:00"), "2026-11-19").isError(409, "lesson_already_started")
        book(studentId, slot(day = 2), "2026-11-17").isError(409, "lesson_already_started")
        book(studentId, slot(day = 4, start = "18:00", end = "20:00"), "2026-11-19").andExpect { status { isCreated() } }
    }

    @Test
    fun `DI41_bookingTheSameLessonTwiceIsAlreadyBooked`() {
        val slotId = slot()
        val studentId = student(monthly = false)
        book(studentId, slotId, "2026-11-24").andExpect { status { isCreated() } }

        book(studentId, slotId, "2026-11-24").isError(409, "already_booked")

        assertThat(bookingDates(studentId, slotId, "DROP_IN")).hasSize(1)
    }

    @Test
    fun `DI41_theUniqueIndexIsTheLastBarrierAgainstADuplicateDropIn`() {
        val slotId = slot()
        val studentId = student(monthly = false)
        bookingRepository.createDropIn(studentId, slotId, LocalDate.of(2026, 11, 24))

        // Skips the check the use case makes first, as a racing request would.
        assertThatThrownBy { bookingRepository.createDropIn(studentId, slotId, LocalDate.of(2026, 11, 24)) }
            .isInstanceOf(AlreadyBooked::class.java)
    }

    @Test
    fun `RF11_aDropInCannotExceedTheCapacityAndNothingIsSaved`() {
        val slotId = slot()
        fill(slotId, "2026-11-24", Slot.CAPACITY)
        val studentId = student(monthly = false)

        book(studentId, slotId, "2026-11-24").isError(409, "capacity_full", "No queda lugar en esa clase.")

        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking WHERE student_id = ?", Int::class.java, studentId)).isZero()
    }

    @Test
    fun `RN02_onlyBookingsThatOccupyAPlaceCountForTheCapacity`() {
        val slotId = slot()
        fill(slotId, "2026-11-24", Slot.CAPACITY - 1)
        fill(slotId, "2026-11-24", 1, status = "CANCELLED")
        fill(slotId, "2026-11-24", 2, status = "ABSENT")

        book(student(monthly = false), slotId, "2026-11-24").andExpect { status { isCreated() } }
        book(student(monthly = false), slotId, "2026-11-24").isError(409, "capacity_full")
    }

    @Test
    fun `DI43_anOverbookedLessonTakesNoDropIn`() {
        val slotId = slot()
        fill(slotId, "2026-11-24", Slot.CAPACITY + 1)

        book(student(monthly = false), slotId, "2026-11-24").isError(409, "capacity_full")
    }

    @Test
    fun `RF09_theBodyMustHaveTheSlotAndAWellFormedDate`() {
        val studentId = student(monthly = false)

        book(lead, studentId, mapOf("lessonDate" to "2026-11-24")).isError(400, "validation_error")
        book(lead, studentId, mapOf("slotId" to slot())).isError(400, "validation_error")
        book(lead, studentId, mapOf("slotId" to slot(), "lessonDate" to "24/11/2026")).isError(400, "validation_error")
        book(lead, "abc", mapOf("slotId" to 1, "lessonDate" to "2026-11-24")).isError(404, "not_found")
    }

    // ---- cancelling a drop-in class ----

    @Test
    fun `RF52_theTeacherCancelsADropInAndTheirPlaceIsFree`() {
        val slotId = slot()
        fill(slotId, "2026-11-24", Slot.CAPACITY - 1)
        val studentId = student(monthly = false)
        val bookingId = idOf(book(studentId, slotId, "2026-11-24"))
        book(student(monthly = false), slotId, "2026-11-24").isError(409, "capacity_full")

        cancel(studentId, bookingId).andExpect {
            status { isOk() }
            jsonPath("$.id") { value(bookingId) }
            jsonPath("$.status") { value("CANCELLED") }
            jsonPath("$.type") { value("DROP_IN") }
        }

        val row = jdbc.queryForMap("SELECT status, cancellation_reason FROM booking WHERE id = ?", bookingId)
        assertThat(row).containsEntry("status", "CANCELLED").containsEntry("cancellation_reason", "TEACHER")
        book(student(monthly = false), slotId, "2026-11-24").andExpect { status { isCreated() } }
    }

    @Test
    fun `DI41_aStudentCanBookAgainAfterCancelling`() {
        val slotId = slot()
        val studentId = student(monthly = false)
        val first = idOf(book(studentId, slotId, "2026-11-24"))
        cancel(studentId, first).andExpect { status { isOk() } }

        book(studentId, slotId, "2026-11-24").andExpect { status { isCreated() } }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking WHERE student_id = ?", Int::class.java, studentId)).isEqualTo(2)
    }

    @Test
    fun `RF52_aBookingOfAnotherStudentOrAFixedOneOrAMissingOneIsNotFound`() {
        val slotId = slot()
        val studentId = student(monthly = false)
        val other = student(monthly = false)
        val bookingId = idOf(book(studentId, slotId, "2026-11-24"))
        val fixedStudent = student()
        val fixedId = fixtures.booking(fixedStudent, slotId, date = LocalDate.of(2026, 12, 1))

        cancel(other, bookingId).isError(404, "not_found")
        cancel(fixedStudent, fixedId).isError(404, "not_found")
        cancel(studentId, 999_999_999L).isError(404, "not_found")
        cancel(999_999_999L, bookingId).isError(404, "not_found")
        cancel(lead, "abc", bookingId).isError(404, "not_found")

        assertThat(jdbc.queryForObject("SELECT status FROM booking WHERE id = ?", String::class.java, bookingId)).isEqualTo("SCHEDULED")
        assertThat(jdbc.queryForObject("SELECT status FROM booking WHERE id = ?", String::class.java, fixedId)).isEqualTo("SCHEDULED")
    }

    @Test
    fun `RF52_onlyAScheduledBookingCanBeCancelled`() {
        val slotId = slot()
        val studentId = student(monthly = false)
        val bookingId = idOf(book(studentId, slotId, "2026-11-24"))
        cancel(studentId, bookingId).andExpect { status { isOk() } }

        cancel(studentId, bookingId).isError(409, "status_conflict")

        jdbc.update("UPDATE booking SET status = 'PRESENT', cancellation_reason = NULL WHERE id = ?", bookingId)
        cancel(studentId, bookingId).isError(409, "status_conflict")
    }

    @Test
    fun `RF52_aLessonThatHasStartedCannotBeCancelled`() {
        val slotId = slot(day = 4, start = "09:00", end = "11:00")
        val studentId = student(monthly = false)
        val bookingId = fixtures.booking(studentId, slotId, type = "DROP_IN", date = LocalDate.of(2026, 11, 19))

        cancel(studentId, bookingId).isError(409, "lesson_already_started")

        assertThat(jdbc.queryForObject("SELECT status FROM booking WHERE id = ?", String::class.java, bookingId)).isEqualTo("SCHEDULED")
    }

    // ---- the profile lists the upcoming classes ----

    @Test
    fun `DI46_theProfileListsTheUpcomingClassesInOrder`() {
        val slotId = slot()
        val studentId = student()
        assign(studentId, slotId).andExpect { status { isOk() } }
        // Not upcoming: started today, past, cancelled, and recorded.
        val started = slot(day = 4, start = "09:00", end = "11:00")
        fixtures.booking(studentId, started, date = LocalDate.of(2026, 11, 19))
        fixtures.booking(studentId, slotId, date = LocalDate.of(2026, 11, 17))
        fixtures.booking(studentId, started, status = "CANCELLED", cancellationReason = "TEACHER", date = LocalDate.of(2026, 11, 26))
        fixtures.booking(studentId, started, status = "ABSENT", date = LocalDate.of(2026, 12, 3))
        // Upcoming, earlier than the Tuesday of the same week: a Thursday at 18:00 today.
        val evening = slot(day = 4, start = "18:00", end = "20:00")
        val eveningBooking = fixtures.booking(studentId, evening, type = "DROP_IN", date = LocalDate.of(2026, 11, 19))
        // Same date as the first FIXED lesson, on another Tuesday slot that starts earlier: it must come first.
        val morning = slot(day = 2, start = "09:00", end = "11:00")
        val morningBooking = fixtures.booking(studentId, morning, type = "DROP_IN", date = LocalDate.of(2026, 11, 24))

        val response = profile(studentId).andExpect {
            status { isOk() }
            jsonPath("$.upcomingLessons.length()") { value(12) }
            jsonPath("$.upcomingLessons[0].bookingId") { value(eveningBooking) }
            jsonPath("$.upcomingLessons[0].lessonDate") { value("2026-11-19") }
            jsonPath("$.upcomingLessons[0].slotId") { value(evening) }
            jsonPath("$.upcomingLessons[0].dayOfWeek") { value(4) }
            jsonPath("$.upcomingLessons[0].startTime") { value("18:00") }
            jsonPath("$.upcomingLessons[0].endTime") { value("20:00") }
            jsonPath("$.upcomingLessons[0].type") { value("DROP_IN") }
            jsonPath("$.upcomingLessons[1].bookingId") { value(morningBooking) }
            jsonPath("$.upcomingLessons[1].lessonDate") { value("2026-11-24") }
            jsonPath("$.upcomingLessons[1].startTime") { value("09:00") }
            jsonPath("$.upcomingLessons[1].type") { value("DROP_IN") }
            jsonPath("$.upcomingLessons[2].lessonDate") { value("2026-11-24") }
            jsonPath("$.upcomingLessons[2].dayOfWeek") { value(2) }
            jsonPath("$.upcomingLessons[2].startTime") { value("18:00") }
            jsonPath("$.upcomingLessons[2].type") { value("FIXED") }
        }.andReturn().response.contentAsString

        val dates = json.readTree(response)["upcomingLessons"].values().map { it["lessonDate"].asString() }
        assertThat(dates).isSorted()
    }

    @Test
    fun `DI46_aStudentWithoutBookingsHasNoUpcomingClasses`() {
        profile(student(monthly = false)).andExpect {
            status { isOk() }
            jsonPath("$.upcomingLessons") { isEmpty() }
        }
    }

    // ---- who can call these endpoints ----

    @Test
    fun `DI34_onlyTheLeadTeacherCanManageFixedSlotsAndDropInBookings`() {
        val studentId = student()
        val body = mapOf("slotId" to 1, "lessonDate" to "2026-11-24")
        listOf(Role.STUDENT to 403, Role.ASSISTANT_TEACHER to 403).forEach { (role, expected) ->
            val token = tokenFor(role)
            assign(token, studentId, body).andExpect { status { isEqualTo(expected) } }
            book(token, studentId, body).andExpect { status { isEqualTo(expected) } }
            cancel(token, studentId, 1).andExpect { status { isEqualTo(expected) } }
        }
        assign(null, studentId, body).andExpect { status { isUnauthorized() } }
        book(null, studentId, body).andExpect { status { isUnauthorized() } }
        cancel(null, studentId, 1).andExpect { status { isUnauthorized() } }
    }
}
