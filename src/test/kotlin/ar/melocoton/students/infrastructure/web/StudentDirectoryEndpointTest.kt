package ar.melocoton.students.infrastructure.web

import ar.melocoton.auth.infrastructure.security.JwtSessionTokens
import ar.melocoton.shared.domain.Role
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The students directory read by the lead and the assistant teacher (RF-24, RF-62, D-29, DI-76), end to end
 * against the test database. Each test is rolled back. Other tests may leave committed students behind, so
 * the tests look only at the ids they created.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudentDirectoryEndpointTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val json: ObjectMapper,
    @Autowired private val tokens: JwtSessionTokens,
) {

    private data class TestUser(val id: Long, val token: String)

    private fun tokenFor(role: Role): String = userWith(role).token

    private fun userWith(role: Role): TestUser {
        val id = jdbc.queryForObject(
            """INSERT INTO app_user (name, email, password_hash, role, phone, active)
               VALUES ('Ana', ?, 'hash', ?, '1100000000', true) RETURNING id""",
            Long::class.javaObjectType, "${UUID.randomUUID()}@test.local", role.name,
        )!!
        val validFrom = jdbc.queryForObject("SELECT sessions_valid_from FROM app_user WHERE id = ?", Instant::class.java, id)!!
        return TestUser(id, tokens.issue(id, role, validFrom, Instant.now()).value)
    }

    private fun student(name: String, phone: String = "1155550000", active: Boolean = true): Long {
        val userId = jdbc.queryForObject(
            """INSERT INTO app_user (name, email, password_hash, role, phone, active)
               VALUES (?, ?, 'hash', 'STUDENT', ?, ?) RETURNING id""",
            Long::class.javaObjectType, name, "${UUID.randomUUID()}@test.local", phone, active,
        )!!
        jdbc.update(
            "INSERT INTO student (user_id, enrollment_type, monthly_start_date) VALUES (?, 'MONTHLY', ?)",
            userId, LocalDate.of(2026, 3, 1),
        )
        return userId
    }

    private fun directory(token: String, query: String = "") = mvc.get("/api/v1/students/directory$query") {
        header(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }

    private fun entries(token: String, query: String = ""): JsonNode =
        json.readTree(directory(token, query).andReturn().response.contentAsString)

    private fun JsonNode.entry(id: Long): JsonNode? = values().firstOrNull { it["id"].asLong() == id }

    @Test
    fun `RF62_anAssistantTeacherReadsOnlyNameAndPhoneOfStudents`() {
        val id = student("Lucía", phone = "1166667777")

        val entry = entries(tokenFor(Role.ASSISTANT_TEACHER)).entry(id)!!

        assertThat(entry.propertyNames()).containsExactlyInAnyOrder("id", "name", "phone", "active")
        assertThat(entry["name"].asString()).isEqualTo("Lucía")
        assertThat(entry["phone"].asString()).isEqualTo("1166667777")
        assertThat(entry["active"].asBoolean()).isTrue()
    }

    @Test
    fun `RF24_theDirectoryListsActiveStudentsByDefault`() {
        val active = student("Bruno")
        val inactive = student("Carla", active = false)

        val result = entries(tokenFor(Role.LEAD_TEACHER))

        assertThat(result.entry(active)).isNotNull
        assertThat(result.entry(inactive)).isNull()
    }

    @Test
    fun `RF24_theDirectoryFiltersInactiveStudents`() {
        val active = student("Bruno")
        val inactive = student("Carla", active = false)

        val result = entries(tokenFor(Role.ASSISTANT_TEACHER), "?status=INACTIVE")

        assertThat(result.entry(active)).isNull()
        assertThat(result.entry(inactive)).isNotNull
    }

    @Test
    fun `DI76_theDirectoryWithStatusAllIncludesInactiveStudents`() {
        val active = student("Bruno")
        val inactive = student("Carla", active = false)

        val result = entries(tokenFor(Role.ASSISTANT_TEACHER), "?status=ALL")

        assertThat(result.entry(active)).isNotNull
        assertThat(result.entry(inactive)!!["active"].asBoolean()).isFalse()
    }

    @Test
    fun `RF24_theDirectoryIsOrderedByNameIgnoringCase`() {
        val ids = listOf(student("zoe"), student("Ana"), student("bea")).toSet()

        val names = entries(tokenFor(Role.LEAD_TEACHER)).values()
            .filter { it["id"].asLong() in ids }
            .map { it["name"].asString() }

        assertThat(names).containsExactly("Ana", "bea", "zoe")
    }

    @Test
    fun `RF24_theDirectoryDoesNotIncludeOtherRoles`() {
        val lead = userWith(Role.LEAD_TEACHER)
        val assistant = userWith(Role.ASSISTANT_TEACHER)

        val result = entries(lead.token, "?status=ALL")

        assertThat(result.entry(lead.id)).isNull()
        assertThat(result.entry(assistant.id)).isNull()
    }

    @Test
    fun `DI76_theDirectoryRuleOpensOnlyTheExactPath`() {
        directory(tokenFor(Role.ASSISTANT_TEACHER), "/x").andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("role_not_allowed") }
        }
    }

    @Test
    fun `RF24_anInvalidStatusIsAValidationError`() {
        directory(tokenFor(Role.LEAD_TEACHER), "?status=NOPE").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("validation_error") }
        }
    }

    @Test
    fun `CP22_aStudentCannotReadTheDirectory`() {
        directory(tokenFor(Role.STUDENT)).andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("role_not_allowed") }
        }
    }

    @Test
    fun `CP22_anAssistantTeacherStillCannotReadAStudentProfile`() {
        val id = student("Lucía")

        mvc.get("/api/v1/students/$id") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenFor(Role.ASSISTANT_TEACHER)}")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value("role_not_allowed") }
        }
    }

    @Test
    fun `RF62_theLiteralDirectoryPathWinsOverTheStudentIdPath`() {
        // If "/{id}" matched first, "directory" would be a non-numeric id and the lead teacher would get a 404.
        directory(tokenFor(Role.LEAD_TEACHER)).andExpect {
            status { isOk() }
            jsonPath("$") { isArray() }
        }
    }
}
