package ar.melocoton.auth.infrastructure.web

import ar.melocoton.auth.application.CreateAssistantTeacher
import ar.melocoton.auth.application.DeactivateAssistantTeacher
import ar.melocoton.auth.application.ListAssistantTeachers
import ar.melocoton.shared.infrastructure.web.ErrorResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** Assistant teacher accounts, managed by the lead teacher only (CU-02 D1, RF-53). The role rule is in SecurityConfig (DI-34). */
@RestController
@RequestMapping("/api/v1/assistant-teachers")
@Tag(name = "Assistant teachers")
@SecurityRequirement(name = "bearerAuth")
class AssistantTeacherController(
    private val listAssistantTeachers: ListAssistantTeachers,
    private val createAssistantTeacher: CreateAssistantTeacher,
    private val deactivateAssistantTeacher: DeactivateAssistantTeacher,
) {

    @GetMapping
    @Operation(summary = "List assistant teachers, ordered by name; optionally only active or inactive (CU-02 D1)")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The assistant teachers"),
        ApiResponse(
            responseCode = "400",
            description = "validation_error: active is not true or false",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "role_not_allowed: only the lead teacher",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun list(@RequestParam(required = false) active: Boolean?): List<AssistantTeacherResponse> =
        listAssistantTeachers.list(active).map { it.toAssistantTeacherResponse() }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Invite an assistant teacher; they get an email to define their password (CU-02 D1, RF-53)",
        description = "Name, email and phone are required. The email must not belong to any other account.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Account created and invitation sent"),
        ApiResponse(
            responseCode = "400",
            description = "validation_error",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "role_not_allowed: only the lead teacher",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "email_already_used",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun create(@Valid @RequestBody request: CreateAssistantTeacherRequest): AssistantTeacherResponse =
        createAssistantTeacher.create(request.name, request.email, request.phone).toAssistantTeacherResponse()

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate an assistant teacher; their sessions stop working (CU-02 D1a, RF-53)")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The account, now inactive"),
        ApiResponse(
            responseCode = "403",
            description = "role_not_allowed: only the lead teacher",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "not_found: the id is not an assistant teacher",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "status_conflict: the account is already inactive",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun deactivate(@PathVariable id: Long): AssistantTeacherResponse =
        deactivateAssistantTeacher.deactivate(id).toAssistantTeacherResponse()
}
