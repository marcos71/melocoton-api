package ar.melocoton.auth.infrastructure.web

import ar.melocoton.auth.infrastructure.security.JwtSessionTokens
import ar.melocoton.shared.application.port.Mail
import ar.melocoton.shared.application.port.Mailer
import ar.melocoton.shared.domain.Role
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * The invitation email of CU-02 D1 (RF-53, D-11) goes out only after the transaction commits.
 * Not wrapped in a test transaction, because a rolled-back test transaction never commits and
 * so would never send. Rows are not deleted (the API role cannot); the test database is
 * recreated on every run (DI-19). Every test uses its own emails.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AssistantTeacherInvitationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val json: ObjectMapper,
    @Autowired private val tokens: JwtSessionTokens,
    @Autowired private val mailer: RecordingMailer,
) {
    /** Keeps the emails instead of writing them to the log, so the test can read the link. */
    class RecordingMailer : Mailer {
        val sent = mutableListOf<Mail>()
        var failure: RuntimeException? = null
        override fun send(mail: Mail) {
            failure?.let { throw it }
            sent += mail
        }
    }

    @TestConfiguration
    class RecordingMailerConfig {
        @Bean
        @Primary
        fun recordingMailer() = RecordingMailer()
    }

    @BeforeEach
    fun resetMailer() {
        mailer.sent.clear()
        mailer.failure = null
    }

    private fun insertUser(
        role: Role,
        active: Boolean = true,
        email: String = "${UUID.randomUUID()}@test.local",
    ): Pair<Long, String> {
        val id = jdbc.queryForObject(
            """INSERT INTO app_user (name, email, role, phone, active) VALUES ('Ana', ?, ?, '1100000000', ?) RETURNING id""",
            Long::class.javaObjectType, email, role.name, active,
        )!!
        return id to email
    }

    private fun leadToken(): String {
        val (id, _) = insertUser(Role.LEAD_TEACHER)
        val validFrom = jdbc.queryForObject("SELECT sessions_valid_from FROM app_user WHERE id = ?", Instant::class.java, id)!!
        return tokens.issue(id, Role.LEAD_TEACHER, validFrom, Instant.now()).value
    }

    private fun create(token: String, email: String): ResultActionsDsl = mvc.post("/api/v1/assistant-teachers") {
        header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        contentType = MediaType.APPLICATION_JSON
        content = json.writeValueAsString(mapOf("name" to "Lucía", "email" to email, "phone" to "1155550000"))
    }

    @Test
    fun `RF53_theInvitationIsSentToTheNewAssistantTeacherAfterTheCommit`() {
        val email = "${UUID.randomUUID()}@test.local"

        val response = create(leadToken(), email).andExpect { status { isCreated() } }.andReturn().response.contentAsString

        val id = json.readTree(response)["id"].asLong()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE id = ?", Int::class.java, id)).isEqualTo(1)
        val mail = mailer.sent.single()
        assertThat(mail.to).isEqualTo(email)
        assertThat(mail.body).contains("/set-password#token=")
    }

    @Test
    fun `RF53_theInvitationLinkLetsTheNewAssistantTeacherDefineAPasswordAndLogIn`() {
        val email = "${UUID.randomUUID()}@test.local"
        create(leadToken(), email).andExpect { status { isCreated() } }
        val token = checkNotNull(Regex("#token=([A-Za-z0-9_-]+)").find(mailer.sent.single().body)).groupValues[1]

        mvc.post("/api/v1/auth/set-password") {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("token" to token, "newPassword" to "una-contraseña-larga"))
        }.andExpect { status { isNoContent() } }

        mvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("email" to email, "password" to "una-contraseña-larga"))
        }.andExpect {
            status { isOk() }
            jsonPath("$.user.role") { value("ASSISTANT_TEACHER") }
        }
    }

    /**
     * Not in the rolled-back test class: a failed INSERT aborts a PostgreSQL transaction, so only one
     * conflict per test transaction is possible there. Here each request commits or fails on its own.
     */
    @Test
    fun `DI34_anEmailOfAnotherAccountIsRejectedIgnoringCaseAndNothingIsSent`() {
        val lead = leadToken()
        val (_, studentEmail) = insertUser(Role.STUDENT)
        val (_, inactiveEmail) = insertUser(Role.STUDENT, active = false)
        val (_, leadEmail) = insertUser(Role.LEAD_TEACHER)

        listOf(studentEmail, inactiveEmail, leadEmail).forEach { email ->
            create(lead, email.uppercase()).andExpect {
                status { isConflict() }
                jsonPath("$.error.code") { value("email_already_used") }
                jsonPath("$.error.message") { value("Ya hay una cuenta con ese email.") }
            }
        }

        assertThat(mailer.sent).isEmpty()
    }

    @Test
    fun `RF53_ifSendingFailsAfterTheCommitTheAccountIsStillCreated`() {
        mailer.failure = IllegalStateException("smtp down")
        val email = "${UUID.randomUUID()}@test.local"

        create(leadToken(), email).andExpect { status { isCreated() } }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email = ?", Int::class.java, email)).isEqualTo(1)
        assertThat(mailer.sent).isEmpty()
    }
}
