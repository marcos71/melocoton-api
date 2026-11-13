package ar.melocoton.shared.infrastructure

import ar.melocoton.shared.domain.WorkshopTime
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The only place that reads the system time. Use cases receive this Clock and pass
 * `now`/`today` to the domain, so tests can replace it with a fixed clock (DI-14).
 */
@Configuration
class ClockConfig {

    @Bean
    fun clock(): Clock = Clock.system(WorkshopTime.ZONE)
}
