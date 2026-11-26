package ar.melocoton.pieces.domain

/**
 * The name and the notes of a piece, already validated (RF-24, DI-72): the name is required and trimmed,
 * blank notes mean no notes.
 */
class PieceText private constructor(val name: String, val notes: String?) {

    companion object {
        const val NAME_MAX_LENGTH = 100
        const val NOTES_MAX_LENGTH = 500

        /** @throws InvalidPiece the name is blank or longer than [NAME_MAX_LENGTH], or the notes are longer than [NOTES_MAX_LENGTH] */
        fun of(name: String, notes: String?): PieceText {
            val trimmedName = name.trim().ifEmpty { throw InvalidPiece("El nombre es obligatorio.") }
            if (trimmedName.length > NAME_MAX_LENGTH) {
                throw InvalidPiece("El nombre puede tener hasta $NAME_MAX_LENGTH caracteres.")
            }
            val trimmedNotes = notes?.trim()?.takeIf { it.isNotEmpty() }
            if (trimmedNotes != null && trimmedNotes.length > NOTES_MAX_LENGTH) {
                throw InvalidPiece("Las notas pueden tener hasta $NOTES_MAX_LENGTH caracteres.")
            }
            return PieceText(trimmedName, trimmedNotes)
        }
    }
}
