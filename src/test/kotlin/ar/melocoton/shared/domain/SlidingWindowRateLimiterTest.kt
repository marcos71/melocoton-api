package ar.melocoton.shared.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SlidingWindowRateLimiterTest {

    private val t0 = Instant.parse("2027-03-01T12:00:00Z")
    private val limiter = SlidingWindowRateLimiter<String>(limit = 3, window = Duration.ofMinutes(1))

    @Test
    fun `RNF25 hits under the limit are accepted`() {
        assertThat(limiter.tryAcquire("a", t0)).isTrue()
        assertThat(limiter.tryAcquire("a", t0.plusSeconds(1))).isTrue()
    }

    @Test
    fun `RNF25 the hit that reaches the limit is accepted and the next one is rejected`() {
        repeat(3) { assertThat(limiter.tryAcquire("a", t0.plusSeconds(it.toLong()))).isTrue() }

        assertThat(limiter.tryAcquire("a", t0.plusSeconds(3))).isFalse()
    }

    @Test
    fun `RNF25 the window slides, so the oldest hit frees a place when it expires`() {
        limiter.tryAcquire("a", t0)
        limiter.tryAcquire("a", t0.plusSeconds(30))
        limiter.tryAcquire("a", t0.plusSeconds(40))

        assertThat(limiter.tryAcquire("a", t0.plusSeconds(59))).isFalse()
        // The first hit counts until t0 + 1 minute, exclusive.
        assertThat(limiter.tryAcquire("a", t0.plusSeconds(60))).isTrue()
        assertThat(limiter.tryAcquire("a", t0.plusSeconds(61))).isFalse()
    }

    @Test
    fun `RNF25 DI31 a full window later every hit is accepted again`() {
        repeat(3) { limiter.tryAcquire("a", t0) }

        assertThat(limiter.tryAcquire("a", t0.plus(Duration.ofMinutes(1)))).isTrue()
    }

    @Test
    fun `RNF25 keys are independent`() {
        repeat(3) { limiter.tryAcquire("a", t0) }

        assertThat(limiter.tryAcquire("a", t0)).isFalse()
        assertThat(limiter.tryAcquire("b", t0)).isTrue()
    }

    @Test
    fun `DI36 rejected hits are not counted, so insisting does not extend the block`() {
        repeat(3) { limiter.tryAcquire("a", t0) }
        repeat(50) { assertThat(limiter.tryAcquire("a", t0.plusSeconds(30))).isFalse() }

        assertThat(limiter.tryAcquire("a", t0.plusSeconds(60))).isTrue()
    }

    @Test
    fun `DI36 keys with no hit in the last window are forgotten`() {
        limiter.tryAcquire("a", t0)
        limiter.tryAcquire("b", t0.plusSeconds(10))
        assertThat(limiter.trackedKeys()).isEqualTo(2)

        limiter.tryAcquire("c", t0.plus(Duration.ofMinutes(5)))

        assertThat(limiter.trackedKeys()).isEqualTo(1)
    }

    @Test
    fun `DI36 it is thread safe, never accepting more than the limit`() {
        val big = SlidingWindowRateLimiter<String>(limit = 100, window = Duration.ofMinutes(1))
        val accepted = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        repeat(1000) { pool.submit { if (big.tryAcquire("a", t0)) accepted.incrementAndGet() } }
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)

        assertThat(accepted.get()).isEqualTo(100)
    }

    @Test
    fun `DI36 a limit below one or an empty window is rejected`() {
        assertThatThrownBy { SlidingWindowRateLimiter<String>(0, Duration.ofMinutes(1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SlidingWindowRateLimiter<String>(1, Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
