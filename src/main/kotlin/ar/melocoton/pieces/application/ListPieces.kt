package ar.melocoton.pieces.application

import ar.melocoton.pieces.application.port.PieceCursor
import ar.melocoton.pieces.application.port.PiecePage
import ar.melocoton.pieces.application.port.PieceQueries
import ar.melocoton.pieces.domain.PieceFilter
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * CU-13, RF-31, RF-32: the pieces of everyone for the teachers (inactive students included), or of one student for
 * themselves, one page at a time (DI-87).
 */
@Service
class ListPieces(private val queries: PieceQueries) {

    @Transactional(readOnly = true)
    fun list(filter: PieceFilter, after: PieceCursor?, limit: Int): PiecePage = queries.findPage(filter, after, limit)

    @Transactional(readOnly = true)
    fun listOwn(studentId: Long, after: PieceCursor?, limit: Int): PiecePage =
        queries.findPage(PieceFilter(studentId = studentId), after, limit)
}
