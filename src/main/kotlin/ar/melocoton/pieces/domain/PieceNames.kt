package ar.melocoton.pieces.domain

/** The name of one of a student's non-voided pieces (DI-26). */
data class PieceName(val pieceId: Long, val name: String, val status: PieceStatus)

/**
 * A student cannot have two pieces with the same name, trimmed and ignoring case (DI-26, DI-73).
 * Only pieces that are not voided and not finished hold their name: a finished piece frees it.
 * This function is the single place that says what "name in use" means; change it here if that rule changes.
 *
 * @param namesInUse the student's non-voided pieces; the port never returns the voided ones
 * @param excludingPieceId the piece being renamed, which may keep its own name
 * @throws DuplicatePieceName another piece holds the name
 */
fun requireUniqueName(name: String, namesInUse: List<PieceName>, excludingPieceId: Long? = null) {
    val wanted = name.normalizedName()
    val taken = namesInUse.any {
        it.pieceId != excludingPieceId && it.status != PieceStatus.FINISHED && it.name.normalizedName() == wanted
    }
    if (taken) throw DuplicatePieceName()
}

private fun String.normalizedName() = trim().lowercase()
