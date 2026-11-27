package ar.melocoton.pieces.infrastructure.persistence

import ar.melocoton.pieces.application.port.PieceQueries
import ar.melocoton.pieces.domain.PieceEventType
import ar.melocoton.pieces.domain.PieceFilter
import ar.melocoton.pieces.domain.PieceStatus
import ar.melocoton.schema.SchemaFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** The piece read side against the test database; each test is rolled back. */
@SpringBootTest
@Transactional
class PieceQueriesJdbcTest(
    @Autowired private val queries: PieceQueries,
    @Autowired private val jdbc: JdbcTemplate,
) {
    private val fixtures = SchemaFixtures(jdbc)
    private val base = Instant.parse("2026-11-26T15:00:00Z")

    private fun event(pieceId: Long, userId: Long, type: String, previous: String?, new: String?, at: Instant) {
        jdbc.update(
            """INSERT INTO piece_status_history (piece_id, previous_status, new_status, event_type, occurred_at, user_id, operation_id)
               VALUES (?, ?, ?, ?, ?, ?, gen_random_uuid())""",
            pieceId, previous, new, type, OffsetDateTime.ofInstant(at, ZoneOffset.UTC), userId,
        )
    }

    private fun voidPiece(pieceId: Long, userId: Long) {
        jdbc.update("UPDATE piece SET voided_at = now(), voided_by = ?, void_reason = 'Rota' WHERE id = ?", userId, pieceId)
    }

    @Test
    fun `RF61_findAllAndFindDetailNeverReturnVoidedPieces`() {
        val student = fixtures.monthlyStudent()
        val piece = fixtures.piece(student, withCreationEvent = true)
        voidPiece(piece, student)

        assertThat(queries.findAll(PieceFilter(studentId = student))).isEmpty()
        assertThat(queries.findDetail(piece)).isNull()
    }

    @Test
    fun `RF31_findAllIncludesPiecesOfInactiveStudents`() {
        val student = fixtures.monthlyStudent()
        val piece = fixtures.piece(student)
        jdbc.update("UPDATE app_user SET active = false WHERE id = ?", student)

        assertThat(queries.findAll(PieceFilter(studentId = student)).map { it.id }).containsExactly(piece)
    }

    @Test
    fun `RF32_filteringByStudentAndStatusReturnsOnlyPiecesMatchingBoth`() {
        val ana = fixtures.monthlyStudent()
        val luis = fixtures.monthlyStudent()
        val anaGlazed = fixtures.piece(ana, status = "GLAZED")
        fixtures.piece(ana, status = "IN_PROGRESS")
        fixtures.piece(luis, status = "GLAZED")

        assertThat(queries.findAll(PieceFilter(studentId = ana, status = PieceStatus.GLAZED)).map { it.id }).containsExactly(anaGlazed)
        assertThat(queries.findAll(PieceFilter(studentId = ana))).hasSize(2)
        assertThat(queries.findAll(PieceFilter(status = PieceStatus.GLAZED)).map { it.id }).contains(anaGlazed).hasSizeGreaterThanOrEqualTo(2)
    }

    @Test
    fun `DI71_updatedAtIsTheLastEventAndListsAreNewestFirst`() {
        val student = fixtures.monthlyStudent()
        val older = fixtures.piece(student, withCreationEvent = false)
        val newer = fixtures.piece(student)
        event(older, student, "CREATED", null, "IN_PROGRESS", base)
        event(older, student, "ADVANCED", "IN_PROGRESS", "BISQUE_FIRED", base.plusSeconds(3600))
        event(newer, student, "CREATED", null, "IN_PROGRESS", base.plusSeconds(60))
        jdbc.update("UPDATE piece SET status = 'BISQUE_FIRED' WHERE id = ?", older)

        val list = queries.findAll(PieceFilter(studentId = student))

        assertThat(list.map { it.id }).containsExactly(older, newer)
        assertThat(list.first().updatedAt).isEqualTo(base.plusSeconds(3600))
        assertThat(list.first().advanceTo).isEqualTo(PieceStatus.GLAZED)
        assertThat(list.first().revertTo).isEqualTo(PieceStatus.IN_PROGRESS)
    }

    @Test
    fun `DI71_theDetailShowsTheHistoryNewestFirstWithWhoChangedIt`() {
        val student = fixtures.monthlyStudent()
        val teacher = fixtures.user("LEAD_TEACHER")
        val piece = fixtures.piece(student)
        event(piece, student, "CREATED", null, "IN_PROGRESS", base)
        event(piece, teacher, "ADVANCED", "IN_PROGRESS", "BISQUE_FIRED", base.plusSeconds(60))
        event(piece, teacher, "REVERTED", "BISQUE_FIRED", "IN_PROGRESS", base.plusSeconds(120))

        val detail = checkNotNull(queries.findDetail(piece))

        assertThat(detail.history.map { it.eventType })
            .containsExactly(PieceEventType.REVERTED, PieceEventType.ADVANCED, PieceEventType.CREATED)
        assertThat(detail.history.first().correction).isTrue()
        assertThat(detail.history.first().changedById).isEqualTo(teacher)
        assertThat(detail.updatedAt).isEqualTo(base.plusSeconds(120))
        assertThat(detail.studentId).isEqualTo(student)
        assertThat(detail.editableByOwner).isTrue()
    }

    @Test
    fun `DI71_aPieceWithoutEventsFallsBackToItsCreationDate`() {
        val piece = fixtures.piece(fixtures.monthlyStudent())

        val detail = checkNotNull(queries.findDetail(piece))

        assertThat(detail.updatedAt).isEqualTo(detail.createdAt)
        assertThat(detail.history).isEmpty()
    }
}
