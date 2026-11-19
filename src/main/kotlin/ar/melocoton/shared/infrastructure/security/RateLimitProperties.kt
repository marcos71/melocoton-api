package ar.melocoton.shared.infrastructure.security

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Request limits (RNF-25, DI-31, DI-36), from `melocoton.rate-limit.*` in application.yml.
 *
 * @param loginPerIp attempts to log in allowed per client IP in each [window].
 * @param perUser requests allowed per authenticated user in each [window].
 * @param window length of the rolling window.
 */
@ConfigurationProperties("melocoton.rate-limit")
data class RateLimitProperties(
    val loginPerIp: Int = 30,
    val perUser: Int = 60,
    val window: Duration = Duration.ofMinutes(1),
)
