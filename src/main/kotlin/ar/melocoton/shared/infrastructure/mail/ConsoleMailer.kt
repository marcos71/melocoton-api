package ar.melocoton.shared.infrastructure.mail

import ar.melocoton.shared.application.port.Mail
import ar.melocoton.shared.application.port.Mailer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Development mailer (DI-13): writes the email to the log instead of sending it, so no real
 * mail account is needed. The log then holds working links, so it must not run in production.
 */
@Component
class ConsoleMailer : Mailer {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(mail: Mail) {
        log.info("Email (not sent, development)\nTo: {}\nSubject: {}\n\n{}", mail.to, mail.subject, mail.body)
    }
}
