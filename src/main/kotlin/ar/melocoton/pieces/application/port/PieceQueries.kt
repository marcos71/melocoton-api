package ar.melocoton.pieces.application.port

import ar.melocoton.pieces.domain.PieceFilter

/** Output port for the piece screens: reads composed from several tables, as read models (DI-60). */
interface PieceQueries {

    /**
     * Non-voided pieces matching the filter, with the owner's name, ordered by updatedAt desc, id desc (DI-71),
     * strictly after [after] and at most [limit] (DI-87).
     */
    fun findPage(filter: PieceFilter, after: PieceCursor?, limit: Int): PiecePage

    /** The non-voided piece with its history, newest first. Null when it does not exist or is voided (DI-74). */
    fun findDetail(id: Long): PieceDetail?
}
