package ar.melocoton.pieces.infrastructure.persistence

import ar.melocoton.pieces.application.port.PieceRepository
import ar.melocoton.pieces.domain.NewPiece
import ar.melocoton.pieces.domain.Piece
import ar.melocoton.pieces.domain.PieceHistoryEvent
import ar.melocoton.pieces.domain.PieceName
import ar.melocoton.pieces.domain.PieceStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Adapter of [PieceRepository]. [create] and [appendHistory] go through JPA entities and flush, so the plain
 * JDBC that does everything else sees them. Reads never go through JPA: after a JDBC UPDATE in the same
 * transaction the persistence context would be stale. Instants travel as UTC `OffsetDateTime`, so the JVM zone never matters.
 */
@Repository
class PieceRepositoryJpa(
    private val pieces: PieceJpaRepository,
    private val history: PieceStatusHistoryJpaRepository,
    private val jdbc: JdbcTemplate,
) : PieceRepository {

    override fun create(piece: NewPiece, createdAt: Instant): Long =
        checkNotNull(pieces.saveAndFlush(piece.toEntity(createdAt)).id) { "A stored piece always has an id" }

    override fun findById(id: Long): Piece? = findByIds(listOf(id)).singleOrNull()

    override fun findByIds(ids: Collection<Long>): List<Piece> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        return jdbc.query(
            "SELECT id, student_id, name, notes, status, voided_at IS NOT NULL AS voided FROM piece WHERE id IN ($placeholders)",
            { rs, _ -> rs.toPiece() },
            *ids.toTypedArray(),
        )
    }

    override fun namesInUse(studentId: Long): List<PieceName> = jdbc.query(
        "SELECT id, name, status FROM piece WHERE student_id = ? AND voided_at IS NULL",
        { rs, _ -> PieceName(rs.getLong("id"), rs.getString("name"), PieceStatus.valueOf(rs.getString("status"))) },
        studentId,
    )

    override fun saveStatus(id: Long, expected: PieceStatus, new: PieceStatus): Boolean = jdbc.update(
        "UPDATE piece SET status = ? WHERE id = ? AND status = ? AND voided_at IS NULL",
        new.name, id, expected.name,
    ) == 1

    override fun saveText(piece: Piece, requiredStatus: PieceStatus?): Boolean {
        val statusGuard = if (requiredStatus == null) "" else " AND status = ?"
        val args = listOf<Any?>(piece.name, piece.notes, piece.id) + listOfNotNull(requiredStatus?.name)
        return jdbc.update(
            "UPDATE piece SET name = ?, notes = ? WHERE id = ? AND voided_at IS NULL$statusGuard",
            *args.toTypedArray(),
        ) == 1
    }

    override fun saveVoid(id: Long, expected: PieceStatus, reason: String, by: Long, at: Instant): Boolean = jdbc.update(
        """UPDATE piece SET voided_at = ?, voided_by = ?, void_reason = ?
           WHERE id = ? AND status = ? AND voided_at IS NULL""",
        at.toUtc(), by, reason, id, expected.name,
    ) == 1

    override fun advanceAll(ids: Collection<Long>, expected: PieceStatus, new: PieceStatus): Set<Long> = jdbc.query(
        { connection ->
            connection.prepareStatement(
                "UPDATE piece SET status = ? WHERE id = ANY(?) AND status = ? AND voided_at IS NULL RETURNING id",
            ).apply {
                setString(1, new.name)
                setArray(2, connection.createArrayOf("bigint", ids.toTypedArray()))
                setString(3, expected.name)
            }
        },
        { rs, _ -> rs.getLong("id") },
    ).toSet()

    override fun appendHistory(events: List<PieceHistoryEvent>) {
        history.saveAllAndFlush(events.map { it.toEntity() })
    }
}

private fun ResultSet.toPiece() = Piece(
    id = getLong("id"),
    studentId = getLong("student_id"),
    name = getString("name"),
    notes = getString("notes"),
    status = PieceStatus.valueOf(getString("status")),
    voided = getBoolean("voided"),
)

private fun Instant.toUtc(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
