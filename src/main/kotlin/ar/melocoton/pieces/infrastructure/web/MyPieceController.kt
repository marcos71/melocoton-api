package ar.melocoton.pieces.infrastructure.web

import ar.melocoton.pieces.application.CreatePiece
import ar.melocoton.pieces.application.EditPiece
import ar.melocoton.pieces.application.GetPiece
import ar.melocoton.pieces.application.ListPieces
import ar.melocoton.shared.domain.SessionUser
import ar.melocoton.shared.infrastructure.web.ErrorResponse
import io.swagger.v3.oas.annotations.Operation
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
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** The student's own pieces (CU-12, CU-13, RF-24, RF-27, RF-31). The role rule is in SecurityConfig; the student is the session user and never sends an id (DI-28). */
@RestController
@RequestMapping("/api/v1/students/me/pieces")
@Tag(name = "My pieces")
@SecurityRequirement(name = "bearerAuth")
class MyPieceController(
    private val listPieces: ListPieces,
    private val getPiece: GetPiece,
    private val createPiece: CreatePiece,
    private val editPiece: EditPiece,
) {

    @GetMapping
    @Operation(
        operationId = "listMyPieces",
        summary = "The student's own pieces, most recently updated first (RF-31, DI-71)",
        description = "Voided pieces are not listed (DI-74).",
    )
    @ApiResponses(ApiResponse(responseCode = "200", description = "The pieces"))
    fun list(@AuthenticationPrincipal user: SessionUser): List<MyPieceItemResponse> =
        listPieces.listOwn(user.userId).map { it.toMyResponse() }

    @GetMapping("/{id}")
    @Operation(operationId = "getMyPiece", summary = "One of the student's own pieces with its history (RF-28)")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The piece"),
        ApiResponse(
            responseCode = "404",
            description = "not_found: the piece does not exist, is voided or belongs to another student (DI-75)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun get(@AuthenticationPrincipal user: SessionUser, @PathVariable id: Long): MyPieceDetailResponse =
        getPiece.getOwn(user.userId, id).toMyResponse()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        operationId = "createMyPiece",
        summary = "The student creates a piece, In progress (CU-12, RF-24, RF-25)",
        description = "The name must not be used by another piece of the student that is neither voided nor finished (DI-26, DI-73).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "The new piece"),
        ApiResponse(
            responseCode = "400",
            description = "validation_error: the name is missing, blank or longer than 100 characters, or the notes are longer than 500",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "duplicate_piece_name",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun create(@AuthenticationPrincipal user: SessionUser, @RequestBody request: CreateMyPieceRequest): MyPieceDetailResponse =
        createPiece.create(user.userId, request.name, request.notes, actorId = user.userId).toMyResponse()

    @PatchMapping("/{id}")
    @Operation(
        operationId = "editMyPiece",
        summary = "The student edits the name and notes of a piece that is In progress (RF-27, RN-20)",
        description = "The notes replace the stored value. Any other field, the status included, is rejected.",
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
            description = "not_found: the piece does not exist, is voided or belongs to another student (DI-75)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "piece_not_editable: the piece is no longer In progress. duplicate_piece_name",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun edit(
        @AuthenticationPrincipal user: SessionUser,
        @PathVariable id: Long,
        @RequestBody request: EditPieceRequest,
    ): MyPieceDetailResponse = editPiece.editOwn(user.userId, id, request.name, request.notes).toMyResponse()
}
