package ar.melocoton.pieces.application.port

import ar.melocoton.pieces.domain.PieceEventType
import ar.melocoton.pieces.domain.PieceStatus
import java.time.Instant
import java.util.UUID

/** A row of a piece list. [updatedAt] is the last history event (DI-71). The actions come from the domain (DI-70). */
data class PieceSummary(
    val id: Long,
    val studentId: Long,
    val studentName: String,
    val name: String,
    val status: PieceStatus,
    val updatedAt: Instant,
) {
    val advanceTo: PieceStatus? get() = status.next()
    val revertTo: PieceStatus? get() = status.previous()
}

/** Position in a piece list: the updatedAt and id of the last item seen (DI-87). */
data class PieceCursor(val updatedAt: Instant, val id: Long)

/** One page of a piece list; [next] is null on the last page (DI-87). */
data class PiecePage(val items: List<PieceSummary>, val next: PieceCursor?)

/** A piece with its history, newest event first (RF-28). */
data class PieceDetail(
    val id: Long,
    val studentId: Long,
    val studentName: String,
    val name: String,
    val notes: String?,
    val status: PieceStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val history: List<PieceHistoryEntry>,
) {
    val advanceTo: PieceStatus? get() = status.next()
    val revertTo: PieceStatus? get() = status.previous()
    val editableByOwner: Boolean get() = status.editableByOwner
}

/** One event of a piece's history, with who caused it. */
data class PieceHistoryEntry(
    val eventType: PieceEventType,
    val previousStatus: PieceStatus?,
    val newStatus: PieceStatus?,
    val occurredAt: Instant,
    val operationId: UUID,
    val changedById: Long,
    val changedByName: String,
) {
    val correction: Boolean get() = eventType.isCorrection
}
