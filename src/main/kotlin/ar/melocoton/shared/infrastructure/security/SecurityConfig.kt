package ar.melocoton.shared.infrastructure.security

import ar.melocoton.shared.application.port.SessionUserLookup
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.OrRequestMatcher
import org.springframework.security.web.util.matcher.RequestMatcher
import java.time.Clock

/**
 * Who may call what (DI-06, DI-28). Spring Security runs as a chain of filters before any
 * controller: for each request it reads `Authorization: Bearer <token>`, checks the signature
 * and expiry ([JwtConfig]), asks [SessionUserLookup] whether the session still holds, and only
 * then lets the request through.
 *
 * Per-role permissions live in the `authorizeHttpRequests` block below, and nowhere else (DI-34).
 * The rules are evaluated in order and the first one that matches wins, so a specific path rule
 * must come before the general session rule for the whole API. A valid session with the wrong role gets 403
 * `role_not_allowed` from [SecurityErrorResponses]. Request limits (RNF-25, DI-36) are two more
 * filters in this chain: [LoginRateLimitFilter] and [UserRateLimitFilter].
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties::class)
class SecurityConfig {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        sessions: SessionUserLookup,
        errors: SecurityErrorResponses,
        rateLimits: RateLimitProperties,
        clock: Clock,
    ): SecurityFilterChain {
        http {
            // CORS is checked before authentication, so the browser's preflight (which carries
            // no token) gets the rules of CorsConfig instead of a 401.
            cors { }
            // The token travels in a header, not in a cookie, so CSRF does not apply.
            csrf { disable() }
            // No server session: every request brings its token.
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                authorize(PUBLIC_API, permitAll)
                authorize("/api/v1/assistant-teachers/**", hasRole("LEAD_TEACHER"))
                authorize("/api/v1/slots/**", hasRole("LEAD_TEACHER"))
                // /me/** is the student's own routes and must come first. Everything else under students
                // (the bare path included: ** also matches zero segments) is the lead teacher's, so a
                // future subpath such as /{id}/fixed-slot is closed to other roles by default.
                authorize("/api/v1/students/me/**", hasRole("STUDENT"))
                authorize("/api/v1/students/**", hasRole("LEAD_TEACHER"))
                authorize("/api/**", authenticated)
                // Outside the API: Swagger UI, the OpenAPI document and Spring's error page.
                authorize(anyRequest, permitAll)
            }
            oauth2ResourceServer {
                bearerTokenResolver = ignoringPublicApi(DefaultBearerTokenResolver())
                jwt { jwtAuthenticationConverter = SessionAuthenticationConverter(sessions) }
                authenticationEntryPoint = errors
                accessDeniedHandler = errors
            }
            exceptionHandling {
                authenticationEntryPoint = errors
                accessDeniedHandler = errors
            }
            // Request limits (RNF-25, DI-36). Both go after CorsFilter, so the browser can read
            // the 429. Login attempts are counted before the token is looked at; the per-user
            // count needs the user, so it goes right after the bearer token is accepted.
            addFilterBefore<BearerTokenAuthenticationFilter>(LoginRateLimitFilter(rateLimits, clock, errors))
            addFilterAfter<BearerTokenAuthenticationFilter>(UserRateLimitFilter(rateLimits, clock, errors))
        }
        return http.build()
    }

    companion object {
        private val paths = PathPatternRequestMatcher.withDefaults()

        /** Endpoints that need no session. */
        val PUBLIC_API: RequestMatcher = OrRequestMatcher(
            paths.matcher("/api/v1/health"),
            paths.matcher(HttpMethod.POST, "/api/v1/auth/login"),
            paths.matcher(HttpMethod.POST, "/api/v1/auth/password-reset-requests"),
            paths.matcher(HttpMethod.POST, "/api/v1/auth/set-password"),
        )

        /**
         * Spring would check a Bearer token on any request that carries one, even on a public
         * endpoint, so an expired token left in the PWA would block logging in again. On public
         * endpoints the token is not read at all.
         */
        fun ignoringPublicApi(delegate: BearerTokenResolver) = BearerTokenResolver { request: HttpServletRequest ->
            if (PUBLIC_API.matches(request)) null else delegate.resolve(request)
        }
    }
}
