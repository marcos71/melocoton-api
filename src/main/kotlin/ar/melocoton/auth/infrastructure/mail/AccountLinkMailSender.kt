package ar.melocoton.auth.infrastructure.mail

import ar.melocoton.auth.application.port.AccountLinkSender
import ar.melocoton.auth.domain.AccountLinkPurpose
import ar.melocoton.auth.domain.UserAccount
import ar.melocoton.shared.application.port.Mail
import ar.melocoton.shared.application.port.Mailer
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Writes the invitation or reset email with the link to the PWA. The token goes in the URL
 * fragment (after `#`): browsers never send it to a server, so it stays out of logs and
 * Referer headers (DI-33).
 */
@Component
class AccountLinkMailSender(
    private val mailer: Mailer,
    @Value("\${melocoton.web.base-url}") private val webBaseUrl: String,
) : AccountLinkSender {

    override fun send(account: UserAccount, purpose: AccountLinkPurpose, token: String) {
        val link = "${webBaseUrl.trimEnd('/')}/set-password#token=$token"
        val mail = when (purpose) {
            AccountLinkPurpose.INVITATION -> Mail(
                to = account.email,
                subject = "Te invitaron a Melocotón",
                body = """
                    |Hola, ${account.name}:
                    |
                    |Para entrar a Melocotón, definí tu contraseña en este enlace:
                    |$link
                    |
                    |El enlace vence en 7 días y sirve una sola vez.
                """.trimMargin(),
            )
            AccountLinkPurpose.PASSWORD_RESET -> Mail(
                to = account.email,
                subject = "Definí tu contraseña de Melocotón",
                body = """
                    |Hola, ${account.name}:
                    |
                    |Para definir una contraseña nueva, entrá a este enlace:
                    |$link
                    |
                    |El enlace vence en 1 hora y sirve una sola vez. Si no lo pediste, ignorá este correo.
                """.trimMargin(),
            )
        }
        mailer.send(mail)
    }
}
