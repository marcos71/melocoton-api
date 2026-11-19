package ar.melocoton.auth.infrastructure.web

import ar.melocoton.auth.application.port.AccountLinkTokens
import ar.melocoton.shared.application.port.AfterCommit
import ar.melocoton.shared.application.port.Mail
import ar.melocoton.shared.application.port.Mailer
import ar.melocoton.shared.domain.Role
import jakarta.persistence.EntityManager
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
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Asking for a link and defining the password with it, end to end against the test database
 * (CU-01 2c, 2e, 2f; D-11; DI-29, DI-31, DI-33). Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AccountLinkEndpointTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val json: ObjectMapper,
    @Autowired private val mailer: RecordingMailer,
    @Autowired private val linkTokens: AccountLinkTokens,
    @Autowired private val entityManager: EntityManager,
) {
    /** Keeps the emails instead of writing them to the log, so the test can read the link. */
    class RecordingMailer : Mailer {
        val sent = mutableListOf<Mail>()
        override fun send(mail: Mail) {
            sent += mail
        }
    }

    // A static nested @TestConfiguration is picked up by @SpringBootTest for this class only.
    // @Primary makes it win over ConsoleMailer wherever a Mailer is injected.
    @TestConfiguration
    class RecordingMailerConfig {
        @Bean
        @Primary
        fun recordingMailer() = RecordingMailer()

        // The test transaction is rolled back, so a real after-commit action would never run.
        // The commit timing is covered in PasswordResetEmailTest.
        @Bean
        @Primary
        fun immediateAfterCommit() = AfterCommit { it() }
    }

    @BeforeEach
    fun clearMails() = mailer.sent.clear()

    private fun user(active: Boolean = true): Pair<Long, String> {
        val email = "${UUID.randomUUID()}@test.local"
        val id = jdbc.queryForObject(
            """INSERT INTO app_user (name, email, password_hash, role, phone, active)
               VALUES ('Ana', ?, ?, ?, '1100000000', ?) RETURNING id""",
            Long::class.java, email, OLD_PASSWORD_HASH, Role.STUDENT.name, active,
        )!!
        return id to email
    }

    /** Changes the user with SQL, as another request would, and clears JPA's cache to see it. */
    private fun changeUser(sql: String, id: Long) {
        jdbc.update(sql, id)
        entityManager.clear()
    }

    private fun requestReset(email: String) = mvc.post("/api/v1/auth/password-reset-requests") {
        contentType = MediaType.APPLICATION_JSON
        content = json.writeValueAsString(mapOf("email" to email))
    }

    private fun setPassword(token: String, newPassword: String = NEW_PASSWORD) = mvc.post("/api/v1/auth/set-password") {
        contentType = MediaType.APPLICATION_JSON
        content = json.writeValueAsString(mapOf("token" to token, "newPassword" to newPassword))
    }

    private fun login(email: String, password: String) = mvc.post("/api/v1/auth/login") {
        contentType = MediaType.APPLICATION_JSON
        content = json.writeValueAsString(mapOf("email" to email, "password" to password))
    }

    private fun tokenOf(email: String, password: String = OLD_PASSWORD): String =
        json.readTree(login(email, password).andReturn().response.contentAsString)["accessToken"].asString()

    /** Asks for a reset link and returns the token from the email. */
    private fun linkTokenFor(email: String): String {
        requestReset(email).andExpect { status { isAccepted() } }
        val body = mailer.sent.last().body
        return checkNotNull(Regex("#token=([A-Za-z0-9_-]+)").find(body)) { "No link in: $body" }.groupValues[1]
    }

    private fun linksOf(id: Long): Int =
        jdbc.queryForObject("SELECT count(*) FROM account_link WHERE user_id = ?", Int::class.java, id)!!

    private fun ResultActionsDsl.isLinkExpired() = andExpect {
        status { isBadRequest() }
        jsonPath("$.error.code") { value("link_expired") }
        jsonPath("$.error.message") { value("El enlace venció o ya no sirve. Pedí uno nuevo con «Olvidé mi contraseña».") }
    }

    @Test
    fun `CU-01 2c asking for a reset sends a link to the account's email`() {
        val (id, email) = user()

        requestReset(email.uppercase()).andExpect { status { isAccepted() } }

        val mail = mailer.sent.single()
        assertThat(mail.to).isEqualTo(email)
        assertThat(mail.body).contains("http://localhost:5173/set-password#token=")
        assertThat(linksOf(id)).isEqualTo(1)
    }

    @Test
    fun `DI-29 only the hash of the token is stored`() {
        val (id, email) = user()
        val token = linkTokenFor(email)

        val stored = jdbc.queryForObject("SELECT token_hash FROM account_link WHERE user_id = ?", String::class.java, id)
        assertThat(stored).isEqualTo(linkTokens.hash(token)).isNotEqualTo(token)
    }

    @Test
    fun `DI-33 an unknown email gets the same answer and no email`() {
        requestReset("nadie@test.local").andExpect { status { isAccepted() } }

        assertThat(mailer.sent).isEmpty()
    }

    @Test
    fun `DI-33 an inactive account gets the same answer and no email`() {
        val (id, email) = user(active = false)

        requestReset(email).andExpect { status { isAccepted() } }

        assertThat(mailer.sent).isEmpty()
        assertThat(linksOf(id)).isZero()
    }

    @Test
    fun `DI-31 a fourth request in the same hour sends nothing`() {
        val (id, email) = user()

        repeat(4) { requestReset(email).andExpect { status { isAccepted() } } }

        assertThat(mailer.sent).hasSize(3)
        assertThat(linksOf(id)).isEqualTo(3)
    }

    @Test
    fun `an empty email is a validation error`() {
        requestReset("").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("validation_error") }
        }
    }

    @Test
    fun `CU-01 2c the link defines a new password that works for logging in`() {
        val (_, email) = user()

        setPassword(linkTokenFor(email)).andExpect { status { isNoContent() } }

        login(email, NEW_PASSWORD).andExpect { status { isOk() } }
        login(email, OLD_PASSWORD).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `D-11 an invited account without a password defines its first one`() {
        val (id, email) = user()
        changeUser("UPDATE app_user SET password_hash = NULL WHERE id = ?", id)

        setPassword(linkTokenFor(email)).andExpect { status { isNoContent() } }

        login(email, NEW_PASSWORD).andExpect { status { isOk() } }
    }

    @Test
    fun `DI-28 defining the password closes the sessions already open`() {
        val (_, email) = user()
        val session = tokenOf(email)

        setPassword(linkTokenFor(email)).andExpect { status { isNoContent() } }

        mvc.get("/api/v1/auth/session") { header(HttpHeaders.AUTHORIZATION, "Bearer $session") }
            .andExpect { status { isUnauthorized() } }
        // A session opened afterwards works.
        mvc.get("/api/v1/auth/session") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenOf(email, NEW_PASSWORD)}") }
            .andExpect { status { isOk() } }
    }

    @Test
    fun `DI-33 defining the password lifts the lockout`() {
        val (id, email) = user()
        changeUser("UPDATE app_user SET failed_login_attempts = 5, locked_until = now() + interval '10 minutes' WHERE id = ?", id)

        setPassword(linkTokenFor(email)).andExpect { status { isNoContent() } }

        login(email, NEW_PASSWORD).andExpect { status { isOk() } }
    }

    @Test
    fun `DI-29 a link works only once`() {
        val (_, email) = user()
        val token = linkTokenFor(email)
        setPassword(token).andExpect { status { isNoContent() } }

        setPassword(token, "otra-contraseña-nueva").isLinkExpired()
    }

    @Test
    fun `DI-33 using a link voids the other open links of the account`() {
        val (_, email) = user()
        val first = linkTokenFor(email)
        val second = linkTokenFor(email)

        setPassword(second).andExpect { status { isNoContent() } }

        setPassword(first, "otra-contraseña-nueva").isLinkExpired()
    }

    @Test
    fun `CU-01 2f an expired link is rejected`() {
        val (id, _) = user()
        jdbc.update(
            """INSERT INTO account_link (user_id, purpose, token_hash, created_at, expires_at)
               VALUES (?, 'PASSWORD_RESET', ?, now() - interval '2 hours', now() - interval '1 hour')""",
            id, linkTokens.hash("expired-token"),
        )

        setPassword("expired-token").isLinkExpired()
    }

    @Test
    fun `an unknown token is rejected like an expired link`() {
        setPassword("not-a-token").isLinkExpired()
    }

    @Test
    fun `DI-33 a deactivated account cannot use its link`() {
        val (id, email) = user()
        val token = linkTokenFor(email)
        changeUser("UPDATE app_user SET active = false WHERE id = ?", id)

        setPassword(token).isLinkExpired()
    }

    @Test
    fun `DI-29 a short password is rejected and the link still works`() {
        val (_, email) = user()
        val token = linkTokenFor(email)

        setPassword(token, "corta").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("validation_error") }
            jsonPath("$.error.message") { value("La contraseña tiene que tener entre 10 y 128 caracteres.") }
        }

        setPassword(token).andExpect { status { isNoContent() } }
    }

    @Test
    fun `both endpoints are public and ignore a stale token`() {
        val (_, email) = user()

        requestReset(email).andExpect { status { isAccepted() } }
        mvc.post("/api/v1/auth/set-password") {
            header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token")
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("token" to "x", "newPassword" to NEW_PASSWORD))
        }.isLinkExpired()
    }

    companion object {
        private const val OLD_PASSWORD = "la-contraseña-vieja"
        private const val NEW_PASSWORD = "la-contraseña-nueva"

        // Argon2 is slow on purpose: hash once for the class, not once per test.
        private val OLD_PASSWORD_HASH = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(OLD_PASSWORD)!!
    }
}
