package ar.melocoton.pieces.application

import ar.melocoton.auth.infrastructure.security.JwtSessionTokens
import ar.melocoton.bookings.FixedClockTestConfig
import ar.melocoton.pieces.domain.BulkItem
import ar.melocoton.pieces.domain.DuplicatePieceName
import ar.melocoton.pieces.domain.PieceConflictDetails
import ar.melocoton.pieces.domain.PieceStatus
import ar.melocoton.pieces.domain.PieceStatusConflict
import ar.melocoton.pieces.infrastructure.web.PieceApi
import ar.melocoton.schema.SchemaFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pieces under concurrency, as in production: each call commits on its own, so these tests are not wrapped in
 * a test transaction (CP-19, CP-20, D-34, DI-73). The API role cannot delete, so each test creates its own
 * students and pieces; fixture names are unique.
 */
// Same annotations as StudentBookingEndpointTest, so all of them share one Spring context (and one connection pool).
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig::class)
class PieceConcurrencyTest(
    @Autowired private val bulkChangePieceStatus: BulkChangePieceStatus,
    @Autowired private val changePieceStatus: ChangePieceStatus,
    @Autowired private val createPiece: CreatePiece,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired mvc: MockMvc,
    @Autowired json: ObjectMapper,
    @Autowired tokens: JwtSessionTokens,
) {
    private val fixtures = SchemaFixtures(jdbc)
    private val api = PieceApi(mvc, jdbc, json, tokens)
    private val teacher = fixtures.user("LEAD_TEACHER")
    private val otherTeacher = fixtures.user("ASSISTANT_TEACHER")

    /** Runs the [tasks] at the same moment, each on its own thread and transaction. */
    private fun <T> concurrently(tasks: List<() -> T>): List<Result<T>> {
        val barrier = CyclicBarrier(tasks.size)
        val pool = Executors.newFixedThreadPool(tasks.size)
        try {
            return tasks.map { task ->
                pool.submit<Result<T>> {
                    barrier.await()
                    runCatching { task() }
                }
            }.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }
    }

    private fun pieces(count: Int, status: String, students: Int = 1): List<Long> {
        val owners = List(students) { fixtures.monthlyStudent() }
        return List(count) { fixtures.piece(owners[it % students], status = status, withCreationEvent = true) }
    }

    private fun items(ids: List<Long>, expected: PieceStatus) = ids.map { BulkItem(it, expected) }

    private fun statusOf(id: Long) = jdbc.queryForObject("SELECT status FROM piece WHERE id = ?", String::class.java, id)

    private fun advancedEvents(ids: List<Long>): Int = jdbc.queryForObject(
        "SELECT count(*) FROM piece_status_history WHERE event_type = 'ADVANCED' AND piece_id IN (${ids.joinToString(",")})",
        Int::class.java,
    )!!

    private fun operationsOf(ids: List<Long>): List<String> = jdbc.queryForList(
        "SELECT DISTINCT operation_id::text FROM piece_status_history WHERE event_type = 'ADVANCED' AND piece_id IN (${ids.joinToString(",")})",
        String::class.java,
    ).filterNotNull()

    @Test
    fun `CP19_aPieceAdvancedByAnotherTeacherBeforeConfirmingRejectsTheWholeBulkChange`() {
        val ids = pieces(5, "BISQUE_FIRED")
        changePieceStatus.change(ids[0], PieceStatus.BISQUE_FIRED, PieceStatus.GLAZED, otherTeacher)

        val failure = runCatching { bulkChangePieceStatus.advance(items(ids, PieceStatus.BISQUE_FIRED), teacher) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(PieceStatusConflict::class.java)
        assertThat((failure as PieceStatusConflict).details as PieceConflictDetails)
            .extracting { it.pieces.map { p -> p.id } }.isEqualTo(listOf(ids[0]))
        assertThat(ids.map { statusOf(it) }).containsExactly("GLAZED", "BISQUE_FIRED", "BISQUE_FIRED", "BISQUE_FIRED", "BISQUE_FIRED")
        // Only the individual advance wrote an event: the bulk change wrote none for the other four.
        assertThat(advancedEvents(ids)).isEqualTo(1)
    }

    @Test
    fun `CP19_aBulkChangeAndAnIndividualChangeAtTheSameTimeNeverBothApply`() {
        repeat(5) {
            val ids = pieces(5, "BISQUE_FIRED")

            val (bulk, individual) = concurrently(
                listOf(
                    { bulkChangePieceStatus.advance(items(ids, PieceStatus.BISQUE_FIRED), teacher) },
                    { changePieceStatus.change(ids[0], PieceStatus.BISQUE_FIRED, PieceStatus.GLAZED, otherTeacher) },
                ),
            )

            assertThat(listOf(bulk, individual).count { it.isSuccess }).describedAs("%s / %s", bulk, individual).isEqualTo(1)
            if (bulk.isSuccess) {
                assertThat(ids.map { statusOf(it) }).containsOnly("GLAZED")
                assertThat(operationsOf(ids)).hasSize(1)
                assertThat(advancedEvents(ids)).isEqualTo(5)
            } else {
                assertThat(bulk.exceptionOrNull()).isInstanceOf(PieceStatusConflict::class.java)
                assertThat(ids.map { statusOf(it) }).containsExactly("GLAZED", "BISQUE_FIRED", "BISQUE_FIRED", "BISQUE_FIRED", "BISQUE_FIRED")
                assertThat(advancedEvents(ids)).isEqualTo(1)
            }
        }
    }

    @Test
    fun `CP19_twoBulkChangesOverTheSameKilnLoadApplyOnlyOne`() {
        repeat(5) {
            val ids = pieces(5, "BISQUE_FIRED")

            val outcomes = concurrently(
                listOf(
                    { bulkChangePieceStatus.advance(items(ids, PieceStatus.BISQUE_FIRED), teacher) },
                    { bulkChangePieceStatus.advance(items(ids, PieceStatus.BISQUE_FIRED), otherTeacher) },
                ),
            )

            assertThat(outcomes.count { it.isSuccess }).describedAs("%s", outcomes).isEqualTo(1)
            assertThat(outcomes.single { it.isFailure }.exceptionOrNull()).isInstanceOf(PieceStatusConflict::class.java)
            assertThat(ids.map { statusOf(it) }).containsOnly("GLAZED")
            assertThat(advancedEvents(ids)).isEqualTo(5)
        }
    }

    @Test
    fun `D34_twoTeachersAdvancingTheSamePieceAtOnceApplyOne`() {
        repeat(5) {
            val piece = pieces(1, "IN_PROGRESS").single()

            val outcomes = concurrently(
                listOf(
                    { changePieceStatus.change(piece, PieceStatus.IN_PROGRESS, PieceStatus.BISQUE_FIRED, teacher) },
                    { changePieceStatus.change(piece, PieceStatus.IN_PROGRESS, PieceStatus.BISQUE_FIRED, otherTeacher) },
                ),
            )

            assertThat(outcomes.count { it.isSuccess }).describedAs("%s", outcomes).isEqualTo(1)
            assertThat(outcomes.single { it.isFailure }.exceptionOrNull()).isInstanceOf(PieceStatusConflict::class.java)
            assertThat(statusOf(piece)).isEqualTo("BISQUE_FIRED")
            assertThat(advancedEvents(listOf(piece))).isEqualTo(1)
        }
    }

    @Test
    fun `DI73_twoPiecesWithTheSameNameCreatedAtOnceSaveOne`() {
        repeat(5) {
            val student = fixtures.monthlyStudent()

            val outcomes = concurrently(
                List(2) { { createPiece.create(student, "Bowl", null, actorId = teacher) } },
            )

            assertThat(outcomes.count { it.isSuccess }).describedAs("%s", outcomes).isEqualTo(1)
            assertThat(outcomes.single { it.isFailure }.exceptionOrNull()).isInstanceOf(DuplicatePieceName::class.java)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM piece WHERE student_id = ?", Int::class.java, student)).isEqualTo(1)
        }
    }

    @Test
    fun `DI91_revertingAFinishedPieceAndCreatingItsNameAtOnceKeepOneActive`() {
        repeat(5) {
            val student = fixtures.monthlyStudent()
            val finished = fixtures.piece(student, status = "FINISHED", name = "Bowl", withCreationEvent = true)

            val outcomes = concurrently(
                listOf(
                    { changePieceStatus.change(finished, PieceStatus.FINISHED, PieceStatus.GLAZED, teacher) },
                    { createPiece.create(student, "Bowl", null, actorId = teacher) },
                ),
            )

            assertThat(outcomes.count { it.isSuccess }).describedAs("%s", outcomes).isEqualTo(1)
            assertThat(outcomes.single { it.isFailure }.exceptionOrNull()).isInstanceOf(DuplicatePieceName::class.java)
            val active = jdbc.queryForObject(
                """SELECT count(*) FROM piece
                   WHERE student_id = ? AND voided_at IS NULL AND status <> 'FINISHED' AND lower(trim(name)) = 'bowl'""",
                Int::class.java,
                student,
            )
            assertThat(active).isEqualTo(1)
            val revertWon = outcomes.first().isSuccess
            val events = jdbc.queryForObject("SELECT count(*) FROM piece_status_history WHERE piece_id = ?", Int::class.java, finished)
            assertThat(events).isEqualTo(if (revertWon) 2 else 1)
        }
    }

    @Test
    fun `CP20_fiftyGlazedPiecesFinishInUnderThreeSeconds`() {
        val ids = pieces(50, "GLAZED", students = 10)
        val lead = api.lead()
        val body = mapOf("items" to items(ids, PieceStatus.GLAZED).map { mapOf("pieceId" to it.pieceId, "expectedStatus" to "GLAZED") })

        val started = System.nanoTime()
        val response = api.post(lead, "/pieces/bulk-change-status", body)
        val elapsedMillis = (System.nanoTime() - started) / NANOS_PER_MILLI
        println("CP20 bulk change of 50 pieces took $elapsedMillis ms")

        response.andExpect {
            status { isOk() }
            jsonPath("$.count") { value(50) }
            jsonPath("$.studentCount") { value(10) }
        }
        assertThat(elapsedMillis).describedAs("elapsed ms").isLessThan(MAX_BULK_MILLIS)
        assertThat(ids.map { statusOf(it) }).containsOnly("FINISHED")
        assertThat(advancedEvents(ids)).isEqualTo(50)
        assertThat(operationsOf(ids)).hasSize(1)
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val MAX_BULK_MILLIS = 3_000L
    }
}
