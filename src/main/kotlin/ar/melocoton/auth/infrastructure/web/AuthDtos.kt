package ar.melocoton.auth.infrastructure.web

import ar.melocoton.auth.application.LoggedIn
import ar.melocoton.auth.application.port.SessionToken
import ar.melocoton.auth.domain.UserAccount
import ar.melocoton.shared.domain.Role
import jakarta.validation.constraints.NotBlank
import java.time.Instant

/** Body of POST /api/v1/auth/login. */
data class LoginRequest(
    @field:NotBlank val email: String,
    @field:NotBlank val password: String,
)

/** Body of POST /api/v1/auth/password-reset-requests. */
data class PasswordResetRequest(@field:NotBlank val email: String)

/** Body of POST /api/v1/auth/set-password. The length rule is checked by the domain (DI-29, DI-33). */
data class SetPasswordRequest(
    /** The token from the link (`#token=...`). */
    @field:NotBlank val token: String,
    val newPassword: String,
)

/** Who is logged in. */
data class SessionUserResponse(val id: Long, val name: String, val role: Role)

/** A new session token, after logging in or refreshing. */
data class TokenResponse(
    /** Send it as `Authorization: Bearer <accessToken>`. */
    val accessToken: String,
    /** Expires then unless it is refreshed before (DI-28). */
    val expiresAt: Instant,
)

/** Answer of POST /api/v1/auth/login. */
data class LoginResponse(val accessToken: String, val expiresAt: Instant, val user: SessionUserResponse)

/** Answer of GET /api/v1/auth/session. */
data class SessionResponse(val user: SessionUserResponse)

fun UserAccount.toSessionUserResponse() = SessionUserResponse(id = id, name = name, role = role)

fun SessionToken.toResponse() = TokenResponse(accessToken = value, expiresAt = expiresAt)

fun LoggedIn.toResponse() = LoginResponse(
    accessToken = token.value,
    expiresAt = token.expiresAt,
    user = account.toSessionUserResponse(),
)
