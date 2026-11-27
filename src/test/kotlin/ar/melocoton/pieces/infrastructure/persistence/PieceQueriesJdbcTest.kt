package ar.melocoton.pieces.infrastructure.persistence

import ar.melocoton.pieces.application.port.PieceCursor
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

    private fun all(filter: PieceFilter) = queries.findPage(filter, null, 100).items

    private fun pieceAt(studentId: Long, at: Instant, status: String = "IN_PROGRESS"): Long =
        fixtures.piece(studentId, status = status).also { event(it, studentId, "CREATED", null, status, at) }

    private fun walk(filter: PieceFilter, limit: Int, from: PieceCursor? = null): List<List<Long>> {
        val pages = mutableListOf<List<Long>>()
        var cursor: PieceCursor? = from
        do {
            val page = queries.findPage(filter, cursor, limit)
            pages += page.items.map { it.id }
            cursor = page.next
        } while (cursor != null)
        return pages
    }

    private fun voidPiece(pieceId: Long, userId: Long) {
        jdbc.update("UPDATE piece SET voided_at = now(), voided_by = ?, void_reason = 'Rota' WHERE id = ?", userId, pieceId)
    }

    @Test
    fun `RF61_findPageAndFindDetailNeverReturnVoidedPieces`() {
        val student = fixtures.monthlyStudent()
        val piece = fixtures.piece(student, withCreationEvent = true)
        voidPiece(piece, student)

        assertThat(all(PieceFilter(studentId = student))).isEmpty()
        assertThat(queries.findDetail(piece)).isNull()
    }

    @Test
    fun `RF31_findPageIncludesPiecesOfInactiveStudents`() {
        val student = fixtures.monthlyStudent()
        val piece = fixtures.piece(student)
        jdbc.update("UPDATE app_user SET active = false WHERE id = ?", student)

        assertThat(all(PieceFilter(studentId = student)).map { it.id }).containsExactly(piece)
    }

    @Test
    fun `RF32_filteringByStudentAndStatusReturnsOnlyPiecesMatchingBoth`() {
        val ana = fixtures.monthlyStudent()
        val luis = fixtures.monthlyStudent()
        val anaGlazed = fixtures.piece(ana, status = "GLAZED")
        fixtures.piece(ana, status = "IN_PROGRESS")
        fixtures.piece(luis, status = "GLAZED")

        assertThat(all(PieceFilter(studentId = ana, status = PieceStatus.GLAZED)).map { it.id }).containsExactly(anaGlazed)
        assertThat(all(PieceFilter(studentId = ana))).hasSize(2)
        assertThat(all(PieceFilter(status = PieceStatus.GLAZED)).map { it.id }).contains(anaGlazed).hasSizeGreaterThanOrEqualTo(2)
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

        val list = all(PieceFilter(studentId = student))

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

    @Test
    fun `DI87_walkingThePagesReturnsEveryPieceOnceInOrder`() {
        val student = fixtures.monthlyStudent()
        (0L until 5L).forEach { pieceAt(student, base.plusSeconds(it * 60)) }
        val filter = PieceFilter(studentId = student)

        val pages = walk(filter, 2)

        assertThat(pages).hasSize(3)
        assertThat(pages.flatten()).containsExactlyElementsOf(all(filter).map { it.id }).doesNotHaveDuplicates()
    }

    @Test
    fun `DI87_piecesWithTheSameUpdatedAtAreSplitByIdDescending`() {
        val student = fixtures.monthlyStudent()
        val ids = (1..3).map { fixtures.piece(student) }
        jdbc.update("UPDATE piece SET created_at = ? WHERE student_id = ?", OffsetDateTime.ofInstant(base, ZoneOffset.UTC), student)

        val pages = walk(PieceFilter(studentId = student), 2)

        assertThat(pages).containsExactly(listOf(ids[2], ids[1]), listOf(ids[0]))
    }

    @Test
    fun `DI87_anExactlyFullLastPageHasNoNextCursor`() {
        val student = fixtures.monthlyStudent()
        repeat(2) { fixtures.piece(student) }

        val page = queries.findPage(PieceFilter(studentId = student), null, 2)

        assertThat(page.items).hasSize(2)
        assertThat(page.next).isNull()
    }

    @Test
    fun `DI87_aPieceChangedWhilePagingMovesToTheTopAndIsNotRepeated`() {
        val student = fixtures.monthlyStudent()
        val (e, d, c, b, a) = (0L until 5L).map { pieceAt(student, base.plusSeconds(it * 60)) }
        val filter = PieceFilter(studentId = student)
        val first = queries.findPage(filter, null, 2)
        assertThat(first.items.map { it.id }).containsExactly(a, b)

        event(d, student, "ADVANCED", "IN_PROGRESS", "BISQUE_FIRED", base.plusSeconds(3600))
        jdbc.update("UPDATE piece SET status = 'BISQUE_FIRED' WHERE id = ?", d)

        assertThat(walk(filter, 2, from = first.next).flatten()).containsExactly(c, e)
    }

    @Test
    fun `DI87_aPieceLeavingTheStatusFilterDoesNotHideTheNextOnes`() {
        val student = fixtures.monthlyStudent()
        val (d, c, b, a) = (0L until 4L).map { pieceAt(student, base.plusSeconds(it * 60), status = "GLAZED") }
        val filter = PieceFilter(studentId = student, status = PieceStatus.GLAZED)
        val first = queries.findPage(filter, null, 2)
        assertThat(first.items.map { it.id }).containsExactly(a, b)

        event(a, student, "ADVANCED", "GLAZED", "FINISHED", base.plusSeconds(3600))
        jdbc.update("UPDATE piece SET status = 'FINISHED' WHERE id = ?", a)

        assertThat(queries.findPage(filter, first.next, 2).items.map { it.id }).containsExactly(c, d)
    }
}
