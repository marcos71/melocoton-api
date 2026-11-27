package ar.melocoton.pieces.infrastructure.persistence

import ar.melocoton.pieces.application.port.PieceRepository
import ar.melocoton.pieces.domain.NewPiece
import ar.melocoton.pieces.domain.PieceEventType
import ar.melocoton.pieces.domain.PieceHistoryEvent
import ar.melocoton.pieces.domain.PieceStatus
import ar.melocoton.pieces.domain.PieceText
import ar.melocoton.schema.SchemaFixtures
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** The piece write adapter against the test database; each test is rolled back. */
@SpringBootTest
@Transactional
class PieceRepositoryJpaTest(
    @Autowired private val pieces: PieceRepository,
    @Autowired private val history: PieceStatusHistoryJpaRepository,
    @Autowired private val entityManager: EntityManager,
    @Autowired private val jdbc: JdbcTemplate,
) {
    private val fixtures = SchemaFixtures(jdbc)
    private val at = Instant.parse("2026-11-26T15:00:00Z")

    private fun statusOf(id: Long) = jdbc.queryForObject("SELECT status FROM piece WHERE id = ?", String::class.java, id)

    @Test
    fun `RF25_createStoresAPieceInProgressThatPlainSqlSees`() {
        val student = fixtures.monthlyStudent()

        val id = pieces.create(NewPiece.create(student, "Bowl", " con asa ", emptyList()), at)

        val piece = checkNotNull(pieces.findById(id))
        assertThat(piece.name).isEqualTo("Bowl")
        assertThat(piece.notes).isEqualTo("con asa")
        assertThat(piece.status).isEqualTo(PieceStatus.IN_PROGRESS)
        assertThat(piece.studentId).isEqualTo(student)
        assertThat(jdbc.queryForObject("SELECT created_at FROM piece WHERE id = ?", OffsetDateTime::class.java, id)!!.toInstant())
            .isEqualTo(at)
    }

    @Test
    fun `D34_saveStatusAdvancesWhenTheExpectedStatusMatches`() {
        val piece = fixtures.piece(fixtures.monthlyStudent())

        assertThat(pieces.saveStatus(piece, PieceStatus.IN_PROGRESS, PieceStatus.BISQUE_FIRED)).isTrue()
        assertThat(statusOf(piece)).isEqualTo("BISQUE_FIRED")
    }

    @Test
    fun `D34_saveStatusWithAStaleExpectedStatusTouchesNoRow`() {
        val piece = fixtures.piece(fixtures.monthlyStudent(), status = "GLAZED")

        assertThat(pieces.saveStatus(piece, PieceStatus.BISQUE_FIRED, PieceStatus.GLAZED)).isFalse()
        assertThat(statusOf(piece)).isEqualTo("GLAZED")
    }

    @Test
    fun `D34_saveStatusOnAVoidedPieceTouchesNoRow`() {
        val student = fixtures.monthlyStudent()
        val piece = fixtures.piece(student)
        pieces.saveVoid(piece, PieceStatus.IN_PROGRESS, "Rota", student, at)

        assertThat(pieces.saveStatus(piece, PieceStatus.IN_PROGRESS, PieceStatus.BISQUE_FIRED)).isFalse()
        assertThat(statusOf(piece)).isEqualTo("IN_PROGRESS")
    }

    @Test
    fun `DI74_saveVoidMarksThePieceOnceAndFindByIdStillReturnsIt`() {
        val student = fixtures.monthlyStudent()
        val piece = fixtures.piece(student)

        assertThat(pieces.saveVoid(piece, PieceStatus.IN_PROGRESS, "Rota", student, at)).isTrue()
        assertThat(pieces.saveVoid(piece, PieceStatus.IN_PROGRESS, "Otra vez", student, at)).isFalse()
        assertThat(checkNotNull(pieces.findById(piece)).voided).isTrue()
    }

    @Test
    fun `DI74_saveVoidWithAStaleExpectedStatusTouchesNoRow`() {
        val student = fixtures.monthlyStudent()
        val piece = fixtures.piece(student, status = "GLAZED")

        assertThat(pieces.saveVoid(piece, PieceStatus.IN_PROGRESS, "Rota", student, at)).isFalse()
        assertThat(checkNotNull(pieces.findById(piece)).voided).isFalse()
    }

    @Test
    fun `RF27_saveTextIsGuardedByTheRequiredStatusAndKeepsNullNotes`() {
        val student = fixtures.monthlyStudent()
        val piece = fixtures.piece(student, status = "GLAZED", name = "Taza")
        val loaded = checkNotNull(pieces.findById(piece))
        loaded.edit(PieceText.of("Jarra", null), emptyList())

        assertThat(pieces.saveText(loaded, requiredStatus = PieceStatus.IN_PROGRESS)).isFalse()
        assertThat(pieces.saveText(loaded, requiredStatus = null)).isTrue()
        assertThat(checkNotNull(pieces.findById(piece)).name).isEqualTo("Jarra")
    }

    @Test
    fun `RN05_historyEventsAreStoredAndCannotBeUpdatedThroughTheEntity`() {
        val student = fixtures.monthlyStudent()
        val piece = fixtures.piece(student)
        val event = PieceHistoryEvent(piece, PieceEventType.ADVANCED, PieceStatus.IN_PROGRESS, PieceStatus.BISQUE_FIRED, at, student, UUID.randomUUID())
        pieces.appendHistory(listOf(event))

        val stored = history.findAll().single { it.pieceId == piece }
        PieceStatusHistoryEntity::class.java.getDeclaredField("newStatus").apply { isAccessible = true }.set(stored, PieceStatus.FINISHED)
        entityManager.flush()

        assertThat(jdbc.queryForObject("SELECT new_status FROM piece_status_history WHERE piece_id = ?", String::class.java, piece))
            .isEqualTo("BISQUE_FIRED")
    }

    @Test
    fun `DI26_namesInUseLeavesOutVoidedPieces`() {
        val student = fixtures.monthlyStudent()
        fixtures.piece(student, name = "Activa")
        val voided = fixtures.piece(student, name = "Anulada")
        pieces.saveVoid(voided, PieceStatus.IN_PROGRESS, "Rota", student, at)
        fixtures.piece(fixtures.monthlyStudent(), name = "De otro alumno")

        assertThat(pieces.namesInUse(student).map { it.name }).containsExactly("Activa")
    }

    @Test
    fun `D34_findByIdsReturnsOnlyTheExistingPieces`() {
        val piece = fixtures.piece(fixtures.monthlyStudent())

        assertThat(pieces.findByIds(listOf(piece, -1L)).map { it.id }).containsExactly(piece)
        assertThat(pieces.findByIds(emptyList())).isEmpty()
    }
}
