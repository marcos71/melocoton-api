package ar.melocoton.auth.infrastructure.web

import ar.melocoton.auth.application.GetSession
import ar.melocoton.auth.application.LogIn
import ar.melocoton.auth.application.RefreshSession
import ar.melocoton.shared.domain.SessionUser
import ar.melocoton.shared.infrastructure.web.ErrorResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth")
class AuthController(
    private val logIn: LogIn,
    private val refreshSession: RefreshSession,
    private val getSession: GetSession,
) {

    @PostMapping("/login")
    @Operation(summary = "Log in with email and password (CU-01)")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Logged in"),
        ApiResponse(
            responseCode = "401",
            description = "invalid_credentials, whatever the reason (DI-30)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun login(@Valid @RequestBody request: LoginRequest): LoginResponse =
        logIn.logIn(request.email, request.password).toResponse()

    @PostMapping("/refresh")
    @Operation(summary = "Renew the session token, so it expires 30 days after the last use (DI-28)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "New token"),
        ApiResponse(
            responseCode = "401",
            description = "not_authenticated: the session expired or is no longer valid",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    // @AuthenticationPrincipal: Spring passes the user the security filter put in the request.
    fun refresh(@AuthenticationPrincipal user: SessionUser): TokenResponse =
        refreshSession.refresh(user).toResponse()

    @GetMapping("/session")
    @Operation(summary = "Who is logged in, to build the navigation for the role (RF-02)")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The session's user"),
        ApiResponse(
            responseCode = "401",
            description = "not_authenticated",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun session(@AuthenticationPrincipal user: SessionUser): SessionResponse =
        SessionResponse(getSession.get(user).toSessionUserResponse())
}
