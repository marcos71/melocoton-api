package ar.melocoton.auth.infrastructure.events

import ar.melocoton.auth.application.CreateAssistantTeacher
import ar.melocoton.auth.application.IssueInvitation
import ar.melocoton.auth.application.port.AccountLinkRepository
import ar.melocoton.auth.domain.AccountInvited
import ar.melocoton.auth.domain.AccountLink
import ar.melocoton.shared.application.port.DomainEvents
import ar.melocoton.shared.domain.Role
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.IllegalTransactionStateException
import java.time.Instant
import java.util.UUID

/**
 * DI-54 against PostgreSQL. Not wrapped in a test transaction: the publisher opens its own, as in
 * production. Rows are not deleted (the API role cannot); the test database is recreated on every run (DI-19).
 */
@SpringBootTest
class InvitationListenerTest(
    @Autowired private val issueInvitation: IssueInvitation,
    @Autowired private val createAssistantTeacher: CreateAssistantTeacher,
    @Autowired private val domainEvents: DomainEvents,
    @Autowired private val jdbc: JdbcTemplate,
) {
    /** Makes saving a link fail on demand. */
    object LinkFailure {
        @Volatile
        var enabled = false
    }

    /** Wraps the real link repository: it delegates everything, and fails on save while [LinkFailure] is on. */
    @TestConfiguration
    class FailingLinkSaveConfig {
        @Bean
        fun failingLinkSave(): BeanPostProcessor = object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any? {
                if (bean !is AccountLinkRepository) return bean
                return object : AccountLinkRepository by bean {
                    override fun save(link: AccountLink) {
                        if (LinkFailure.enabled) throw IllegalStateException("link insert failed")
                        bean.save(link)
                    }
                }
            }
        }
    }

    @AfterEach
    fun reset() {
        LinkFailure.enabled = false
    }

    private fun insertUser(): Long = jdbc.queryForObject(
        "INSERT INTO app_user (name, email, role, phone) VALUES ('Ana', ?, ?, '1100000000') RETURNING id",
        Long::class.javaObjectType, "${UUID.randomUUID()}@test.local", Role.STUDENT.name,
    )!!

    private fun linkCount(userId: Long) =
        jdbc.queryForObject("SELECT count(*) FROM account_link WHERE user_id = ?", Int::class.java, userId)

    @Test
    fun `DI54_issuingAnInvitationOutsideATransactionFails`() {
        val id = insertUser()

        assertThatThrownBy { issueInvitation.issue(id, Instant.now()) }
            .isInstanceOf(IllegalTransactionStateException::class.java)

        assertThat(linkCount(id)).isZero()
    }

    @Test
    fun `DI54_publishingAccountInvitedOutsideATransactionFails`() {
        val id = insertUser()

        assertThatThrownBy { domainEvents.publish(AccountInvited(id, Instant.now())) }
            .isInstanceOf(IllegalTransactionStateException::class.java)

        assertThat(linkCount(id)).isZero()
    }

    @Test
    fun `DI54_ifTheListenerFailsTheAccountCreationIsRolledBackToo`() {
        val email = "${UUID.randomUUID()}@test.local"
        LinkFailure.enabled = true

        assertThatThrownBy { createAssistantTeacher.create("Lucía", email, "1155550000") }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email = ?", Int::class.java, email)).isZero()
    }
}
