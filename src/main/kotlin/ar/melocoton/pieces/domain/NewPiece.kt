package ar.melocoton.pieces.domain

import java.time.Instant
import java.util.UUID

/** A piece to be created (CU-12, RF-24, RF-25): already validated, without an id. It always starts in progress. */
class NewPiece private constructor(val studentId: Long, val text: PieceText) {

    /** The first event of the piece's history (RN-05), once it has an id. */
    fun createdEvent(pieceId: Long, by: Long, at: Instant, operationId: UUID) = PieceHistoryEvent(
        pieceId = pieceId,
        eventType = PieceEventType.CREATED,
        previousStatus = null,
        newStatus = PieceStatus.IN_PROGRESS,
        occurredAt = at,
        userId = by,
        operationId = operationId,
    )

    companion object {
        /**
         * @param namesInUse the student's non-voided pieces
         * @throws InvalidPiece the name or the notes are not valid
         * @throws DuplicatePieceName the student already has a piece with that name
         */
        fun create(studentId: Long, name: String, notes: String?, namesInUse: List<PieceName>): NewPiece {
            val text = PieceText.of(name, notes)
            requireUniqueName(text.name, namesInUse)
            return NewPiece(studentId, text)
        }
    }
}
