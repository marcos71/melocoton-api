package ar.melocoton.auth.application.port

interface AccountLinkTokens {
    /** A new random token, impossible to guess, safe to put in a URL. */
    fun generate(): String

    /** The value stored and looked up instead of the token itself (DI-29). */
    fun hash(token: String): String
}
