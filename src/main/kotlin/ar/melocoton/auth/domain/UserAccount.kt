package ar.melocoton.auth.domain

import ar.melocoton.shared.domain.Role
import java.time.Duration
import java.time.Instant

/**
 * A person's account: credentials, role, lockout and session cut-off.
 * Login rules live here (DI-30, DI-31); hashing the password is infrastructure,
 * so the caller only says whether it matched.
 */
class UserAccount(
    val id: Long,
    val name: String,
    val email: String,
    val role: Role,
    /** Null until the invited person defines a password (D-11). */
    val passwordHash: String?,
    val active: Boolean,
    failedLoginAttempts: Int,
    lockedUntil: Instant?,
    /** Session cut-off: only tokens issued with this exact value are valid; a password change moves it (DI-28). */
    val sessionsValidFrom: Instant,
) {
    var failedLoginAttempts: Int = failedLoginAttempts
        private set

    var lockedUntil: Instant? = lockedUntil
        private set

    fun isLockedAt(now: Instant): Boolean = lockedUntil?.isAfter(now) == true

    /**
     * Decides a login attempt. Every rejection is the same [InvalidCredentials], whatever
     * the reason (DI-30). A wrong password counts toward the lockout (DI-31); an attempt
     * while locked is rejected without counting, and a success resets the count.
     */
    fun logIn(passwordMatches: Boolean, now: Instant) {
        if (isLockedAt(now)) throw InvalidCredentials()
        if (!passwordMatches) {
            recordFailedAttempt(now)
            throw InvalidCredentials()
        }
        if (!active) throw InvalidCredentials()
        failedLoginAttempts = 0
        lockedUntil = null
    }

    /**
     * True while the account is active and the token was issued under the current cut-off (DI-28).
     * Compared for equality, not by time: exact even for a token issued a moment before a change.
     */
    fun acceptsSession(tokenSessionsValidFrom: Instant): Boolean =
        active && tokenSessionsValidFrom == sessionsValidFrom

    private fun recordFailedAttempt(now: Instant) {
        // A lockout that already ended starts a fresh count.
        if (lockedUntil != null) {
            failedLoginAttempts = 0
            lockedUntil = null
        }
        failedLoginAttempts += 1
        if (failedLoginAttempts >= MAX_FAILED_ATTEMPTS) lockedUntil = now.plus(LOCKOUT)
    }

    companion object {
        /** Wrong passwords in a row that lock the account (DI-31). */
        const val MAX_FAILED_ATTEMPTS = 5

        /** How long the lockout lasts (DI-31). */
        val LOCKOUT: Duration = Duration.ofMinutes(15)
    }
}
