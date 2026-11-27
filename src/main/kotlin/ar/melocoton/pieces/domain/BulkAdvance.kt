package ar.melocoton.pieces.domain

import java.time.Instant
import java.util.UUID

/** One piece of a bulk change and the status the person saw it in (D-34). */
data class BulkItem(val pieceId: Long, val expectedStatus: PieceStatus)

/**
 * A bulk advance already checked (CU-14, RF-29, RF-30): every piece is in the same status, not finished, not
 * voided, and moves to the next one. A bulk change never reverts (RN-04). Build it with [plan].
 */
class BulkAdvance private constructor(
    val pieceIds: List<Long>,
    val from: PieceStatus,
    val to: PieceStatus,
    val studentCount: Int,
) {
    /** One ADVANCED event per piece, all with the same [operationId] (RF-29). */
    fun events(by: Long, at: Instant, operationId: UUID): List<PieceHistoryEvent> =
        pieceIds.map { PieceHistoryEvent(it, PieceEventType.ADVANCED, from, to, at, by, operationId) }

    /** The conflict after the guarded UPDATE missed some pieces: the reason comes from their reloaded state. */
    fun conflictAfterRace(reloaded: List<Piece>): PieceStatusConflict = PieceStatusConflict(reloaded.map { it.toConflicting() })

    companion object {
        /** Technical guard on the size of one request; there is no business cap (DI-69). */
        const val MAX_ITEMS = 500

        /**
         * Checks, in this order, the selection against the pieces as loaded.
         *
         * @param current the pieces of the selection that exist
         * @throws InvalidPiece the selection is empty, has a repeated piece or has more than [MAX_ITEMS]
         * @throws MixedStatuses the pieces were seen in different statuses
         * @throws NoNextStatus the pieces were seen finished
         * @throws PieceNotFound a piece of the selection does not exist
         * @throws PieceStatusConflict some pieces are voided or no longer in the expected status; lists all of them
         */
        fun plan(items: List<BulkItem>, current: List<Piece>): BulkAdvance {
            val ids = items.map { it.pieceId }
            if (ids.isEmpty()) throw InvalidPiece("Elegí al menos una pieza.")
            if (ids.size > MAX_ITEMS) throw InvalidPiece("Se pueden cambiar hasta $MAX_ITEMS piezas a la vez.")
            if (ids.toSet().size != ids.size) throw InvalidPiece("Hay piezas repetidas en la selección.")
            val from = items.map { it.expectedStatus }.distinct().singleOrNull() ?: throw MixedStatuses()
            val to = from.next() ?: throw NoNextStatus()
            val byId = current.associateBy { it.id }
            val pieces = ids.map { byId[it] ?: throw PieceNotFound() }
            val stale = pieces.filter { it.voided || it.status != from }
            if (stale.isNotEmpty()) throw PieceStatusConflict(stale.map { it.toConflicting() })
            return BulkAdvance(ids, from, to, studentCount = pieces.map { it.studentId }.distinct().size)
        }
    }
}

private fun Piece.toConflicting() =
    ConflictingPiece(id, name, if (voided) PieceConflictReason.VOIDED else PieceConflictReason.CHANGED)
