package ar.melocoton.shared.application.port

/** An email for one person. Plain text, in Spanish (DI-20). */
data class Mail(val to: String, val subject: String, val body: String)

/** Sends emails (DI-13): in development they are written to the log instead. */
fun interface Mailer {
    fun send(mail: Mail)
}
