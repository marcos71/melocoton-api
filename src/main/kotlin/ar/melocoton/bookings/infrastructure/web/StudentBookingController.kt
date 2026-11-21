package ar.melocoton.bookings.infrastructure.web

import ar.melocoton.bookings.application.AssignFixedSlot
import ar.melocoton.bookings.application.BookDropInClass
import ar.melocoton.bookings.application.CancelDropInClass
import ar.melocoton.shared.infrastructure.web.ErrorResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * A student's fixed slot and drop-in bookings, managed by the lead teacher (RF-08, RF-09, RF-52).
 * The role rule is in SecurityConfig: everything under /students that is not /me is the lead teacher's (DI-34).
 */
@RestController
@RequestMapping("/api/v1/students/{id}")
@Tag(name = "Bookings")
@SecurityRequirement(name = "bearerAuth")
class StudentBookingController(
    private val assignFixedSlot: AssignFixedSlot,
    private val bookDropInClass: BookDropInClass,
    private val cancelDropInClass: CancelDropInClass,
) {

    @PutMapping("/fixed-slot")
    @Operation(
        operationId = "assignFixedSlot",
        summary = "Assign or replace a monthly student's fixed slot (RF-08, DI-27, DI-42, DI-43)",
        description = "The slot takes at most as many fixed students as its capacity (DI-66). Generates the student's " +
            "fixed bookings from today to the end of the rolling window, even on dates that drop-ins filled: the response lists the future lessons of the slot that end up " +
            "over capacity. If the student had another slot, its scheduled fixed bookings of lessons that " +
            "have not started are cancelled (ENROLLMENT_CHANGE). Assigning the same slot again only fills the missing dates.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The assigned slot and its overbooked future lessons"),
        ApiResponse(
            responseCode = "400",
            description = "validation_error: slotId is missing or malformed",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "role_not_allowed: only the lead teacher",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "not_found: the id is not a student, or the slot does not exist",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "enrollment_type_mismatch: the student is not monthly. student_inactive. slot_inactive. " +
                "fixed_students_full: the slot already has as many fixed students as its capacity; " +
                "details is FixedStudentsFullDetails (DI-66)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun assignFixedSlot(@PathVariable id: Long, @RequestBody request: AssignFixedSlotRequest): FixedSlotAssignmentResponse =
        assignFixedSlot.assign(id, request.slotId).toResponse()

    @PostMapping("/drop-in-bookings")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        operationId = "bookDropInClass",
        summary = "Book a drop-in class for a drop-in student (RF-09, RF-11, DI-44)",
        description = "The date must fall on the slot's weekday, be at most 3 months ahead, and the lesson " +
            "must not have started. A drop-in booking never exceeds the slot's capacity.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "The scheduled drop-in booking"),
        ApiResponse(
            responseCode = "400",
            description = "validation_error: a field is missing or malformed. invalid_lesson_date: the date is not on " +
                "the slot's weekday or is more than 3 months ahead",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "role_not_allowed: only the lead teacher",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "not_found: the id is not a student, or the slot does not exist",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "enrollment_type_mismatch: the student is not drop-in. student_inactive. slot_inactive. " +
                "lesson_already_started. already_booked: the student has an active booking in that lesson. " +
                "capacity_full: no place left",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun bookDropInClass(@PathVariable id: Long, @RequestBody request: BookDropInClassRequest): BookingResponse =
        bookDropInClass.book(id, request.slotId, request.lessonDate).toResponse()

    @PostMapping("/drop-in-bookings/{bookingId}/cancel")
    @Operation(
        operationId = "cancelDropInClass",
        summary = "Cancel a student's drop-in class (RF-52, DI-44)",
        description = "Only a scheduled drop-in booking of that student, before the lesson starts. It frees the place.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The cancelled booking"),
        ApiResponse(
            responseCode = "403",
            description = "role_not_allowed: only the lead teacher",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "not_found: the id is not a student, or the booking does not exist, is another " +
                "student's or is not a drop-in",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "status_conflict: the booking is not scheduled. lesson_already_started",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun cancelDropInClass(@PathVariable id: Long, @PathVariable bookingId: Long): BookingResponse =
        cancelDropInClass.cancel(id, bookingId).toResponse()
}
