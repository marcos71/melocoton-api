package ar.melocoton.shared.infrastructure.security

import ar.melocoton.shared.domain.SessionUser
import ar.melocoton.shared.domain.SlidingWindowRateLimiter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpMethod
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock

/**
 * Limits the attempts to log in per client IP (DI-31, DI-36). Every attempt counts, whether it
 * succeeds, fails or has an invalid body, and the check runs before authentication, so a flood of
 * guesses never reaches the password check.
 *
 * The IP is `request.remoteAddr`. Behind a proxy it is the proxy's address unless the server is
 * configured to honor the forwarded headers (`server.forward-headers-strategy`, a deployment
 * concern); this class never reads `X-Forwarded-For` itself, because a client could forge it.
 *
 * A rejected attempt is not counted again (see [SlidingWindowRateLimiter]). State is in memory:
 * per instance, and reset on restart.
 */
class LoginRateLimitFilter(
    properties: RateLimitProperties,
    private val clock: Clock,
    private val errors: SecurityErrorResponses,
) : OncePerRequestFilter() {

    private val limiter = SlidingWindowRateLimiter<String>(properties.loginPerIp, properties.window)

    override fun shouldNotFilter(request: HttpServletRequest) = !LOGIN.matches(request)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (limiter.tryAcquire(request.remoteAddr, clock.instant())) chain.doFilter(request, response)
        else errors.tooManyRequests(response)
    }

    private companion object {
        val LOGIN = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/login")
    }
}

/**
 * Limits the requests of each authenticated user across the whole API (RNF-25, DI-36). It runs
 * after the bearer token has been accepted, so the key is the id of the user in the session, and
 * requests without a valid session (public endpoints, rejected tokens) are not counted here.
 *
 * A rejected request is not counted again (see [SlidingWindowRateLimiter]). State is in memory:
 * per instance, and reset on restart.
 */
class UserRateLimitFilter(
    properties: RateLimitProperties,
    private val clock: Clock,
    private val errors: SecurityErrorResponses,
) : OncePerRequestFilter() {

    private val limiter = SlidingWindowRateLimiter<Long>(properties.perUser, properties.window)

    override fun shouldNotFilter(request: HttpServletRequest) = !API.matches(request)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val user = SecurityContextHolder.getContext().authentication?.principal as? SessionUser
        if (user == null || limiter.tryAcquire(user.userId, clock.instant())) chain.doFilter(request, response)
        else errors.tooManyRequests(response)
    }

    private companion object {
        val API = PathPatternRequestMatcher.withDefaults().matcher("/api/**")
    }
}
