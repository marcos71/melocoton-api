package ar.melocoton.pieces.infrastructure.web

import ar.melocoton.pieces.application.BulkChangeResult
import ar.melocoton.pieces.application.port.PieceDetail
import ar.melocoton.pieces.application.port.PieceHistoryEntry
import ar.melocoton.pieces.application.port.PieceSummary
import ar.melocoton.pieces.domain.BulkAdvance
import ar.melocoton.pieces.domain.BulkItem
import ar.melocoton.pieces.domain.Piece
import ar.melocoton.pieces.domain.PieceEventType
import ar.melocoton.pieces.domain.PieceStatus
import ar.melocoton.pieces.domain.PieceText
import com.fasterxml.jackson.annotation.JsonAnySetter
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

private const val INSTANT_DESCRIPTION = "ISO-8601 instant in UTC"

/**
 * Body of POST /api/v1/pieces: the teacher creates a piece for a student (CU-12). The text is trimmed here;
 * a blank or too long value is rejected by the domain (PieceText), so POST and PATCH answer the same.
 */
class CreatePieceRequest(
    val studentId: Long,
    name: String,
    notes: String? = null,
) {
    @field:Schema(maxLength = PieceText.NAME_MAX_LENGTH)
    val name: String = name.trim()

    @field:Schema(maxLength = PieceText.NOTES_MAX_LENGTH, description = "Blank means no notes")
    val notes: String? = notes?.trim()
}

/**
 * Body of PATCH on a piece (RF-27): the name is required and the notes replace the stored value (null or blank
 * clears them, DI-72). Any other field, `status` included, is rejected with 400 instead of being ignored.
 */
class EditPieceRequest(
    name: String,
    notes: String? = null,
) {
    @field:Schema(maxLength = PieceText.NAME_MAX_LENGTH)
    val name: String = name.trim()

    @field:Schema(maxLength = PieceText.NOTES_MAX_LENGTH, description = "Null or blank clears the notes")
    val notes: String? = notes?.trim()

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
class VoidPieceRequest(reason: String) {
    @field:Schema(maxLength = Piece.VOID_REASON_MAX_LENGTH)
    val reason: String = reason.trim()
}

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
