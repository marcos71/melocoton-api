package ar.melocoton.auth.application.port

fun interface PasswordHasher {
    /**
     * Whether [rawPassword] matches [hash]. With a null hash (unknown email, or a password not
     * defined yet) it still does the same work and returns false, so the response time does not
     * reveal whether the email exists (DI-30).
     */
    fun matches(rawPassword: String, hash: String?): Boolean
}
