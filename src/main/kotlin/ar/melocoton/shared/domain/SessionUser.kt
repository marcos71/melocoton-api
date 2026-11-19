package ar.melocoton.shared.domain

import java.time.Instant

/**
 * Who is making the request, taken from a valid session token (DI-28).
 *
 * @param sessionsValidFrom the account's session cut-off the token was issued under; a refresh
 *   keeps it, so a password change in between still closes the renewed session.
 */
data class SessionUser(val userId: Long, val role: Role, val sessionsValidFrom: Instant)
