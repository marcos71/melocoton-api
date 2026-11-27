package ar.melocoton.pieces.infrastructure.web

import ar.melocoton.pieces.application.BulkChangePieceStatus
import ar.melocoton.pieces.application.ChangePieceStatus
import ar.melocoton.pieces.application.CreatePiece
import ar.melocoton.pieces.application.EditPiece
import ar.melocoton.pieces.application.GetPiece
import ar.melocoton.pieces.application.ListPieces
import ar.melocoton.pieces.application.VoidPiece
import ar.melocoton.pieces.domain.PieceFilter
import ar.melocoton.pieces.domain.PieceStatus
import ar.melocoton.shared.domain.SessionUser
import ar.melocoton.shared.infrastructure.web.ErrorResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

private const val NOT_FOUND_DESCRIPTION = "not_found: the piece does not exist or is voided (DI-74)"

/** The pieces of every student, for the lead and the assistant teacher (CU-12, CU-13, RF-24 to RF-28, RF-31, RF-32, RF-56). The role rule is in SecurityConfig. */
@RestController
@RequestMapping("/api/v1/pieces")
@Tag(name = "Pieces")
@SecurityRequirement(name = "bearerAuth")
class PieceController(
    private val listPieces: ListPieces,
    private val getPiece: GetPiece,
    private val createPiece: CreatePiece,
    private val editPiece: EditPiece,
    private val changePieceStatus: ChangePieceStatus,
    private val voidPiece: VoidPiece,
    private val bulkChangePieceStatus: BulkChangePieceStatus,
) {

    @GetMapping
    @Operation(
        operationId = "listPieces",
        summary = "List the pieces of all students, most recently updated first (RF-31, RF-32, DI-71)",
        description = "Pieces of inactive students are included; voided pieces are not (DI-74). " +
            "studentId and status narrow the list when sent. advanceTo and revertTo are the actions each piece allows (DI-70). " +
            "Paged with a cursor (DI-87): send the nextCursor of a page to get the next one; it is null on the last page.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The pieces"),
        ApiResponse(
            responseCode = "400",
            description = "validation_error: status is not a piece status, the cursor is not valid or limit is not between 1 and 100",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun list(
        @RequestParam(required = false) studentId: Long?,
        @RequestParam(required = false) status: PieceStatus?,
        @Parameter(description = "The nextCursor of the previous page; omit for the first page")
        @RequestParam(required = false) cursor: String?,
        @Parameter(schema = Schema(type = "integer", minimum = "1", maximum = "$MAX_PAGE_SIZE", defaultValue = "$DEFAULT_PAGE_SIZE"))
        @RequestParam(required = false) limit: Int?,
    ): PiecePageResponse =
        listPieces.list(PieceFilter(studentId, status), cursor?.toPieceCursor(), limit.toPageSize()).toResponse()

    @GetMapping("/{id}")
    @Operation(operationId = "getPiece", summary = "A piece with its history, newest event first (RF-28)")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The piece"),
        ApiResponse(
            responseCode = "404",
            description = NOT_FOUND_DESCRIPTION,
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun get(@PathVariable id: Long): PieceDetailResponse = getPiece.get(id).toResponse()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        operationId = "createPiece",
        summary = "A teacher creates a piece for a student, In progress (CU-12, RF-24, RF-25)",
        description = "The student must be active. The name must not be used by another piece of the student that is neither voided nor finished (DI-26, DI-73).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "The new piece"),
        ApiResponse(
            responseCode = "400",
            description = "validation_error: the name is missing, blank or longer than 100 characters, or the notes are longer than 500",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "not_found: the id is not a student",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "student_inactive. duplicate_piece_name",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun create(@AuthenticationPrincipal user: SessionUser, @RequestBody request: CreatePieceRequest): PieceDetailResponse =
        createPiece.create(request.studentId, request.name, request.notes, actorId = user.userId).toResponse()

    @PatchMapping("/{id}")
    @Operation(
        operationId = "editPiece",
        summary = "A teacher edits the name and notes of a piece in any status (RF-27)",
        description = "The notes replace the stored value. Any other field is rejected.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The piece, edited"),
        ApiResponse(
            responseCode = "400",
            description = "validation_error: invalid name or notes, or a field that cannot be edited",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = NOT_FOUND_DESCRIPTION,
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "status_conflict: the piece was voided meanwhile, details is PieceConflictDetails. duplicate_piece_name",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun edit(@PathVariable id: Long, @RequestBody request: EditPieceRequest): PieceDetailResponse =
        editPiece.edit(id, request.name, request.notes).toResponse()

    @PostMapping("/{id}/change-status")
    @Operation(
        operationId = "changePieceStatus",
        summary = "Advance a piece one step or revert it one step (CU-13, RF-26, RN-04, D-34)",
        description = "expectedStatus is the status the person saw; if the piece has another one nothing changes. " +
            "A revert is recorded as a correction (RN-05).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The piece, changed"),
        ApiResponse(
            responseCode = "400",
            description = "invalid_transition: newStatus is neither the next nor the previous status. validation_error",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = NOT_FOUND_DESCRIPTION,
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "status_conflict: the piece changed or was voided meanwhile, details is PieceConflictDetails. " +
                "duplicate_piece_name: reverting a Terminada piece whose name another active piece of the student now uses",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun changeStatus(
        @AuthenticationPrincipal user: SessionUser,
        @PathVariable id: Long,
        @RequestBody request: ChangePieceStatusRequest,
    ): PieceDetailResponse =
        changePieceStatus.change(id, request.expectedStatus, request.newStatus, actorId = user.userId).toResponse()

    @PostMapping("/bulk-change-status")
    @Operation(
        operationId = "bulkChangePieceStatus",
        summary = "Advance several pieces of the same status together (CU-14, RF-29, RF-30, D-06)",
        description = "All or nothing. Every item carries the status the person saw; they must all be the same and " +
            "not Terminada. A bulk change only advances. If any piece changed or was voided meanwhile, nothing changes " +
            "and the response lists them all.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The summary of the change"),
        ApiResponse(
            responseCode = "400",
            description = "validation_error: empty selection, repeated piece or more than 500 items. " +
                "mixed_statuses: the items have different expected statuses. no_next_status: the pieces are Terminadas",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "not_found: a piece of the selection does not exist",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "status_conflict: some pieces changed or are voided, details is PieceConflictDetails",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun bulkChangeStatus(
        @AuthenticationPrincipal user: SessionUser,
        @RequestBody request: BulkChangePieceStatusRequest,
    ): BulkChangeResponse = bulkChangePieceStatus.advance(request.toItems(), actorId = user.userId).toResponse()

    @PostMapping("/{id}/void")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        operationId = "voidPiece",
        summary = "Void a piece with a reason (CU-13, RF-56, RN-20, DI-74)",
        description = "The piece disappears from every list and read; its history stays.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "The piece was voided"),
        ApiResponse(
            responseCode = "400",
            description = "validation_error: the reason is missing, blank or longer than 500 characters",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = NOT_FOUND_DESCRIPTION,
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "status_conflict: the piece changed or was voided meanwhile, details is PieceConflictDetails",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun void(@AuthenticationPrincipal user: SessionUser, @PathVariable id: Long, @RequestBody request: VoidPieceRequest) {
        voidPiece.voidPiece(id, request.reason, actorId = user.userId)
    }
}
