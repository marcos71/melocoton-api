package ar.melocoton.pieces.application

import ar.melocoton.pieces.application.port.PieceDetail
import ar.melocoton.pieces.application.port.PieceQueries
import ar.melocoton.pieces.application.port.PieceRepository
import ar.melocoton.pieces.domain.PieceNotFound
import ar.melocoton.pieces.domain.PieceStatus
import ar.melocoton.pieces.domain.PieceText
import ar.melocoton.students.application.port.StudentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * CU-13 step 3 and AL1, RF-27: editing name and notes. It writes no history event: RF-28 lists creation,
 * advances, reverts and voids only. The owner's student row is locked before the names are read (DI-73).
 */
@Service
class EditPiece(
    private val students: StudentRepository,
    private val pieces: PieceRepository,
    private val queries: PieceQueries,
) {
    /** The owner edits their own piece while it is in progress. */
    @Transactional
    fun editOwn(studentId: Long, pieceId: Long, name: String, notes: String?): PieceDetail {
        val piece = pieces.findById(pieceId) ?: throw PieceNotFound()
        // Before locking anything: another student's piece must not even touch their row.
        piece.requireVisibleTo(studentId)
        students.lockById(studentId)
        val text = PieceText.of(name, notes)
        piece.editAsOwner(studentId, text, pieces.namesInUse(studentId))
        if (!pieces.saveText(piece, requiredStatus = PieceStatus.IN_PROGRESS)) {
            throw reload(pieceId).ownerEditConflict()
        }
        return detail(pieceId)
    }

    /** A teacher edits any non-voided piece, in any status. */
    @Transactional
    fun edit(pieceId: Long, name: String, notes: String?): PieceDetail {
        val piece = pieces.findById(pieceId) ?: throw PieceNotFound()
        piece.requireVisible()
        students.lockById(piece.studentId)
        piece.edit(PieceText.of(name, notes), pieces.namesInUse(piece.studentId))
        if (!pieces.saveText(piece, requiredStatus = null)) {
            // Only a voidance in between can make the guarded UPDATE miss.
            throw reload(pieceId).conflict()
        }
        return detail(pieceId)
    }

    private fun reload(pieceId: Long) = checkNotNull(pieces.findById(pieceId)) { "Piece $pieceId disappeared" }

    private fun detail(pieceId: Long) = checkNotNull(queries.findDetail(pieceId)) { "Piece $pieceId was just edited" }
}
