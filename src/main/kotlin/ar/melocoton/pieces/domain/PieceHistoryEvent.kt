package ar.melocoton.pieces.domain

import java.time.Instant
import java.util.UUID

/**
 * One row of the insert-only history of a piece (RN-05, RF-28). [operationId] groups the pieces of one bulk change.
 * A creation has no previous status and a voidance has no new status (DI-74).
 */
data class PieceHistoryEvent(
    val pieceId: Long,
    val eventType: PieceEventType,
    val previousStatus: PieceStatus?,
    val newStatus: PieceStatus?,
    val occurredAt: Instant,
    val userId: Long,
    val operationId: UUID,
)
