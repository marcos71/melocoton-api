package ar.melocoton.shared.infrastructure

import ar.melocoton.shared.domain.WorkshopTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class ClockConfigTest {

    private val clock: Clock = ClockConfig().clock()

    @Test
    fun `the workshop zone is Buenos Aires`() {
        assertThat(WorkshopTime.ZONE).isEqualTo(ZoneId.of("America/Argentina/Buenos_Aires"))
    }

    @Test
    fun `the clock uses the workshop zone`() {
        assertThat(clock.zone).isEqualTo(WorkshopTime.ZONE)
    }

    @Test
    fun `the clock follows the system time`() {
        assertThat(Duration.between(clock.instant(), Instant.now()).abs())
            .isLessThan(Duration.ofSeconds(1))
    }
}
