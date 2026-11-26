package ar.melocoton.pieces.domain

import java.time.Instant
import java.util.UUID

/**
 * A piece of a student and its life cycle (RN-04, RN-20). Voiding is a mark, not a status (DI-74):
 * a voided piece accepts no more changes (RF-61).
 */
class Piece(
    val id: Long,
    val studentId: Long,
    name: String,
    notes: String?,
    status: PieceStatus,
    voided: Boolean,
) {
    var name: String = name
        private set
    var notes: String? = notes
        private set
    var status: PieceStatus = status
        private set
    var voided: Boolean = voided
        private set

    /** Why the piece was voided; set by [void] only. A reloaded piece does not carry it. */
    var voidReason: String? = null
        private set

    /**
     * Moves one step forward or back (RN-04, D-34). A revert is a correction (RN-05).
     *
     * @param expected the status the person saw
     * @throws PieceStatusConflict the piece is voided, or its status is not [expected]
     * @throws InvalidTransition [target] is neither the next nor the previous status
     */
    fun changeTo(expected: PieceStatus, target: PieceStatus, by: Long, at: Instant, operationId: UUID): PieceHistoryEvent {
        requireNotVoided()
        if (expected != status) throw conflict()
        val eventType = when (target) {
            status.next() -> PieceEventType.ADVANCED
            status.previous() -> PieceEventType.REVERTED
            else -> throw InvalidTransition()
        }
        val event = PieceHistoryEvent(id, eventType, status, target, at, by, operationId)
        status = target
        return event
    }

    /**
     * The owner changes name and notes while the piece is in progress (RF-27, RN-20).
     *
     * @throws PieceNotFound the piece is voided or belongs to another student
     * @throws PieceNotEditable the piece is not in progress
     * @throws DuplicatePieceName the student has another piece with that name
     */
    fun editAsOwner(ownerId: Long, text: PieceText, namesInUse: List<PieceName>) {
        if (voided || ownerId != studentId) throw PieceNotFound()
        if (!status.editableByOwner) throw PieceNotEditable()
        applyText(text, namesInUse)
    }

    /**
     * A teacher changes name and notes in any status (RF-27).
     *
     * @throws PieceStatusConflict the piece is voided
     * @throws DuplicatePieceName the student has another piece with that name
     */
    fun edit(text: PieceText, namesInUse: List<PieceName>) {
        requireNotVoided()
        applyText(text, namesInUse)
    }

    /**
     * Voids the piece (RF-56, RN-20). The event keeps the status it had and has no new one (DI-74).
     *
     * @throws PieceStatusConflict the piece is already voided
     * @throws InvalidPiece the reason is blank or longer than [VOID_REASON_MAX_LENGTH]
     */
    fun void(reason: String, by: Long, at: Instant, operationId: UUID): PieceHistoryEvent {
        requireNotVoided()
        val trimmedReason = reason.trim().ifEmpty { throw InvalidPiece("El motivo es obligatorio.") }
        if (trimmedReason.length > VOID_REASON_MAX_LENGTH) {
            throw InvalidPiece("El motivo puede tener hasta $VOID_REASON_MAX_LENGTH caracteres.")
        }
        voided = true
        voidReason = trimmedReason
        return PieceHistoryEvent(id, PieceEventType.VOIDED, status, null, at, by, operationId)
    }

    /** The conflict to report when a guarded update touched no row: voided, or changed by someone else (D-34). */
    fun conflict() = PieceStatusConflict(
        listOf(ConflictingPiece(id, name, if (voided) PieceConflictReason.VOIDED else PieceConflictReason.CHANGED)),
    )

    private fun requireNotVoided() {
        if (voided) throw conflict()
    }

    private fun applyText(text: PieceText, namesInUse: List<PieceName>) {
        requireUniqueName(text.name, namesInUse, excludingPieceId = id)
        name = text.name
        notes = text.notes
    }

    companion object {
        const val VOID_REASON_MAX_LENGTH = 500
    }
}
