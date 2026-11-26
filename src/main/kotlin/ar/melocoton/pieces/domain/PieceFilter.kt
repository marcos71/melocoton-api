package ar.melocoton.pieces.domain

/** What a piece list narrows by; a null field does not filter (RF-32). */
data class PieceFilter(val studentId: Long? = null, val status: PieceStatus? = null)
