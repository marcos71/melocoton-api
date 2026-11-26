package ar.melocoton.slots.application

import ar.melocoton.slots.domain.SlotOverlap
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalTime
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Two overlapping slots created at the same time on a day with no slots: only one is saved (DI-25).
 * Not wrapped in a test transaction: each create commits on its own, as in production. The API role
 * cannot delete, so the slots are deactivated afterwards; Sunday is used by no other test.
 */
@SpringBootTest
class SlotConcurrencyTest(
    @Autowired private val createSlot: CreateSlot,
    @Autowired private val jdbc: JdbcTemplate,
) {

    @Test
    fun `DI25_concurrentOverlappingCreates_onlyOneIsSaved`() {
        repeat(5) {
            val barrier = CyclicBarrier(2)
            val pool = Executors.newFixedThreadPool(2)
            val outcomes = listOf("18:00" to "20:00", "19:00" to "21:00").map { (start, end) ->
                pool.submit<Result<Long>> {
                    barrier.await()
                    runCatching { createSlot.create(7, LocalTime.parse(start), LocalTime.parse(end)).id }
                }
            }.map { it.get(30, TimeUnit.SECONDS) }
            pool.shutdown()

            try {
                assertThat(outcomes.count { it.isSuccess }).describedAs("created: %s", outcomes).isEqualTo(1)
                assertThat(outcomes.single { it.isFailure }.exceptionOrNull()).isInstanceOf(SlotOverlap::class.java)
                assertThat(
                    jdbc.queryForObject("SELECT count(*) FROM slot WHERE day_of_week = 7 AND active", Int::class.java),
                ).isEqualTo(1)
            } finally {
                jdbc.update("UPDATE slot SET active = false WHERE day_of_week = 7")
            }
        }
    }
}
