package ar.melocoton.bookings

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Replaces the application clock with a fixed one, so the rolling window and the lessons that
 * "have not started" do not depend on the day the tests run. 12:00 in Buenos Aires on Thursday 2026-11-19;
 * the window then ends on 2027-01-31 (DI-42, horizon of 60 days).
 */
@TestConfiguration
class FixedClockTestConfig {

    @Bean
    @Primary
    fun fixedClock(): Clock = FIXED_CLOCK

    companion object {
        val NOW: Instant = Instant.parse("2026-11-19T15:00:00Z")
        val FIXED_CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
        val TODAY: LocalDate = LocalDate.of(2026, 11, 19)
        val WINDOW_END: LocalDate = LocalDate.of(2027, 1, 31)
    }
}
