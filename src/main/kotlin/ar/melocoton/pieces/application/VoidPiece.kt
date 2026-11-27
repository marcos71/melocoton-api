package ar.melocoton.pieces.application

import ar.melocoton.pieces.application.port.PieceRepository
import ar.melocoton.pieces.domain.PieceNotFound
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** CU-13 A1, RF-56, RF-61, DI-74: a teacher voids a piece with a reason; the piece disappears and its history stays. */
@Service
class VoidPiece(
    private val pieces: PieceRepository,
    private val clock: Clock,
) {
    @Transactional
    fun voidPiece(pieceId: Long, reason: String, actorId: Long) {
        val now = clock.instant()
        val piece = pieces.findById(pieceId) ?: throw PieceNotFound()
        piece.requireVisible()
        val event = piece.void(reason, actorId, now, UUID.randomUUID())
        val voidReason = checkNotNull(piece.voidReason) { "A voided piece has a reason" }
        if (!pieces.saveVoid(pieceId, piece.status, voidReason, actorId, now)) {
            throw checkNotNull(pieces.findById(pieceId)) { "Piece $pieceId disappeared" }.conflict()
        }
        pieces.appendHistory(listOf(event))
    }
}
