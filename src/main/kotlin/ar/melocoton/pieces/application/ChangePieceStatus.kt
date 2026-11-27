package ar.melocoton.pieces.application

import ar.melocoton.pieces.application.port.PieceDetail
import ar.melocoton.pieces.application.port.PieceQueries
import ar.melocoton.pieces.application.port.PieceRepository
import ar.melocoton.pieces.domain.PieceNotFound
import ar.melocoton.pieces.domain.PieceStatus
import ar.melocoton.students.application.port.StudentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * CU-13 4, 4a to 4c, RF-26, RF-28, CP-21: a teacher moves a piece one step. The guarantee is the guarded
 * UPDATE (D-34): the history is written only when it changed a row. Only leaving FINISHED reads names, under the
 * student lock (DI-73, DI-91), because it takes the piece's name back; every other change relies on the guarded UPDATE alone.
 */
@Service
class ChangePieceStatus(
    private val students: StudentRepository,
    private val pieces: PieceRepository,
    private val queries: PieceQueries,
    private val clock: Clock,
) {
    @Transactional
    fun change(pieceId: Long, expected: PieceStatus, target: PieceStatus, actorId: Long): PieceDetail {
        val piece = pieces.findById(pieceId) ?: throw PieceNotFound()
        piece.requireVisible()
        val namesInUse = if (piece.reclaimsNameOn(target)) {
            students.lockById(piece.studentId)
            pieces.namesInUse(piece.studentId)
        } else {
            emptyList()
        }
        val event = piece.changeTo(expected, target, namesInUse, actorId, clock.instant(), UUID.randomUUID())
        if (!pieces.saveStatus(pieceId, expected, target)) {
            throw checkNotNull(pieces.findById(pieceId)) { "Piece $pieceId disappeared" }.conflict()
        }
        pieces.appendHistory(listOf(event))
        return checkNotNull(queries.findDetail(pieceId)) { "Piece $pieceId was just changed" }
    }
}
