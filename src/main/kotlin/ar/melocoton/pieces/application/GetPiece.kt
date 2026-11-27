package ar.melocoton.pieces.application

import ar.melocoton.pieces.application.port.PieceDetail
import ar.melocoton.pieces.application.port.PieceQueries
import ar.melocoton.pieces.domain.PieceNotFound
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** CU-13, RF-28, DI-74, DI-75: a piece with its history. Another student's piece is not found for a student, like a voided one. */
@Service
class GetPiece(private val queries: PieceQueries) {

    @Transactional(readOnly = true)
    fun get(id: Long): PieceDetail = queries.findDetail(id) ?: throw PieceNotFound()

    @Transactional(readOnly = true)
    fun getOwn(studentId: Long, id: Long): PieceDetail =
        get(id).takeIf { it.studentId == studentId } ?: throw PieceNotFound()
}
