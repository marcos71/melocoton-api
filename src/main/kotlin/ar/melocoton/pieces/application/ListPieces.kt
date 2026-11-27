package ar.melocoton.pieces.application

import ar.melocoton.pieces.application.port.PieceQueries
import ar.melocoton.pieces.application.port.PieceSummary
import ar.melocoton.pieces.domain.PieceFilter
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** CU-13, RF-31, RF-32: the pieces of everyone for the teachers (inactive students included), or of one student for themselves. */
@Service
class ListPieces(private val queries: PieceQueries) {

    @Transactional(readOnly = true)
    fun list(filter: PieceFilter): List<PieceSummary> = queries.findAll(filter)

    @Transactional(readOnly = true)
    fun listOwn(studentId: Long): List<PieceSummary> = queries.findAll(PieceFilter(studentId = studentId))
}
