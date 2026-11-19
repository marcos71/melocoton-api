package ar.melocoton.shared.domain

import java.time.Duration
import java.time.Instant

/**
 * Counts hits per key in a rolling window and says whether one more is allowed (RNF-25, DI-31,
 * DI-36). At most [limit] hits are accepted per key in any span of [window]: a hit made at `t`
 * counts until `t + window` (exclusive), then it is forgotten.
 *
 * A rejected hit is not recorded, so insisting does not extend the block: once the oldest
 * accepted hits leave the window, the key can make requests again.
 *
 * Pure rules: it never reads the clock (the caller passes `now`) and it is thread safe. The
 * state lives in memory, so it is per instance and resets on restart (DI-36: single instance).
 * Keys with no hit in the last [window] are dropped, so memory does not grow with the number of
 * keys ever seen.
 */
class SlidingWindowRateLimiter<K : Any>(private val limit: Int, private val window: Duration) {

    private val hits = HashMap<K, ArrayDeque<Instant>>()
    private var lastSweep: Instant? = null

    init {
        require(limit >= 1) { "limit must be at least 1" }
        require(!window.isZero && !window.isNegative) { "window must be positive" }
    }

    /** Records a hit for [key] at [now] and returns true, or returns false (recording nothing) if the limit is reached. */
    @Synchronized
    fun tryAcquire(key: K, now: Instant): Boolean {
        sweepIfDue(now)
        val queue = hits.getOrPut(key) { ArrayDeque() }
        dropExpired(queue, now)
        if (queue.size >= limit) return false
        queue.addLast(now)
        return true
    }

    /** How many keys are being tracked; for tests and monitoring. */
    @Synchronized
    fun trackedKeys(): Int = hits.size

    private fun dropExpired(queue: ArrayDeque<Instant>, now: Instant) {
        val cutoff = now.minus(window)
        while (queue.isNotEmpty() && queue.first() <= cutoff) queue.removeFirst()
    }

    /** At most once per window, forgets the keys whose hits have all expired. */
    private fun sweepIfDue(now: Instant) {
        val last = lastSweep
        if (last != null && now < last.plus(window)) return
        lastSweep = now
        val iterator = hits.values.iterator()
        while (iterator.hasNext()) {
            val queue = iterator.next()
            dropExpired(queue, now)
            if (queue.isEmpty()) iterator.remove()
        }
    }
}
