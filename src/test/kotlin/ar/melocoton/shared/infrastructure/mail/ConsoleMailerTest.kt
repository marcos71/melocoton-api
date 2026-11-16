package ar.melocoton.shared.infrastructure.mail

import ar.melocoton.shared.application.port.Mail
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

@ExtendWith(OutputCaptureExtension::class)
class ConsoleMailerTest {

    @Test
    fun `DI-13 in development the email is written to the log`(output: CapturedOutput) {
        ConsoleMailer().send(Mail("ana@test.local", "Asunto", "Cuerpo del correo"))

        assertThat(output.out).contains("To: ana@test.local", "Subject: Asunto", "Cuerpo del correo")
    }
}
