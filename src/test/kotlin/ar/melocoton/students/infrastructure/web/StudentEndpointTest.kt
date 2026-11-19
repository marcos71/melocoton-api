package ar.melocoton.students.infrastructure.web

import ar.melocoton.auth.infrastructure.security.JwtSessionTokens
import ar.melocoton.schema.SchemaFixtures
import ar.melocoton.shared.domain.Role
import ar.melocoton.shared.domain.WorkshopTime
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
 * Student management by the lead teacher, end to end against the test database
 * (CU-02; RF-04, RF-05; DI-34, DI-46). Each test is rolled back. Duplicate emails and the invitation
 * email are in [StudentInvitationTest], which commits.
 *
 * Other tests may leave committed students behind, so list tests narrow by a fresh fixed slot or
 * check only the ids they created.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudentEndpointTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val json: ObjectMapper,
    @Autowired private val tokens: JwtSessionTokens,
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

    private fun body(
        name: String = "Lucía",
        email: String = "${UUID.randomUUID()}@test.local",
        phone: String = "1155550000",
        type: String = "MONTHLY",
        start: String? = null,
    ): Map<String, Any?> = buildMap {
        put("name", name)
        put("email", email)
        put("phone", phone)
        put("enrollmentType", type)
        start?.let { put("monthlyStartDate", it) }
    }

    private fun create(token: String?, body: Map<String, Any?>) = mvc.post("/api/v1/students") {
        token?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
        contentType = MediaType.APPLICATION_JSON
        content = json.writeValueAsString(body)
    }

    private fun patch(token: String?, id: Long, body: Map<String, Any?>) = mvc.patch("/api/v1/students/$id") {
        token?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
        contentType = MediaType.APPLICATION_JSON
        content = json.writeValueAsString(body)
    }

    private fun list(token: String?, query: String = "") = mvc.get("/api/v1/students$query") {
        token?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
    }

    private fun get(token: String?, path: String) = mvc.get("/api/v1/students/$path") {
        token?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
    }

    private fun idsOf(result: ResultActionsDsl): List<Long> =
        json.readTree(result.andReturn().response.contentAsString).values().map { it["id"].asLong() }

    private fun namesOf(result: ResultActionsDsl): List<String> =
        json.readTree(result.andReturn().response.contentAsString).values().map { it["name"].asString() }

    private fun ResultActionsDsl.isValidationError() = andExpect {
        status { isBadRequest() }
        jsonPath("$.error.code") { value("validation_error") }
    }

    private fun ResultActionsDsl.isNotFound() = andExpect {
        status { isNotFound() }
        jsonPath("$.error.code") { value("not_found") }
    }

    /** A student row with a chosen name, modality, state and fixed slot; returns the user id. */
    private fun student(
        name: String,
        monthly: Boolean = true,
        active: Boolean = true,
        slotId: Long? = null,
    ): Long {
        val userId = jdbc.queryForObject(
            """INSERT INTO app_user (name, email, password_hash, role, phone, active)
               VALUES (?, ?, 'hash', 'STUDENT', '1100000000', ?) RETURNING id""",
            Long::class.javaObjectType, name, "${UUID.randomUUID()}@test.local", active,
        )!!
        if (monthly) {
            jdbc.update(
                "INSERT INTO student (user_id, enrollment_type, fixed_slot_id, monthly_start_date) VALUES (?, 'MONTHLY', ?, ?)",
                userId, slotId, LocalDate.of(2026, 3, 1),
            )
        } else {
            jdbc.update("INSERT INTO student (user_id, enrollment_type) VALUES (?, 'DROP_IN')", userId)
        }
        return userId
    }

    // ---- create ----

    @Test
    fun `RF04_aMonthlyStudentWithoutStartDateStartsToday`() {
        val email = "${UUID.randomUUID()}@test.local"

        val response = create(lead, body(name = "  Lucía ", email = " $email ", type = "MONTHLY")).andExpect {
            status { isCreated() }
            jsonPath("$.id") { isNumber() }
            jsonPath("$.name") { value("Lucía") }
            jsonPath("$.email") { value(email) }
            jsonPath("$.phone") { value("1155550000") }
            jsonPath("$.active") { value(true) }
            jsonPath("$.enrollmentType") { value("MONTHLY") }
            jsonPath("$.monthlyStartDate") { value(today.toString()) }
            jsonPath("$.fixedSlot") { doesNotExist() }
        }.andReturn().response.contentAsString

        val id = json.readTree(response)["id"].asLong()
        val account = jdbc.queryForMap("SELECT role, active, password_hash FROM app_user WHERE id = ?", id)
        assertThat(account).containsEntry("role", "STUDENT").containsEntry("active", true).containsEntry("password_hash", null)
        val row = jdbc.queryForMap("SELECT enrollment_type, fixed_slot_id, monthly_start_date FROM student WHERE user_id = ?", id)
        assertThat(row).containsEntry("enrollment_type", "MONTHLY").containsEntry("fixed_slot_id", null)
        assertThat(row["monthly_start_date"].toString()).isEqualTo(today.toString())
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_link WHERE user_id = ?", Int::class.java, id)).isEqualTo(1)
    }

    @Test
    fun `RF04_aMonthlyStudentKeepsTheStartDateItIsGiven`() {
        create(lead, body(type = "MONTHLY", start = "2026-12-01")).andExpect {
            status { isCreated() }
            jsonPath("$.monthlyStartDate") { value("2026-12-01") }
        }
    }

    @Test
    fun `RF05_aDropInStudentIsCreatedWithoutStartDate`() {
        create(lead, body(type = "DROP_IN")).andExpect {
            status { isCreated() }
            jsonPath("$.enrollmentType") { value("DROP_IN") }
            jsonPath("$.monthlyStartDate") { doesNotExist() }
            jsonPath("$.fixedSlot") { doesNotExist() }
        }
    }

    @Test
    fun `RF05_aDropInStudentWithAStartDateIsRejected`() {
        create(lead, body(type = "DROP_IN", start = "2026-12-01")).isValidationError()
    }

    @Test
    fun `RF04_theRequiredFieldsMustBePresentAndNotBlank`() {
        val token = lead
        create(token, body(name = "   ")).isValidationError()
        create(token, body(phone = "")).isValidationError()
        create(token, body(email = " ")).isValidationError()
        create(token, body() - "name").isValidationError()
        create(token, body() - "email").isValidationError()
        create(token, body() - "phone").isValidationError()
        create(token, body() - "enrollmentType").isValidationError()
    }

    @Test
    fun `RF04_anInvalidEmailModalityOrDateIsRejected`() {
        val token = lead
        create(token, body(email = "no-es-un-email")).isValidationError()
        create(token, body(email = "a@b")).isValidationError()
        create(token, body(type = "WEEKLY")).isValidationError()
        create(token, body(type = "MONTHLY", start = "01/12/2026")).isValidationError()
    }

    // ---- list ----

    @Test
    fun `RF05_theListShowsOnlyActiveStudentsByDefaultAndFiltersByStatus`() {
        val active = student("Activa")
        val inactive = student("Inactiva", active = false)
        val token = lead

        assertThat(idsOf(list(token))).contains(active).doesNotContain(inactive)
        assertThat(idsOf(list(token, "?status=ACTIVE"))).contains(active).doesNotContain(inactive)
        assertThat(idsOf(list(token, "?status=INACTIVE"))).contains(inactive).doesNotContain(active)
        assertThat(idsOf(list(token, "?status=ALL"))).contains(active, inactive)
    }

    @Test
    fun `RF05_theListFiltersByModality`() {
        val monthly = student("Mensual")
        val dropIn = student("Suelta", monthly = false)
        val token = lead

        assertThat(idsOf(list(token, "?enrollmentType=MONTHLY"))).contains(monthly).doesNotContain(dropIn)
        assertThat(idsOf(list(token, "?enrollmentType=DROP_IN"))).contains(dropIn).doesNotContain(monthly)
    }

    @Test
    fun `RF05_theListFiltersByFixedSlotAndIsOrderedByNameIgnoringCase`() {
        val slotId = fixtures.slot()
        val otherSlotId = fixtures.slot()
        val zoe = student("Zoe", slotId = slotId)
        val alba = student("alba", slotId = slotId)
        val beto = student("Beto", slotId = slotId)
        val elsewhere = student("Aaa elsewhere", slotId = otherSlotId)
        student("Sin turno")
        val token = lead

        val result = list(token, "?slotId=$slotId")

        assertThat(idsOf(result)).containsExactly(alba, beto, zoe).doesNotContain(elsewhere)
        assertThat(namesOf(list(token, "?slotId=$slotId&status=ALL"))).containsExactly("alba", "Beto", "Zoe")
    }

    @Test
    fun `RF05_theListItemsCarryModalityStateAndFixedSlot`() {
        val slotId = fixtures.slot()
        student("Con turno", slotId = slotId)

        list(lead, "?slotId=$slotId").andExpect {
            status { isOk() }
            jsonPath("$[0].name") { value("Con turno") }
            jsonPath("$[0].enrollmentType") { value("MONTHLY") }
            jsonPath("$[0].active") { value(true) }
            jsonPath("$[0].fixedSlot.id") { value(slotId) }
            jsonPath("$[0].fixedSlot.dayOfWeek") { value(2) }
            jsonPath("$[0].fixedSlot.startTime") { value("18:00") }
            jsonPath("$[0].fixedSlot.endTime") { value("20:00") }
        }
    }

    @Test
    fun `RF05_theListDoesNotIncludeOtherRoles`() {
        val assistant = fixtures.user("ASSISTANT_TEACHER")
        val lead = fixtures.user("LEAD_TEACHER")
        val ids = idsOf(list(this.lead, "?status=ALL"))

        assertThat(ids).doesNotContain(assistant, lead)
    }

    @Test
    fun `RF05_anInvalidFilterIsAValidationError`() {
        val token = lead
        list(token, "?status=SOMETIMES").isValidationError()
        list(token, "?enrollmentType=WEEKLY").isValidationError()
        list(token, "?slotId=abc").isValidationError()
    }

    // ---- profile ----

    @Test
    fun `DI46_theProfileShowsTheDataAndTheFixedSlot`() {
        val slotId = fixtures.slot()
        val id = student("Perfil", slotId = null)
        jdbc.update("UPDATE student SET fixed_slot_id = ? WHERE user_id = ?", slotId, id)

        get(lead, "$id").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(id) }
            jsonPath("$.name") { value("Perfil") }
            jsonPath("$.phone") { value("1100000000") }
            jsonPath("$.active") { value(true) }
            jsonPath("$.enrollmentType") { value("MONTHLY") }
            jsonPath("$.monthlyStartDate") { value("2026-03-01") }
            jsonPath("$.fixedSlot.id") { value(slotId) }
            jsonPath("$.fixedSlot.dayOfWeek") { value(2) }
            jsonPath("$.fixedSlot.startTime") { value("18:00") }
            jsonPath("$.fixedSlot.endTime") { value("20:00") }
        }
    }

    @Test
    fun `DI46_theProfileOfADropInStudentHasNoStartDateNorSlot`() {
        val id = student("Suelta", monthly = false)

        get(lead, "$id").andExpect {
            status { isOk() }
            jsonPath("$.enrollmentType") { value("DROP_IN") }
            jsonPath("$.monthlyStartDate") { doesNotExist() }
            jsonPath("$.fixedSlot") { doesNotExist() }
        }
    }

    @Test
    fun `DI46_theProfileOfAnInactiveStudentIsStillThere`() {
        val id = student("Inactiva", active = false)

        get(lead, "$id").andExpect {
            status { isOk() }
            jsonPath("$.active") { value(false) }
        }
    }

    @Test
    fun `DI34_aMissingIdOrAnotherRolesIdIsNotFound`() {
        val token = lead
        get(token, "${fixtures.user("ASSISTANT_TEACHER")}").isNotFound()
        get(token, "${fixtures.user("LEAD_TEACHER")}").isNotFound()
        get(token, "999999999").isNotFound()
    }

    // ---- edit ----

    @Test
    fun `DI46_theLeadTeacherEditsNameAndPhone`() {
        val id = student("Antes")

        patch(lead, id, mapOf("name" to " Después ", "phone" to "1166667777")).andExpect {
            status { isOk() }
            jsonPath("$.name") { value("Después") }
            jsonPath("$.phone") { value("1166667777") }
            jsonPath("$.enrollmentType") { value("MONTHLY") }
        }

        val row = jdbc.queryForMap("SELECT name, phone FROM app_user WHERE id = ?", id)
        assertThat(row).containsEntry("name", "Después").containsEntry("phone", "1166667777")
    }

    @Test
    fun `DI46_editingOnlyTheNameKeepsThePhone`() {
        val id = student("Antes")

        patch(lead, id, mapOf("name" to "Después")).andExpect {
            status { isOk() }
            jsonPath("$.name") { value("Después") }
            jsonPath("$.phone") { value("1100000000") }
        }
    }

    @Test
    fun `DI46_editingOnlyThePhoneKeepsTheName`() {
        val id = student("Antes")

        patch(lead, id, mapOf("phone" to "1177778888")).andExpect {
            status { isOk() }
            jsonPath("$.name") { value("Antes") }
            jsonPath("$.phone") { value("1177778888") }
        }
    }

    @Test
    fun `DI46_theEmailIsNotEditableAndIsNotSilentlyIgnored`() {
        val id = student("Antes")
        val email = jdbc.queryForObject("SELECT email FROM app_user WHERE id = ?", String::class.java, id)

        patch(lead, id, mapOf("name" to "Después", "email" to "otro@test.local")).isValidationError()
        patch(lead, id, mapOf("email" to "otro@test.local")).isValidationError()

        val row = jdbc.queryForMap("SELECT name, email FROM app_user WHERE id = ?", id)
        assertThat(row).containsEntry("name", "Antes").containsEntry("email", email)
    }

    @Test
    fun `DI46_theModalityAndStartDateAreNotEditableHere`() {
        val id = student("Antes")

        patch(lead, id, mapOf("enrollmentType" to "DROP_IN")).isValidationError()
        patch(lead, id, mapOf("name" to "Después", "monthlyStartDate" to "2026-12-01")).isValidationError()
    }

    @Test
    fun `DI46_anEmptyOrBlankEditIsAValidationError`() {
        val id = student("Antes")
        val token = lead

        patch(token, id, emptyMap()).isValidationError()
        patch(token, id, mapOf("name" to "  ")).isValidationError()
        patch(token, id, mapOf("phone" to "")).isValidationError()
    }

    @Test
    fun `DI34_editingAMissingIdOrAnotherRolesIdIsNotFound`() {
        val token = lead
        patch(token, fixtures.user("ASSISTANT_TEACHER"), mapOf("name" to "Nadie")).isNotFound()
        patch(token, 999999999, mapOf("name" to "Nadie")).isNotFound()
    }

    // ---- who may call ----

    @Test
    fun `DI34_noTokenIsUnauthorized`() {
        create(null, body()).andExpect { status { isUnauthorized() } }
        list(null).andExpect { status { isUnauthorized() } }
        get(null, "1").andExpect { status { isUnauthorized() } }
        patch(null, 1, mapOf("name" to "X")).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `DI34_assistantTeachersAndStudentsGetRoleNotAllowed`() {
        val id = student("Alguien")
        listOf(Role.ASSISTANT_TEACHER, Role.STUDENT).forEach { role ->
            val token = tokenFor(role)
            listOf(
                create(token, body()),
                list(token),
                get(token, "$id"),
                patch(token, id, mapOf("name" to "X")),
            ).forEach {
                it.andExpect {
                    status { isForbidden() }
                    jsonPath("$.error.code") { value("role_not_allowed") }
                }
            }
        }
    }

    @Test
    fun `DI46_theLeadTeacherRuleDoesNotCaptureTheStudentsOwnRoutes`() {
        // /api/v1/students/me/** will be the student's own routes later: the lead-only rule must not apply to it.
        val status = get(tokenFor(Role.STUDENT), "me/anything").andReturn().response.status

        assertThat(status).isNotEqualTo(403)
        assertThat(status).isEqualTo(404)
    }
}
