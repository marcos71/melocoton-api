package ar.melocoton.students.infrastructure.web

import ar.melocoton.shared.infrastructure.web.ErrorResponse
import ar.melocoton.students.application.CreateStudent
import ar.melocoton.students.application.GetStudent
import ar.melocoton.students.application.ListStudents
import ar.melocoton.students.application.UpdateStudent
import ar.melocoton.students.domain.EnrollmentType
import ar.melocoton.students.domain.StudentChange
import ar.melocoton.students.domain.StudentFilter
import ar.melocoton.students.domain.StudentStatusFilter
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
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** Students, managed by the lead teacher only (CU-02, RF-04, RF-05, DI-46). The role rule is in SecurityConfig (DI-34). */
@RestController
@RequestMapping("/api/v1/students")
@Tag(name = "Students")
@SecurityRequirement(name = "bearerAuth")
class StudentController(
    private val createStudent: CreateStudent,
    private val listStudents: ListStudents,
    private val getStudent: GetStudent,
    private val updateStudent: UpdateStudent,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        operationId = "createStudent",
        summary = "Register a student; they get an email to define their password (CU-02, RF-04, RF-05, DI-46)",
        description = "Name, email, phone and enrollment type are required. A MONTHLY student starts on " +
            "monthlyStartDate, or today when it is not sent; a DROP_IN student must not send it. " +
            "The email must not belong to any other account.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "The student, created and invited"),
        ApiResponse(
            responseCode = "400",
            description = "validation_error: a field is missing or blank, the email is malformed, or a DROP_IN student sends a start date",
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
    fun create(@Valid @RequestBody request: CreateStudentRequest): StudentProfileResponse =
        createStudent.create(
            request.name, request.email, request.phone, request.enrollmentType, request.monthlyStartDate,
        ).toResponse()

    @GetMapping
    @Operation(
        operationId = "listStudents",
        summary = "List students ordered by name (CU-02, RF-05)",
        description = "status defaults to ACTIVE. enrollmentType and slotId (the fixed slot) narrow the list when sent.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The students"),
        ApiResponse(
            responseCode = "400",
            description = "validation_error: a filter has an invalid value",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "role_not_allowed: only the lead teacher",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun list(
        @RequestParam(defaultValue = "ACTIVE") status: StudentStatusFilter,
        @RequestParam(required = false) enrollmentType: EnrollmentType?,
        @RequestParam(required = false) slotId: Long?,
    ): List<StudentItemResponse> =
        listStudents.list(StudentFilter(status, enrollmentType, slotId)).map { it.toResponse() }

    @GetMapping("/{id}")
    @Operation(operationId = "getStudent", summary = "A student's profile (CU-02, DI-46)")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The student"),
        ApiResponse(
            responseCode = "403",
            description = "role_not_allowed: only the lead teacher",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "not_found: the id is not a student",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun get(@PathVariable id: Long): StudentProfileResponse = getStudent.get(id).toResponse()

    @PatchMapping("/{id}")
    @Operation(
        operationId = "updateStudent",
        summary = "Edit a student's name and phone (CU-02, DI-46)",
        description = "At least one of name and phone. The email, the modality and the start date cannot be edited here.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The student, updated"),
        ApiResponse(
            responseCode = "400",
            description = "validation_error: nothing to change, a blank value, or a field that cannot be edited",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "role_not_allowed: only the lead teacher",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "not_found: the id is not a student",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun update(@PathVariable id: Long, @RequestBody request: UpdateStudentRequest): StudentProfileResponse =
        updateStudent.update(id, StudentChange(request.name, request.phone)).toResponse()
}
