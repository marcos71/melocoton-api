package ar.melocoton.pieces.domain

import ar.melocoton.shared.domain.BusinessErrorKind
import ar.melocoton.shared.domain.BusinessRuleViolation

/** The piece data is not valid: a blank or too long text (DI-72), or an empty or repeated selection. */
class InvalidPiece(message: String) : BusinessRuleViolation(
    code = "validation_error",
    message = message,
    kind = BusinessErrorKind.INVALID,
)

/** The id does not belong to a piece the person can see: missing, voided (DI-74) or someone else's (DI-75). */
class PieceNotFound : BusinessRuleViolation(
    code = "not_found",
    message = "No existe esa pieza.",
    kind = BusinessErrorKind.NOT_FOUND,
)

/** The new status is neither the next nor the previous one (RN-04). */
class InvalidTransition : BusinessRuleViolation(
    code = "invalid_transition",
    message = "Solo se puede avanzar al estado siguiente o volver al anterior.",
    kind = BusinessErrorKind.INVALID,
)

/** The owner tries to edit a piece that is no longer in progress (RF-27, RN-20). */
class PieceNotEditable : BusinessRuleViolation(
    code = "piece_not_editable",
    message = "Solo podés editar la pieza mientras está En proceso.",
    kind = BusinessErrorKind.CONFLICT,
)

/** The student already has another piece with that name (DI-26, DI-73). */
class DuplicatePieceName : BusinessRuleViolation(
    code = "duplicate_piece_name",
    message = "Ya hay otra pieza con ese nombre. Usá uno distinto, por ejemplo «Bowl 2».",
    kind = BusinessErrorKind.CONFLICT,
)

/** A bulk change mixes pieces of different statuses (RF-29). */
class MixedStatuses : BusinessRuleViolation(
    code = "mixed_statuses",
    message = "Todas las piezas tienen que estar en el mismo estado para cambiarlas juntas.",
    kind = BusinessErrorKind.INVALID,
)

/** A bulk change selects finished pieces, which have no next status (RN-04). */
class NoNextStatus : BusinessRuleViolation(
    code = "no_next_status",
    message = "Las piezas Terminadas no tienen estado siguiente.",
    kind = BusinessErrorKind.INVALID,
)

/** Why a piece was not changed (RF-30, RF-61). */
enum class PieceConflictReason { CHANGED, VOIDED }

/** A piece that did not change as the person expected. */
data class ConflictingPiece(val id: Long, val name: String, val reason: PieceConflictReason)

/** `details` of `status_conflict`: the pieces that changed or were voided in the meantime. */
data class PieceConflictDetails(val pieces: List<ConflictingPiece>)

/** Someone changed or voided the pieces after the person looked at them (RN-04, D-34, RF-30, RF-61). */
class PieceStatusConflict(pieces: List<ConflictingPiece>) : BusinessRuleViolation(
    code = "status_conflict",
    message = conflictMessage(pieces),
    kind = BusinessErrorKind.CONFLICT,
    details = PieceConflictDetails(pieces),
)

private const val RETRY_HINT = "Actualizá la lista y volvé a intentar."

private fun conflictMessage(pieces: List<ConflictingPiece>): String {
    val single = pieces.singleOrNull() ?: return "${pieces.size} piezas cambiaron o fueron anuladas mientras tanto. $RETRY_HINT"
    return when (single.reason) {
        PieceConflictReason.CHANGED -> "Alguien modificó «${single.name}» mientras tanto. $RETRY_HINT"
        PieceConflictReason.VOIDED -> "«${single.name}» fue anulada mientras tanto. $RETRY_HINT"
    }
}
