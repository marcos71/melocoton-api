package ar.melocoton.pieces.domain

/** The firing stages of a piece, in order (RN-04). */
enum class PieceStatus {
    IN_PROGRESS, BISQUE_FIRED, GLAZED, FINISHED;

    /** The status an advance leads to; null for the last one. */
    fun next(): PieceStatus? = entries.getOrNull(ordinal + 1)

    /** The status a revert leads to; null for the first one. */
    fun previous(): PieceStatus? = entries.getOrNull(ordinal - 1)

    /** RN-20, RF-27: the owner edits name and notes only while the piece is in progress. */
    val editableByOwner: Boolean get() = this == IN_PROGRESS
}

private val SPANISH_STATUSES = mapOf(
    PieceStatus.IN_PROGRESS to "En proceso",
    PieceStatus.BISQUE_FIRED to "Bizcochada",
    PieceStatus.GLAZED to "Esmaltada",
    PieceStatus.FINISHED to "Terminada",
)

/** Status name for the messages people read (DI-20). */
fun PieceStatus.spanishName(): String = SPANISH_STATUSES.getValue(this)
