package ar.melocoton.bookings.infrastructure.web

import ar.melocoton.bookings.application.ListLessons
import ar.melocoton.shared.infrastructure.web.ErrorResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/** Lessons with their free capacity, for the lead teacher's booking screens (DI-56). Secured in SecurityConfig. */
@RestController
@RequestMapping("/api/v1/lessons")
@Tag(name = "Bookings")
@SecurityRequirement(name = "bearerAuth")
class LessonController(private val listLessons: ListLessons) {

    @GetMapping
    @Operation(
        operationId = "listLessons",
        summary = "Lessons between two dates with their free capacity (DI-56, CU-04)",
        description = "One lesson per date in the range that falls on the weekday of each active slot (or of " +
            "slotId only; an inactive slot has none), sorted by date and start time. The range is inclusive " +
            "and at most 3 months long.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The lessons"),
        ApiResponse(
            responseCode = "400",
            description = "validation_error: from or to is missing or malformed. invalid_date_range: from is " +
                "after to, or to is more than 3 months after from",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "role_not_allowed: only the lead teacher",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "not_found: slotId is not a slot",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun list(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
        @RequestParam(required = false) slotId: Long?,
    ): List<LessonResponse> = listLessons.list(from, to, slotId).map { it.toResponse() }
}
