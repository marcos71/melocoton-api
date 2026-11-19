package ar.melocoton.auth.infrastructure.web

import ar.melocoton.shared.application.port.Mail
import ar.melocoton.shared.application.port.Mailer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * The password reset email of CU-01 2c (RF-01, DI-33) goes out only after the transaction commits,
 * and a send failure does not change the answer. Not wrapped in a test transaction, because a
 * rolled-back test transaction never commits and so would never send. Every test uses its own email.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PasswordResetEmailTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val json: ObjectMapper,
    @Autowired private val mailer: RecordingMailer,
) {
    /** Counts the committed links as seen from another connection at the moment of sending. */
    class RecordingMailer(private val jdbc: JdbcTemplate) : Mailer {
        val sent = mutableListOf<Mail>()
        val linksVisibleWhenSent = mutableListOf<Int>()
        var failure: RuntimeException? = null
        override fun send(mail: Mail) {
            failure?.let { throw it }
            linksVisibleWhenSent += jdbc.queryForObject(
                "SELECT count(*) FROM account_link l JOIN app_user u ON u.id = l.user_id WHERE u.email = ?",
                Int::class.java, mail.to,
            )!!
            sent += mail
        }
    }

    @TestConfiguration
    class RecordingMailerConfig {
        @Bean
        @Primary
        fun recordingMailer(jdbc: JdbcTemplate) = RecordingMailer(jdbc)
    }

    @BeforeEach
    fun resetMailer() {
        mailer.sent.clear()
        mailer.linksVisibleWhenSent.clear()
        mailer.failure = null
    }

    private fun activeUser(): String {
        val email = "${UUID.randomUUID()}@test.local"
        jdbc.update(
            "INSERT INTO app_user (name, email, role, phone, active) VALUES ('Ana', ?, 'STUDENT', '1100000000', true)",
            email,
        )
        return email
    }

    private fun requestReset(email: String) = mvc.post("/api/v1/auth/password-reset-requests") {
        contentType = MediaType.APPLICATION_JSON
        content = json.writeValueAsString(mapOf("email" to email))
    }

    private fun linksOf(email: String): Int = jdbc.queryForObject(
        "SELECT count(*) FROM account_link l JOIN app_user u ON u.id = l.user_id WHERE u.email = ?",
        Int::class.java, email,
    )!!

    @Test
    fun `RF01_theResetEmailIsSentAfterTheCommit`() {
        val email = activeUser()

        requestReset(email).andExpect { status { isAccepted() } }

        assertThat(mailer.sent.single().to).isEqualTo(email)
        // The mailer queried over its own connection: the link was already committed when it ran.
        assertThat(mailer.linksVisibleWhenSent).containsExactly(1)
    }

    @Test
    fun `RF01_ifSendingTheResetEmailFailsTheAnswerIsStill202AndTheLinkIsSaved`() {
        mailer.failure = IllegalStateException("smtp down")
        val email = activeUser()

        requestReset(email).andExpect { status { isAccepted() } }

        assertThat(linksOf(email)).isEqualTo(1)
        assertThat(mailer.sent).isEmpty()
    }
}
