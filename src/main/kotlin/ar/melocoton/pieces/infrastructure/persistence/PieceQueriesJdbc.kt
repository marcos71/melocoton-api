package ar.melocoton.pieces.infrastructure.persistence

import ar.melocoton.pieces.application.port.PieceDetail
import ar.melocoton.pieces.application.port.PieceHistoryEntry
import ar.melocoton.pieces.application.port.PieceQueries
import ar.melocoton.pieces.application.port.PieceSummary
import ar.melocoton.pieces.domain.PieceEventType
import ar.melocoton.pieces.domain.PieceFilter
import ar.melocoton.pieces.domain.PieceStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Adapter of [PieceQueries]. It joins `app_user` read-only for the names of the owner and of whoever changed
 * the piece: that is coupling to the auth schema, accepted for composed reads (DI-60).
 * `updatedAt` is the last history event, or the creation date for a piece without events (DI-71).
 */
@Repository
class PieceQueriesJdbc(private val jdbc: JdbcTemplate) : PieceQueries {

    override fun findAll(filter: PieceFilter): List<PieceSummary> {
        val conditions = mutableListOf("p.voided_at IS NULL")
        val args = mutableListOf<Any>()
        filter.studentId?.let {
            conditions += "p.student_id = ?"
            args += it
        }
        filter.status?.let {
            conditions += "p.status = ?"
            args += it.name
        }
        return jdbc.query(
            """SELECT p.id, p.student_id, u.name AS student_name, p.name, p.status, $UPDATED_AT AS updated_at
               FROM piece p JOIN app_user u ON u.id = p.student_id
               WHERE ${conditions.joinToString(" AND ")}
               ORDER BY updated_at DESC, p.id DESC""",
            { rs, _ ->
                PieceSummary(
                    id = rs.getLong("id"),
                    studentId = rs.getLong("student_id"),
                    studentName = rs.getString("student_name"),
                    name = rs.getString("name"),
                    status = PieceStatus.valueOf(rs.getString("status")),
                    updatedAt = rs.instant("updated_at"),
                )
            },
            *args.toTypedArray(),
        )
    }

    override fun findDetail(id: Long): PieceDetail? {
        val history = findHistory(id)
        return jdbc.query(
            """SELECT p.id, p.student_id, u.name AS student_name, p.name, p.notes, p.status, p.created_at,
                      $UPDATED_AT AS updated_at
               FROM piece p JOIN app_user u ON u.id = p.student_id
               WHERE p.id = ? AND p.voided_at IS NULL""",
            { rs, _ ->
                PieceDetail(
                    id = rs.getLong("id"),
                    studentId = rs.getLong("student_id"),
                    studentName = rs.getString("student_name"),
                    name = rs.getString("name"),
                    notes = rs.getString("notes"),
                    status = PieceStatus.valueOf(rs.getString("status")),
                    createdAt = rs.instant("created_at"),
                    updatedAt = rs.instant("updated_at"),
                    history = history,
                )
            },
            id,
        ).singleOrNull()
    }

    private fun findHistory(pieceId: Long): List<PieceHistoryEntry> = jdbc.query(
        """SELECT h.event_type, h.previous_status, h.new_status, h.occurred_at, h.operation_id, h.user_id, u.name AS user_name
           FROM piece_status_history h JOIN app_user u ON u.id = h.user_id
           WHERE h.piece_id = ?
           ORDER BY h.occurred_at DESC, h.id DESC""",
        { rs, _ ->
            PieceHistoryEntry(
                eventType = PieceEventType.valueOf(rs.getString("event_type")),
                previousStatus = rs.getString("previous_status")?.let(PieceStatus::valueOf),
                newStatus = rs.getString("new_status")?.let(PieceStatus::valueOf),
                occurredAt = rs.instant("occurred_at"),
                operationId = rs.getObject("operation_id", UUID::class.java),
                changedById = rs.getLong("user_id"),
                changedByName = rs.getString("user_name"),
            )
        },
        pieceId,
    )

    private companion object {
        const val UPDATED_AT = "COALESCE((SELECT max(h.occurred_at) FROM piece_status_history h WHERE h.piece_id = p.id), p.created_at)"
    }
}

private fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()
