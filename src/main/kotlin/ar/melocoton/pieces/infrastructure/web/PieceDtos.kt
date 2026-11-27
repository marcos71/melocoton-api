package ar.melocoton.pieces.infrastructure.web

import ar.melocoton.pieces.application.BulkChangeResult
import ar.melocoton.pieces.application.port.PieceCursor
import ar.melocoton.pieces.application.port.PieceDetail
import ar.melocoton.pieces.application.port.PieceHistoryEntry
import ar.melocoton.pieces.application.port.PiecePage
import ar.melocoton.pieces.application.port.PieceSummary
import ar.melocoton.pieces.domain.BulkAdvance
import ar.melocoton.pieces.domain.BulkItem
import ar.melocoton.pieces.domain.InvalidPiece
import ar.melocoton.pieces.domain.Piece
import ar.melocoton.pieces.domain.PieceEventType
import ar.melocoton.pieces.domain.PieceStatus
import ar.melocoton.pieces.domain.PieceText
import com.fasterxml.jackson.annotation.JsonAnySetter
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

private const val INSTANT_DESCRIPTION = "ISO-8601 instant in UTC"
const val DEFAULT_PAGE_SIZE = 50
const val MAX_PAGE_SIZE = 100
private const val CURSOR_SEPARATOR = "|"
private const val INVALID_CURSOR_MESSAGE = "No pudimos cargar mÃ¡s piezas. VolvÃ© a cargar la lista."

/** Opaque cursor for the client: base64url (no padding) of "<instant>|<id>" (DI-87). */
fun PieceCursor.toToken(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString("$updatedAt$CURSOR_SEPARATOR$id".toByteArray())

/** Throws [InvalidPiece] (validation_error) for anything this API did not issue. */
fun String.toPieceCursor(): PieceCursor = try {
    val parts = String(Base64.getUrlDecoder().decode(this)).split(CURSOR_SEPARATOR)
    require(parts.size == 2)
    val id = parts[1].toLong()
    require(id > 0)
    PieceCursor(Instant.parse(parts[0]), id)
} catch (e: IllegalArgumentException) {
    throw InvalidPiece(INVALID_CURSOR_MESSAGE)
} catch (e: DateTimeParseException) {
    throw InvalidPiece(INVALID_CURSOR_MESSAGE)
}

/** Null means the default; outside 1..[MAX_PAGE_SIZE] throws [InvalidPiece]. */
fun Int?.toPageSize(): Int = when {
    this == null -> DEFAULT_PAGE_SIZE
    this in 1..MAX_PAGE_SIZE -> this
    else -> throw InvalidPiece("La cantidad por pÃ¡gina tiene que estar entre 1 y $MAX_PAGE_SIZE.")
}

/**
 * Body of POST /api/v1/pieces: the teacher creates a piece for a student (CU-12). The text is trimmed and
 * validated by the domain (PieceText), so POST and PATCH answer the same.
 */
class CreatePieceRequest(
    val studentId: Long,
    @field:Schema(maxLength = PieceText.NAME_MAX_LENGTH)
    val name: String,
    @field:Schema(maxLength = PieceText.NOTES_MAX_LENGTH, description = "Blank means no notes")
    val notes: String? = null,
)

/**
 * Body of PATCH on a piece (RF-27): the name is required and the notes replace the stored value (null or blank
 * clears them, DI-72). Any other field, `status` included, is rejected with 400 instead of being ignored.
 */
class EditPieceRequest(
    @field:Schema(maxLength = PieceText.NAME_MAX_LENGTH)
    val name: String,
    @field:Schema(maxLength = PieceText.NOTES_MAX_LENGTH, description = "Null or blank clears the notes")
    val notes: String? = null,
) {

    /** Jackson calls this for every JSON field the class does not declare; failing here makes it a 400. */
    @JsonAnySetter
    @Schema(hidden = true)
    fun rejectUnknownField(field: String, value: Any?) {
        throw IllegalArgumentException("Field '$field' cannot be edited here")
    }
}

/** Body of POST /api/v1/pieces/{id}/change-status (RF-26): the status the person saw and the one they want (D-34). */
class ChangePieceStatusRequest(
    val expectedStatus: PieceStatus,
    val newStatus: PieceStatus,
)

/** One piece of a bulk change and the status the person saw it in (D-34). */
class BulkChangeItemRequest(
    val pieceId: Long,
    val expectedStatus: PieceStatus,
)

/** Body of POST /api/v1/pieces/bulk-change-status (RF-29): the pieces to advance, all in the same status. */
class BulkChangePieceStatusRequest(
    @field:ArraySchema(maxItems = BulkAdvance.MAX_ITEMS, schema = Schema(description = "Up to ${BulkAdvance.MAX_ITEMS} items"))
    val items: List<BulkChangeItemRequest>,
)

/** Result of a bulk change. [operationId] groups the history events of all the pieces. */
data class BulkChangeResponse(
    val operationId: UUID,
    val newStatus: PieceStatus,
    val count: Int,
    @field:Schema(description = "How many students own the changed pieces")
    val studentCount: Int,
)

/** Body of POST /api/v1/pieces/{id}/void (RF-56). */
class VoidPieceRequest(
    @field:Schema(maxLength = Piece.VOID_REASON_MAX_LENGTH)
    val reason: String,
)

/** Who owns a piece. */
data class PieceStudentResponse(val id: Long, val name: String)

/** Who caused a history event. */
data class PieceUserResponse(val id: Long, val name: String)

/** A row of the teachers' piece list. [advanceTo] and [revertTo] are the actions the piece allows (DI-70). */
data class PieceItemResponse(
    val id: Long,
    val name: String,
    val status: PieceStatus,
    val advanceTo: PieceStatus?,
    val revertTo: PieceStatus?,
    @field:Schema(type = "string", format = "date-time", description = INSTANT_DESCRIPTION)
    val updatedAt: Instant,
    val student: PieceStudentResponse,
)

/** A piece with its history for the teachers (RF-28). */
data class PieceDetailResponse(
    val id: Long,
    val name: String,
    val notes: String?,
    val status: PieceStatus,
    val advanceTo: PieceStatus?,
    val revertTo: PieceStatus?,
    @field:Schema(type = "string", format = "date-time", description = INSTANT_DESCRIPTION)
    val createdAt: Instant,
    @field:Schema(type = "string", format = "date-time", description = "The last history event (DI-71)")
    val updatedAt: Instant,
    val student: PieceStudentResponse,
    @field:Schema(description = "Newest first")
    val history: List<PieceHistoryEntryResponse>,
)

/** One event of a piece's history. A REVERTED event is a correction. */
data class PieceHistoryEntryResponse(
    val eventType: PieceEventType,
    val previousStatus: PieceStatus?,
    val newStatus: PieceStatus?,
    @field:Schema(type = "string", format = "date-time", description = INSTANT_DESCRIPTION)
    val occurredAt: Instant,
    val correction: Boolean,
    @field:Schema(description = "Groups the pieces of one bulk change")
    val operationId: UUID,
    val changedBy: PieceUserResponse,
)

/** A page of the teachers' piece list (DI-87). */
data class PiecePageResponse(
    val items: List<PieceItemResponse>,
    @field:Schema(description = "Pass as cursor to get the next page; null on the last page")
    val nextCursor: String?,
)

fun PiecePage.toResponse() = PiecePageResponse(items.map { it.toResponse() }, next?.toToken())

fun PieceSummary.toResponse() = PieceItemResponse(
    id = id,
    name = name,
    status = status,
    advanceTo = advanceTo,
    revertTo = revertTo,
    updatedAt = updatedAt,
    student = PieceStudentResponse(studentId, studentName),
)

fun PieceDetail.toResponse() = PieceDetailResponse(
    id = id,
    name = name,
    notes = notes,
    status = status,
    advanceTo = advanceTo,
    revertTo = revertTo,
    createdAt = createdAt,
    updatedAt = updatedAt,
    student = PieceStudentResponse(studentId, studentName),
    history = history.map { it.toResponse() },
)

fun PieceHistoryEntry.toResponse() = PieceHistoryEntryResponse(
    eventType = eventType,
    previousStatus = previousStatus,
    newStatus = newStatus,
    occurredAt = occurredAt,
    correction = correction,
    operationId = operationId,
    changedBy = PieceUserResponse(changedById, changedByName),
)

fun BulkChangePieceStatusRequest.toItems() = items.map { BulkItem(it.pieceId, it.expectedStatus) }

fun BulkChangeResult.toResponse() = BulkChangeResponse(operationId, newStatus, count, studentCount)
