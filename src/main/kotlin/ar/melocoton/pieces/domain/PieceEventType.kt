package ar.melocoton.pieces.domain

/** What happened to a piece, as the history records it (RN-05). */
enum class PieceEventType {
    CREATED, ADVANCED, REVERTED, VOIDED;

    /** A revert is a correction, not a normal step (RN-05). */
    val isCorrection: Boolean get() = this == REVERTED
}
