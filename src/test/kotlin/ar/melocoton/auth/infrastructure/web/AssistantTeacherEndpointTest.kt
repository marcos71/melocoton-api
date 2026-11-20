package ar.melocoton.auth.infrastructure.web

import ar.melocoton.auth.infrastructure.security.JwtSessionTokens
import ar.melocoton.shared.domain.Role
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * Assistant teacher accounts managed by the lead teacher, end to end against the test database
 * (CU-02 D1, D1a; RF-53; DI-32, DI-34; first part of CP-22). Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AssistantTeacherEndpointTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val json: ObjectMapper,
    @Autowired private val tokens: JwtSessionTokens,
    @Autowired private val entityManager: EntityManager,
) {
    private fun user(role: Role, active: Boolean = true, name: String = "Ana"): Pair<Long, String> {
        val email = "${UUID.randomUUID()}@test.local"
        val id = jdbc.queryForObject(
            """INSERT INTO app_user (name, email, password_hash, role, phone, active)
               VALUES (?, ?, ?, ?, '1100000000', ?) RETURNING id""",
            Long::class.javaObjectType, name, email, PASSWORD_HASH, role.name, active,
        )!!
        return id to email
    }

    private fun tokenFor(id: Long, role: Role): String {
        val validFrom = jdbc.queryForObject("SELECT sessions_valid_from FROM app_user WHERE id = ?", Instant::class.java, id)!!
        return tokens.issue(id, role, validFrom, Instant.now()).value
    }

    private fun leadToken() = user(Role.LEAD_TEACHER).let { (id, _) -> tokenFor(id, Role.LEAD_TEACHER) }

    private fun login(email: String, password: String) = mvc.post("/api/v1/auth/login") {
        contentType = MediaType.APPLICATION_JSON
        content = json.writeValueAsString(mapOf("email" to email, "password" to password))
    }

    private fun create(token: String?, body: Map<String, Any?>) = mvc.post("/api/v1/assistant-teachers") {
        token?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
        contentType = MediaType.APPLICATION_JSON
        content = json.writeValueAsString(body)
    }

    private fun validBody(email: String = "${UUID.randomUUID()}@test.local") =
        mapOf("name" to "Lucía", "email" to email, "phone" to "1155550000")

    private fun deactivate(token: String?, id: Long) = mvc.post("/api/v1/assistant-teachers/$id/deactivate") {
        token?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
    }

    private fun list(token: String?, query: String = "") = mvc.get("/api/v1/assistant-teachers$query") {
        token?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
    }

    private fun idsOf(result: ResultActionsDsl): List<Long> =
        json.readTree(result.andReturn().response.contentAsString).values().map { it["id"].asLong() }

    private fun ResultActionsDsl.isValidationError() = andExpect {
        status { isBadRequest() }
        jsonPath("$.error.code") { value("validation_error") }
    }

    private fun ResultActionsDsl.isForbidden() = andExpect {
        status { isForbidden() }
        jsonPath("$.error.code") { value("role_not_allowed") }
    }

    @Test
    fun `RF53_theLeadTeacherCreatesAnAssistantTeacherWithoutPasswordAndWithAnInvitationLink`() {
        val body = validBody()
        val response = create(leadToken(), body).andExpect {
            status { isCreated() }
            jsonPath("$.name") { value("Lucía") }
            jsonPath("$.email") { value(body["email"]) }
            jsonPath("$.phone") { value("1155550000") }
            jsonPath("$.active") { value(true) }
        }.andReturn().response.contentAsString
        val id = json.readTree(response)["id"].asLong()

        val row = jdbc.queryForMap("SELECT role, password_hash, active FROM app_user WHERE id = ?", id)
        assertThat(row["role"]).isEqualTo("ASSISTANT_TEACHER")
        assertThat(row["password_hash"]).isNull()
        assertThat(row["active"]).isEqualTo(true)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account_link WHERE user_id = ? AND purpose = 'INVITATION'", Int::class.java, id,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `RF53_theTextFieldsAreTrimmed`() {
        create(leadToken(), mapOf("name" to "  Lucía ", "email" to "${UUID.randomUUID()}@test.local", "phone" to " 1155550000 "))
            .andExpect {
                status { isCreated() }
                jsonPath("$.name") { value("Lucía") }
                jsonPath("$.phone") { value("1155550000") }
            }
    }

    @Test
    fun `DI34_missingOrBlankFieldsAndABadEmailAreValidationErrors`() {
        val lead = leadToken()

        create(lead, validBody() - "phone").isValidationError()
        create(lead, validBody() + ("phone" to "  ")).isValidationError()
        create(lead, validBody() + ("name" to " ")).isValidationError()
        create(lead, validBody() + ("name" to "")).isValidationError()
        create(lead, validBody() - "name").isValidationError()
        create(lead, validBody() - "email").isValidationError()
        create(lead, validBody("not-an-email")).isValidationError()
        create(lead, validBody("a@b")).isValidationError()
        create(lead, validBody("a@b.")).isValidationError()
        create(lead, validBody("a@.com")).isValidationError()
    }

    @Test
    fun `DI34_aPaddedEmailIsAcceptedAndStoredTrimmed`() {
        val email = "${UUID.randomUUID()}@test.local"

        val response = create(leadToken(), validBody(" $email ")).andExpect {
            status { isCreated() }
            jsonPath("$.email") { value(email) }
        }.andReturn().response.contentAsString

        val id = json.readTree(response)["id"].asLong()
        assertThat(jdbc.queryForObject("SELECT email FROM app_user WHERE id = ?", String::class.java, id)).isEqualTo(email)
    }

    @Test
    fun `DI34_aPaddedEmailOfAnotherAccountIsAConflictIgnoringCase`() {
        val (_, email) = user(Role.STUDENT)

        create(leadToken(), validBody("  ${email.uppercase()} ")).andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("email_already_used") }
        }
    }

    @Test
    fun `RF53_anActiveFilterThatIsNotABooleanIsAValidationError`() {
        list(leadToken(), "?active=maybe").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("validation_error") }
            jsonPath("$.error.message") { value("Los datos enviados no son válidos.") }
        }
    }

    @Test
    fun `RF53_listingFiltersByActiveAndReturnsOnlyAssistantTeachersByName`() {
        val lead = leadToken()
        val (zoe, _) = user(Role.ASSISTANT_TEACHER, name = "zzz-Zoe")
        val (abel, _) = user(Role.ASSISTANT_TEACHER, name = "zzz-abel")
        val (inactive, _) = user(Role.ASSISTANT_TEACHER, active = false, name = "zzz-Beto")
        val (student, _) = user(Role.STUDENT, name = "zzz-Alumna")
        val mine = setOf(zoe, abel, inactive, student)

        val all = idsOf(list(lead)).filter { it in mine }
        val active = idsOf(list(lead, "?active=true")).filter { it in mine }
        val inactiveOnly = idsOf(list(lead, "?active=false")).filter { it in mine }

        // Ordered by name ignoring case: abel, Beto, Zoe; the student never appears.
        assertThat(all).containsExactly(abel, inactive, zoe)
        assertThat(active).containsExactly(abel, zoe)
        assertThat(inactiveOnly).containsExactly(inactive)
        list(lead, "?active=true").andExpect {
            status { isOk() }
            jsonPath("$[0].email") { exists() }
            jsonPath("$[0].phone") { exists() }
            jsonPath("$[0].active") { value(true) }
        }
    }

    @Test
    fun `RF53_deactivatingMakesTheAccountInactiveAndCutsItsSessions`() {
        val (id, email) = user(Role.ASSISTANT_TEACHER)
        val own = json.readTree(login(email, PASSWORD).andReturn().response.contentAsString)["accessToken"].asString()
        mvc.get("/api/v1/auth/session") { header(HttpHeaders.AUTHORIZATION, "Bearer $own") }
            .andExpect { status { isOk() } }

        deactivate(leadToken(), id).andExpect {
            status { isOk() }
            jsonPath("$.id") { value(id) }
            jsonPath("$.active") { value(false) }
        }

        entityManager.flush()
        assertThat(jdbc.queryForObject("SELECT active FROM app_user WHERE id = ?", Boolean::class.java, id)).isFalse()
        mvc.get("/api/v1/auth/session") { header(HttpHeaders.AUTHORIZATION, "Bearer $own") }
            .andExpect { status { isUnauthorized() } }
        login(email, PASSWORD).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `RF53_deactivatingAnInactiveAccountIsAStatusConflict`() {
        val lead = leadToken()
        val (id, _) = user(Role.ASSISTANT_TEACHER)
        deactivate(lead, id).andExpect { status { isOk() } }

        deactivate(lead, id).andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("status_conflict") }
            jsonPath("$.error.message") { value("La cuenta ya está inactiva.") }
        }
    }

    @Test
    fun `DI34_deactivatingAStudentALeadTeacherOrAMissingIdIsNotFound`() {
        val lead = leadToken()
        val (student, _) = user(Role.STUDENT)
        val (otherLead, _) = user(Role.LEAD_TEACHER)

        listOf(student, otherLead, Long.MAX_VALUE).forEach { id ->
            deactivate(lead, id).andExpect {
                status { isNotFound() }
                jsonPath("$.error.code") { value("not_found") }
                jsonPath("$.error.message") { value("No existe ese docente secundario.") }
            }
        }
        assertThat(jdbc.queryForObject("SELECT active FROM app_user WHERE id = ?", Boolean::class.java, student)).isTrue()
    }

    @Test
    fun `DI34_nonNumericIdIsNotFound`() {
        mvc.post("/api/v1/assistant-teachers/abc/deactivate") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${leadToken()}")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("not_found") }
        }
    }

    @Test
    fun `CP22_studentsAndAssistantTeachersGetForbiddenOnEveryAssistantTeacherEndpoint`() {
        val (target, _) = user(Role.ASSISTANT_TEACHER)
        val (studentId, _) = user(Role.STUDENT)
        val (assistantId, _) = user(Role.ASSISTANT_TEACHER)

        listOf(tokenFor(studentId, Role.STUDENT), tokenFor(assistantId, Role.ASSISTANT_TEACHER)).forEach { token ->
            list(token).isForbidden()
            create(token, validBody()).isForbidden()
            deactivate(token, target).isForbidden()
        }
        assertThat(jdbc.queryForObject("SELECT active FROM app_user WHERE id = ?", Boolean::class.java, target)).isTrue()
    }

    @Test
    fun `CP22_withoutATokenEveryAssistantTeacherEndpointIsUnauthorized`() {
        val (target, _) = user(Role.ASSISTANT_TEACHER)

        listOf(list(null), create(null, validBody()), deactivate(null, target)).forEach {
            it.andExpect {
                status { isUnauthorized() }
                jsonPath("$.error.code") { value("not_authenticated") }
                jsonPath("$.error.message") { exists() }
            }
        }
    }

    companion object {
        private const val PASSWORD = "la-contraseña-vieja"

        // Argon2 is slow on purpose: hash once for the class, not once per test.
        private val PASSWORD_HASH = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(PASSWORD)!!
    }
}
