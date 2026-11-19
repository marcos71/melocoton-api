package ar.melocoton.auth.application.port

import ar.melocoton.auth.domain.UserAccount

interface UserAccountRepository {
    /**
     * Loads the account to log in and locks its row until the transaction ends, so parallel
     * attempts on the same account are counted one after the other (DI-31). Email is compared
     * ignoring case, as the unique index does.
     */
    fun lockByEmail(email: String): UserAccount?

    fun findById(id: Long): UserAccount?

    /** Saves the failed attempts and lockout after a login attempt. */
    fun saveLoginState(account: UserAccount)
}
