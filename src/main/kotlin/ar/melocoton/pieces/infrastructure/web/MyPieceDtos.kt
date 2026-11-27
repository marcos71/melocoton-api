package ar.melocoton.pieces.infrastructure.web

import ar.melocoton.pieces.application.port.PieceDetail
import ar.melocoton.pieces.application.port.PieceHistoryEntry
import ar.melocoton.pieces.application.port.PieceSummary
import ar.melocoton.pieces.domain.PieceEventType
import ar.melocoton.pieces.domain.PieceStatus
import ar.melocoton.pieces.domain.PieceText
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

private const val INSTANT_DESCRIPTION = "ISO-8601 instant in UTC"

/** Body of POST /api/v1/students/me/pieces (RF-24): the owner is the session user. */
class CreateMyPieceRequest(
    name: String,
    notes: String? = null,
) {
    @field:Schema(maxLength = PieceText.NAME_MAX_LENGTH)
    val name: String = name.trim()

    @field:Schema(maxLength = PieceText.NOTES_MAX_LENGTH, description = "Blank means no notes")
    val notes: String? = notes?.trim()
}

/** A row of the student's own piece list. */
data class MyPieceItemResponse(
    val id: Long,
    val name: String,
    val status: PieceStatus,
    @field:Schema(type = "string", format = "date-time", description = INSTANT_DESCRIPTION)
    val updatedAt: Instant,
)

/** The student's own piece. [editable] is true only while it is in progress (RF-27, DI-70). */
data class MyPieceDetailResponse(
    val id: Long,
    val name: String,
    val notes: String?,
    val status: PieceStatus,
    val editable: Boolean,
    @field:Schema(type = "string", format = "date-time", description = INSTANT_DESCRIPTION)
    val createdAt: Instant,
    @field:Schema(type = "string", format = "date-time", description = "The last history event (DI-71)")
    val updatedAt: Instant,
    @field:Schema(description = "Newest first")
    val history: List<MyPieceHistoryEntryResponse>,
)

/** One event of the history as the student sees it: no author and no operation. */
data class MyPieceHistoryEntryResponse(
    val eventType: PieceEventType,
    val previousStatus: PieceStatus?,
    val newStatus: PieceStatus?,
    @field:Schema(type = "string", format = "date-time", description = INSTANT_DESCRIPTION)
    val occurredAt: Instant,
    val correction: Boolean,
)

fun PieceSummary.toMyResponse() = MyPieceItemResponse(id = id, name = name, status = status, updatedAt = updatedAt)

fun PieceDetail.toMyResponse() = MyPieceDetailResponse(
    id = id,
    name = name,
    notes = notes,
    status = status,
    editable = editableByOwner,
    createdAt = createdAt,
    updatedAt = updatedAt,
    history = history.map { it.toMyResponse() },
)

fun PieceHistoryEntry.toMyResponse() = MyPieceHistoryEntryResponse(
    eventType = eventType,
    previousStatus = previousStatus,
    newStatus = newStatus,
    occurredAt = occurredAt,
    correction = correction,
)
