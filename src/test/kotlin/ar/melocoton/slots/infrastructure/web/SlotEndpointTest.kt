package ar.melocoton.slots.infrastructure.web

import ar.melocoton.auth.infrastructure.security.JwtSessionTokens
import ar.melocoton.schema.SchemaFixtures
import ar.melocoton.shared.domain.Role
import ar.melocoton.shared.domain.WorkshopTime
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Slot management by the lead teacher, end to end against the test database
 * (CU-03; RF-06, RF-07, RF-10; DI-25, DI-43, DI-45). Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SlotEndpointTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val json: ObjectMapper,
    @Autowired private val tokens: JwtSessionTokens,
    @Autowired private val entityManager: EntityManager,
) {
    private val fixtures = SchemaFixtures(jdbc)
    private val today = LocalDate.now(WorkshopTime.ZONE)

    private fun tokenFor(role: Role): String {
        val id = jdbc.queryForObject(
            """INSERT INTO app_user (name, email, password_hash, role, phone, active)
               VALUES ('Ana', ?, 'hash', ?, '1100000000', true) RETURNING id""",
            Long::class.javaObjectType, "${UUID.randomUUID()}@test.local", role.name,
        )!!
        val validFrom = jdbc.queryForObject("SELECT sessions_valid_from FROM app_user WHERE id = ?", Instant::class.java, id)!!
        return tokens.issue(id, role, validFrom, Instant.now()).value
    }

    private val lead get() = tokenFor(Role.LEAD_TEACHER)

    private fun body(day: Int = 2, start: String = "18:00", end: String = "20:00", capacity: Int = 8) =
        mapOf("dayOfWeek" to day, "startTime" to start, "endTime" to end, "capacity" to capacity)

    private fun create(token: String?, body: Map<String, Any?>) = mvc.post("/api/v1/slots") {
        token?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
        contentType = MediaType.APPLICATION_JSON
        content = json.writeValueAsString(body)
    }

    private fun patch(token: String?, id: Long, body: Map<String, Any?>) = mvc.patch("/api/v1/slots/$id") {
        token?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
        contentType = MediaType.APPLICATION_JSON
        content = json.writeValueAsString(body)
    }

    private fun list(token: String?, query: String = "") = mvc.get("/api/v1/slots$query") {
        token?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
    }

    private fun get(token: String?, id: Long) = mvc.get("/api/v1/slots/$id") {
        token?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
    }

    private fun createdId(token: String, body: Map<String, Any?>): Long =
        json.readTree(create(token, body).andReturn().response.contentAsString)["id"].asLong()

    private fun idsOf(result: ResultActionsDsl): List<Long> =
        json.readTree(result.andReturn().response.contentAsString).values().map { it["id"].asLong() }

    private fun ResultActionsDsl.isValidationError() = andExpect {
        status { isBadRequest() }
        jsonPath("$.error.code") { value("validation_error") }
    }

    /** Writes pending changes and forgets loaded entities, so JDBC and the use cases see the same rows. */
    private fun sync() {
        entityManager.flush()
        entityManager.clear()
    }

    private fun deactivate(id: Long) {
        sync()
        jdbc.update("UPDATE slot SET active = false WHERE id = ?", id)
    }

    private fun bookings(slotId: Long, date: LocalDate, status: String, count: Int) = repeat(count) {
        fixtures.booking(
            studentId = fixtures.monthlyStudent(),
            slotId = slotId,
            status = status,
            cancellationReason = if (status == "CANCELLED") "STUDENT" else null,
            date = date,
        )
    }

    // ---- create ----

    @Test
    fun `RF06_theLeadTeacherCreatesASlot`() {
        create(lead, body(day = 2, start = "18:00", end = "20:00", capacity = 8)).andExpect {
            status { isCreated() }
            jsonPath("$.id") { isNumber() }
            jsonPath("$.dayOfWeek") { value(2) }
            jsonPath("$.startTime") { value("18:00") }
            jsonPath("$.endTime") { value("20:00") }
            jsonPath("$.capacity") { value(8) }
            jsonPath("$.active") { value(true) }
        }
    }

    @Test
    fun `RF06_invalidDataIsAValidationError`() {
        val lead = lead

        create(lead, body(day = 0)).isValidationError()
        create(lead, body(day = 8)).isValidationError()
        create(lead, body(start = "20:00", end = "18:00")).isValidationError()
        create(lead, body(start = "18:00", end = "18:00")).isValidationError()
        create(lead, body(capacity = 0)).isValidationError()
        create(lead, body(capacity = -3)).isValidationError()
        create(lead, body() - "capacity").isValidationError()
        create(lead, body() - "dayOfWeek").isValidationError()
        create(lead, body(start = "25:00")).isValidationError()
        create(lead, body(start = "tarde")).isValidationError()
    }

    @Test
    fun `DI25_aSlotOverlappingAnActiveOneIsRejectedWithTheConflictingSlot`() {
        val lead = lead
        val existing = createdId(lead, body(day = 2, start = "18:00", end = "20:00"))

        create(lead, body(day = 2, start = "19:00", end = "21:00")).andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("slot_overlap") }
            jsonPath("$.error.message") { value("Se superpone con el turno del martes de 18:00 a 20:00.") }
            jsonPath("$.error.details.slotId") { value(existing) }
            jsonPath("$.error.details.dayOfWeek") { value(2) }
            jsonPath("$.error.details.startTime") { value("18:00") }
            jsonPath("$.error.details.endTime") { value("20:00") }
        }
    }

    @Test
    fun `DI25_touchingSlotsAndOtherDaysAreAllowed`() {
        val lead = lead
        createdId(lead, body(day = 2, start = "18:00", end = "20:00"))

        create(lead, body(day = 2, start = "20:00", end = "22:00")).andExpect { status { isCreated() } }
        create(lead, body(day = 3, start = "18:00", end = "20:00")).andExpect { status { isCreated() } }
    }

    @Test
    fun `DI25_anInactiveSlotDoesNotBlockANewOne`() {
        val lead = lead
        val id = createdId(lead, body(day = 4))
        deactivate(id)

        create(lead, body(day = 4)).andExpect { status { isCreated() } }
    }

    @Test
    fun `DI34_errorsWithoutDetailsDoNotCarryTheField`() {
        val response = create(lead, body(capacity = 0)).andReturn().response.contentAsString

        assertThat(json.readTree(response)["error"].has("details")).isFalse()
    }

    // ---- roles ----

    @Test
    fun `CP22_assistantTeachersAndStudentsCannotUseSlots`() {
        val lead = lead
        val id = createdId(lead, body())
        for (role in listOf(Role.ASSISTANT_TEACHER, Role.STUDENT)) {
            val token = tokenFor(role)
            for (result in listOf(create(token, body()), list(token), get(token, id), patch(token, id, mapOf("capacity" to 5)))) {
                result.andExpect {
                    status { isForbidden() }
                    jsonPath("$.error.code") { value("role_not_allowed") }
                }
            }
        }
    }

    @Test
    fun `CP22_requestsWithoutASessionAreUnauthorized`() {
        create(null, body()).andExpect { status { isUnauthorized() } }
        list(null).andExpect { status { isUnauthorized() } }
        get(null, 1).andExpect { status { isUnauthorized() } }
        patch(null, 1, mapOf("capacity" to 5)).andExpect { status { isUnauthorized() } }
    }

    // ---- list and get ----

    @Test
    fun `RF07_listFiltersByStatusAndOrdersByDayAndStartTime`() {
        val lead = lead
        val saturdayMorning = createdId(lead, body(day = 6, start = "09:00", end = "11:00"))
        val mondayLate = createdId(lead, body(day = 1, start = "19:00", end = "21:00"))
        val mondayEarly = createdId(lead, body(day = 1, start = "10:00", end = "12:00"))
        val inactive = createdId(lead, body(day = 3))
        deactivate(inactive)
        val mine = setOf(saturdayMorning, mondayLate, mondayEarly, inactive)

        assertThat(idsOf(list(lead)).filter { it in mine }).containsExactly(mondayEarly, mondayLate, saturdayMorning)
        assertThat(idsOf(list(lead, "?status=ACTIVE")).filter { it in mine }).containsExactly(mondayEarly, mondayLate, saturdayMorning)
        assertThat(idsOf(list(lead, "?status=INACTIVE")).filter { it in mine }).containsExactly(inactive)
        assertThat(idsOf(list(lead, "?status=ALL")).filter { it in mine })
            .containsExactly(mondayEarly, mondayLate, inactive, saturdayMorning)
        list(lead, "?status=MAYBE").isValidationError()
    }

    @Test
    fun `RF07_getReturnsTheSlotOrNotFound`() {
        val lead = lead
        val id = createdId(lead, body(capacity = 6))

        get(lead, id).andExpect {
            status { isOk() }
            jsonPath("$.id") { value(id) }
            jsonPath("$.capacity") { value(6) }
            jsonPath("$.upcomingOverbookedLessons") { isEmpty() }
        }
        get(lead, Long.MAX_VALUE).andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("not_found") }
        }
    }

    @Test
    fun `DI34_nonNumericIdIsNotFound`() {
        val lead = lead
        mvc.get("/api/v1/slots/abc") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $lead")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("not_found") }
        }
        mvc.patch("/api/v1/slots/abc") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $lead")
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("not_found") }
        }
    }

    @Test
    fun `DI43_aFutureLessonAboveTheCapacityShowsAsOverbooked`() {
        val lead = lead
        val id = createdId(lead, body(capacity = 2))
        val date = today.plusDays(10)
        // Fixed bookings may exceed the capacity (DI-43); a cancelled one does not count.
        bookings(id, date, "SCHEDULED", 3)
        bookings(id, date, "CANCELLED", 2)
        // A lesson in the past is never listed.
        bookings(id, today.minusDays(10), "PRESENT", 4)

        get(lead, id).andExpect {
            jsonPath("$.upcomingOverbookedLessons.length()") { value(1) }
            jsonPath("$.upcomingOverbookedLessons[0].lessonDate") { value(date.toString()) }
            jsonPath("$.upcomingOverbookedLessons[0].occupancy") { value(3) }
        }
        val item = json.readTree(list(lead).andReturn().response.contentAsString).first { it["id"].asLong() == id }
        assertThat(item["upcomingOverbookedLessons"].size()).isEqualTo(1)
    }

    // ---- patch ----

    @Test
    fun `CU03_theCapacityIsEdited`() {
        val lead = lead
        val id = createdId(lead, body(capacity = 8))

        patch(lead, id, mapOf("capacity" to 10)).andExpect {
            status { isOk() }
            jsonPath("$.capacity") { value(10) }
            jsonPath("$.upcomingOverbookedLessons") { isEmpty() }
        }
        sync()
        assertThat(jdbc.queryForObject("SELECT capacity FROM slot WHERE id = ?", Int::class.java, id)).isEqualTo(10)
    }

    @Test
    fun `RF06_theTimesAreStoredAsEnteredWithTheJvmInBuenosAires`() {
        val original = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Argentina/Buenos_Aires"))
        try {
            val lead = lead
            val id = createdId(lead, body(start = "21:30", end = "23:45"))
            val moved = createdId(lead, body(day = 3, start = "18:00", end = "20:00"))
            patch(lead, moved, mapOf("startTime" to "17:15"))
            sync()

            val row = jdbc.queryForMap("SELECT start_time::text AS start, end_time::text AS finish FROM slot WHERE id = ?", id)
            assertThat(row["start"]).isEqualTo("21:30:00")
            assertThat(row["finish"]).isEqualTo("23:45:00")
            assertThat(jdbc.queryForObject("SELECT start_time::text FROM slot WHERE id = ?", String::class.java, moved))
                .isEqualTo("17:15:00")
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }

    @Test
    fun `CU03_aSlotWithNoBookingsChangesDayAndTime`() {
        val lead = lead
        val id = createdId(lead, body(day = 2, start = "18:00", end = "20:00"))

        patch(lead, id, mapOf("dayOfWeek" to 4, "startTime" to "10:00", "endTime" to "12:00")).andExpect {
            status { isOk() }
            jsonPath("$.dayOfWeek") { value(4) }
            jsonPath("$.startTime") { value("10:00") }
            jsonPath("$.endTime") { value("12:00") }
        }
    }

    @Test
    fun `CU03_E1_theCapacityCannotGoBelowTheOccupancyOfFutureLessons`() {
        val lead = lead
        val id = createdId(lead, body(capacity = 10))
        val first = today.plusDays(7)
        val second = today.plusDays(14)
        val fine = today.plusDays(21)
        bookings(id, first, "SCHEDULED", 6)
        bookings(id, first, "PRESENT", 1)
        bookings(id, second, "SCHEDULED", 5)
        bookings(id, fine, "SCHEDULED", 3)
        bookings(id, fine, "ABSENT", 4)

        patch(lead, id, mapOf("capacity" to 4)).andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("capacity_below_occupancy") }
            jsonPath("$.error.details.lessons.length()") { value(2) }
            jsonPath("$.error.details.lessons[0].lessonDate") { value(first.toString()) }
            jsonPath("$.error.details.lessons[0].occupancy") { value(7) }
            jsonPath("$.error.details.lessons[0].spotsToFree") { value(3) }
            jsonPath("$.error.details.lessons[1].lessonDate") { value(second.toString()) }
            jsonPath("$.error.details.lessons[1].spotsToFree") { value(1) }
        }
        sync()
        assertThat(jdbc.queryForObject("SELECT capacity FROM slot WHERE id = ?", Int::class.java, id)).isEqualTo(10)
        // Exactly the highest occupancy fits.
        patch(lead, id, mapOf("capacity" to 7)).andExpect { status { isOk() } }
    }

    @Test
    fun `CU03_E3_pastLessonsDoNotBlockALowerCapacity`() {
        val lead = lead
        val id = createdId(lead, body(capacity = 10))
        bookings(id, today.minusDays(7), "PRESENT", 9)

        patch(lead, id, mapOf("capacity" to 2)).andExpect {
            status { isOk() }
            jsonPath("$.capacity") { value(2) }
        }
    }

    @Test
    fun `DI45_dayAndTimeCannotChangeWhenTheSlotHasBookingsOfAnyStatus`() {
        val lead = lead
        val id = createdId(lead, body())
        bookings(id, today.minusDays(30), "CANCELLED", 1)

        patch(lead, id, mapOf("startTime" to "17:00")).andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("slot_has_bookings") }
        }
        patch(lead, id, mapOf("dayOfWeek" to 5)).andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("slot_has_bookings") }
        }
        // The capacity is still editable.
        patch(lead, id, mapOf("capacity" to 12)).andExpect { status { isOk() } }
    }

    @Test
    fun `DI25_moveToAnOverlappingTimeIsRejectedButTheSlotIgnoresItself`() {
        val lead = lead
        val first = createdId(lead, body(day = 2, start = "18:00", end = "20:00"))
        val second = createdId(lead, body(day = 2, start = "20:00", end = "22:00"))

        patch(lead, second, mapOf("startTime" to "19:00")).andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("slot_overlap") }
            jsonPath("$.error.details.slotId") { value(first) }
        }
        patch(lead, first, mapOf("startTime" to "17:00")).andExpect { status { isOk() } }
    }

    @Test
    fun `DI45_anInactiveSlotCannotBeEdited`() {
        val lead = lead
        val id = createdId(lead, body())
        deactivate(id)

        patch(lead, id, mapOf("capacity" to 3)).andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("status_conflict") }
        }
    }

    @Test
    fun `CU03_patchValidationAndNotFound`() {
        val lead = lead
        val id = createdId(lead, body(start = "18:00", end = "20:00"))

        patch(lead, id, emptyMap()).isValidationError()
        patch(lead, id, mapOf("capacity" to 0)).isValidationError()
        patch(lead, id, mapOf("dayOfWeek" to 9)).isValidationError()
        patch(lead, id, mapOf("startTime" to "21:00")).isValidationError()
        patch(lead, Long.MAX_VALUE, mapOf("capacity" to 5)).andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("not_found") }
        }
    }
}
