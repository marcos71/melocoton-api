package ar.melocoton.auth.infrastructure.mail

import ar.melocoton.auth.domain.AccountLinkPurpose
import ar.melocoton.auth.domain.UserAccount
import ar.melocoton.shared.application.port.Mail
import ar.melocoton.shared.domain.Role
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class AccountLinkMailSenderTest {

    private val sent = mutableListOf<Mail>()

    // A trailing slash in the setting must not produce "//set-password".
    private val sender = AccountLinkMailSender({ sent += it }, "https://app.melocoton.test/")

    private val account = UserAccount(
        id = 1, name = "Ana", email = "ana@test.local", role = Role.ASSISTANT_TEACHER, passwordHash = null,
        active = true, failedLoginAttempts = 0, lockedUntil = null, sessionsValidFrom = Instant.EPOCH,
    )

    @Test
    fun `DI-33 the link goes to the PWA with the token in the fragment`() {
        sender.send(account, AccountLinkPurpose.PASSWORD_RESET, "abc_123")

        val mail = sent.single()
        assertThat(mail.to).isEqualTo("ana@test.local")
        assertThat(mail.body).contains("https://app.melocoton.test/set-password#token=abc_123")
    }

    @Test
    fun `CU-01 2c the reset email says it lasts 1 hour`() {
        sender.send(account, AccountLinkPurpose.PASSWORD_RESET, "abc")

        val mail = sent.single()
        assertThat(mail.subject).isEqualTo("Definí tu contraseña de Melocotón")
        assertThat(mail.body).startsWith("Hola, Ana:").contains("vence en 1 hora")
    }

    @Test
    fun `D-11 the invitation email says it lasts 7 days`() {
        sender.send(account, AccountLinkPurpose.INVITATION, "abc")

        val mail = sent.single()
        assertThat(mail.subject).isEqualTo("Te invitaron a Melocotón")
        assertThat(mail.body).startsWith("Hola, Ana:").contains("vence en 7 días")
    }
}
