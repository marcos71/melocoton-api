package ar.melocoton.pieces.application.port

import ar.melocoton.pieces.domain.NewPiece
import ar.melocoton.pieces.domain.Piece
import ar.melocoton.pieces.domain.PieceHistoryEvent
import ar.melocoton.pieces.domain.PieceName
import ar.melocoton.pieces.domain.PieceStatus
import java.time.Instant

/**
 * Output port for the write side of pieces. Every status guard is a conditional UPDATE whose affected rows
 * the caller checks before writing history (D-34). The screen reads are in [PieceQueries] (DI-60).
 */
interface PieceRepository {

    /** Inserts the piece in IN_PROGRESS with [createdAt]; flushed so plain SQL sees it. Returns its id. */
    fun create(piece: NewPiece, createdAt: Instant): Long

    /** Voided pieces included (the domain decides what a voided piece means). Null when it does not exist. */
    fun findById(id: Long): Piece?

    /** Same as [findById] for several ids; ids that do not exist are absent. */
    fun findByIds(ids: Collection<Long>): List<Piece>

    /** Names of the student's non-voided pieces (DI-26). Read it holding the student lock (DI-73). */
    fun namesInUse(studentId: Long): List<PieceName>

    /** D-34: UPDATE piece SET status = :new WHERE id = :id AND status = :expected AND voided_at IS NULL. True when one row changed. */
    fun saveStatus(id: Long, expected: PieceStatus, new: PieceStatus): Boolean

    /** Writes name and notes; guarded by voided_at IS NULL and, when [requiredStatus] is not null, by that status. True when one row changed. */
    fun saveText(piece: Piece, requiredStatus: PieceStatus?): Boolean

    /** DI-74: sets voided_at, voided_by and void_reason WHERE id AND status = :expected AND voided_at IS NULL. True when one row changed. */
    fun saveVoid(id: Long, expected: PieceStatus, reason: String, by: Long, at: Instant): Boolean

    /**
     * D-34 for many pieces, in one statement: UPDATE ... WHERE id = ANY(:ids) AND status = :expected AND voided_at IS NULL.
     * Returns the ids it changed; the caller compares them with [ids] before writing history.
     */
    fun advanceAll(ids: Collection<Long>, expected: PieceStatus, new: PieceStatus): Set<Long>

    /** Inserts the events through the immutable entity, flushed. */
    fun appendHistory(events: List<PieceHistoryEvent>)
}
