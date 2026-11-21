package ar.melocoton.bookings.infrastructure.web

import ar.melocoton.auth.infrastructure.security.JwtSessionTokens
import ar.melocoton.bookings.FixedClockTestConfig
import ar.melocoton.schema.SchemaFixtures
import ar.melocoton.shared.domain.Role
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.util.UUID

/**
 * GET /api/v1/lessons end to end against the test database (DI-56, DI-43, CP02). The clock is fixed at
 * Thursday 2026-11-19, 12:00 in Buenos Aires. Each test is rolled back. Other tests may leave committed
 * slots behind, so the tests narrow by their own slot.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FixedClockTestConfig::class)
class LessonEndpointTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val json: ObjectMapper,
    @Autowired private val tokens: JwtSessionTokens,
) {
    private val fixtures = SchemaFixtures(jdbc)

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

    private fun slot(day: Int = 2, start: String = "18:00", end: String = "20:00", capacity: Int = 2, active: Boolean = true): Long =
        jdbc.queryForObject(
            "INSERT INTO slot (day_of_week, start_time, end_time, capacity, active) VALUES (?, ?::time, ?::time, ?, ?) RETURNING id",
            Long::class.java, day, start, end, capacity, active,
        )!!

    private fun student(): Long {
        val userId = jdbc.queryForObject(
            """INSERT INTO app_user (name, email, password_hash, role, phone, active)
               VALUES ('Alumno', ?, 'hash', 'STUDENT', '1100000000', true) RETURNING id""",
            Long::class.java, "${UUID.randomUUID()}@test.local",
        )!!
        jdbc.update("INSERT INTO student (user_id, enrollment_type) VALUES (?, 'DROP_IN')", userId)
        return userId
    }

    private fun book(slotId: Long, date: String, status: String = "SCHEDULED") = fixtures.booking(
        student(), slotId, type = "DROP_IN", status = status, date = LocalDate.parse(date),
        cancellationReason = if (status == "CANCELLED") "TEACHER" else null,
    )

    private fun lessons(token: String?, query: String) = mvc.get("/api/v1/lessons?$query") {
        token?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
    }

    private fun ResultActionsDsl.isError(status: Int, code: String) = andExpect {
        status { isEqualTo(status) }
        jsonPath("$.error.code") { value(code) }
    }

    private fun bodyOf(result: ResultActionsDsl) = json.readTree(result.andReturn().response.contentAsString)

    @Test
    fun `DI56_lessonsCarryOccupancyFreeSpotsAndTheStartedFlagAndACancelledBookingDoesNotCount`() {
        val slotId = slot(capacity = 2)
        book(slotId, "2026-11-24")
        book(slotId, "2026-11-24", status = "CANCELLED")
        book(slotId, "2026-12-01")
        book(slotId, "2026-12-01")

        val result = lessons(lead, "from=2026-11-17&to=2026-12-01&slotId=$slotId").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(3) }
            jsonPath("$[0].lessonDate") { value("2026-11-17") }
            jsonPath("$[0].started") { value(true) }
            jsonPath("$[0].occupancy") { value(0) }
            jsonPath("$[0].freeSpots") { value(2) }
            jsonPath("$[1].lessonDate") { value("2026-11-24") }
            jsonPath("$[1].slotId") { value(slotId) }
            jsonPath("$[1].dayOfWeek") { value(2) }
            jsonPath("$[1].startTime") { value("18:00") }
            jsonPath("$[1].endTime") { value("20:00") }
            jsonPath("$[1].capacity") { value(2) }
            jsonPath("$[1].occupancy") { value(1) }
            jsonPath("$[1].freeSpots") { value(1) }
            jsonPath("$[1].overbooked") { value(false) }
            jsonPath("$[1].started") { value(false) }
            jsonPath("$[2].occupancy") { value(2) }
            jsonPath("$[2].freeSpots") { value(0) }
        }
        assertThat(bodyOf(result).size()).isEqualTo(3)
    }

    @Test
    fun `DI43_anOverbookedLessonShowsZeroFreeSpotsAndTheOverbookedFlag`() {
        val slotId = slot(capacity = 1)
        book(slotId, "2026-11-24")
        book(slotId, "2026-11-24")

        lessons(lead, "from=2026-11-24&to=2026-11-24&slotId=$slotId").andExpect {
            status { isOk() }
            jsonPath("$[0].occupancy") { value(2) }
            jsonPath("$[0].freeSpots") { value(0) }
            jsonPath("$[0].overbooked") { value(true) }
        }
    }

    @Test
    fun `DI56_withoutSlotIdEveryActiveSlotIsListedSortedByDateThenStartTime`() {
        val late = slot(day = 2, start = "20:30", end = "22:00")
        val early = slot(day = 2, start = "09:00", end = "11:00")
        val inactive = slot(day = 2, start = "12:00", end = "13:00", active = false)

        val ids = bodyOf(lessons(lead, "from=2026-11-24&to=2026-11-24")).values().map { it["slotId"].asLong() }

        assertThat(ids).contains(early, late).doesNotContain(inactive)
        assertThat(ids.indexOf(early)).isLessThan(ids.indexOf(late))
    }

    @Test
    fun `DI56_anInactiveSlotGivesAnEmptyList`() {
        val slotId = slot(active = false)

        lessons(lead, "from=2026-11-17&to=2026-12-01&slotId=$slotId").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(0) }
        }
    }

    @Test
    fun `DI56_anUnknownSlotIsNotFound`() {
        lessons(lead, "from=2026-11-17&to=2026-12-01&slotId=999999999").isError(404, "not_found")
    }

    @Test
    fun `DI56_anInvertedRangeIsRejected`() {
        lessons(lead, "from=2026-12-01&to=2026-11-30").isError(400, "invalid_date_range")
    }

    @Test
    fun `DI56_aRangeOfMoreThanThreeMonthsIsRejected`() {
        lessons(lead, "from=2026-11-19&to=2027-02-19").andExpect { status { isOk() } }
        lessons(lead, "from=2026-11-19&to=2027-02-20").isError(400, "invalid_date_range")
    }

    @Test
    fun `DI56_aMissingDateIsAValidationError`() {
        lessons(lead, "from=2026-11-19").isError(400, "validation_error")
    }

    @Test
    fun `DI56_withoutATokenTheAnswerIs401`() {
        lessons(null, "from=2026-11-19&to=2026-11-20").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `DI56_onlyTheLeadTeacherMayList`() {
        lessons(tokenFor(Role.STUDENT), "from=2026-11-19&to=2026-11-20").isError(403, "role_not_allowed")
        lessons(tokenFor(Role.ASSISTANT_TEACHER), "from=2026-11-19&to=2026-11-20").isError(403, "role_not_allowed")
    }
}
