package ar.melocoton.slots.infrastructure.web

import ar.melocoton.shared.infrastructure.web.ErrorResponse
import ar.melocoton.slots.application.CreateSlot
import ar.melocoton.slots.application.GetSlot
import ar.melocoton.slots.application.ListSlots
import ar.melocoton.slots.application.UpdateSlot
import ar.melocoton.slots.domain.SlotChange
import ar.melocoton.slots.domain.SlotStatusFilter
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
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

/** Slots, managed by the lead teacher only (CU-03, RF-06, RF-07). The role rule is in SecurityConfig (DI-34). */
@RestController
@RequestMapping("/api/v1/slots")
@Tag(name = "Slots")
@SecurityRequirement(name = "bearerAuth")
class SlotController(
    private val createSlot: CreateSlot,
    private val listSlots: ListSlots,
    private val getSlot: GetSlot,
    private val updateSlot: UpdateSlot,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Create a slot (CU-03, RF-06)",
        description = "A slot may not overlap another active slot of the same day; touching ends are fine (DI-25).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "The new slot"),
        ApiResponse(
            responseCode = "400",
            description = "validation_error: a field is missing or out of range, or the start is not before the end",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "role_not_allowed: only the lead teacher",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "slot_overlap: details is SlotOverlapDetails",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun create(@RequestBody request: CreateSlotRequest): SlotResponse =
        createSlot.create(request.dayOfWeek, request.startTime, request.endTime, request.capacity).toResponse()

    @GetMapping
    @Operation(
        summary = "List slots ordered by day and start time (CU-03, RF-07)",
        description = "Each slot carries its future lessons with more occupied places than the capacity (DI-43).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The slots"),
        ApiResponse(
            responseCode = "400",
            description = "validation_error: status is not ACTIVE, INACTIVE or ALL",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "role_not_allowed: only the lead teacher",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun list(@RequestParam(defaultValue = "ACTIVE") status: SlotStatusFilter): List<SlotItemResponse> =
        listSlots.list(status).map { it.toResponse() }

    @GetMapping("/{id}")
    @Operation(summary = "One slot (CU-03, RF-07)")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The slot"),
        ApiResponse(
            responseCode = "403",
            description = "role_not_allowed: only the lead teacher",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "not_found",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun get(@PathVariable id: Long): SlotItemResponse = getSlot.get(id).toResponse()

    @PatchMapping("/{id}")
    @Operation(
        summary = "Edit a slot (CU-03, RF-06, DI-45)",
        description = "At least one field. The capacity cannot go below the occupancy of any future lesson. " +
            "Day and time can change only while the slot has no bookings, and may not overlap another active slot.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The slot after the change"),
        ApiResponse(
            responseCode = "400",
            description = "validation_error: nothing to change, a field out of range, or the start is not before the end",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "role_not_allowed: only the lead teacher",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "not_found",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "status_conflict: the slot is inactive. slot_has_bookings: day or time cannot change. " +
                "slot_overlap: details is SlotOverlapDetails. " +
                "capacity_below_occupancy: details is CapacityBelowOccupancyDetails",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun update(@PathVariable id: Long, @RequestBody request: UpdateSlotRequest): SlotItemResponse =
        updateSlot.update(
            id,
            SlotChange(request.dayOfWeek, request.startTime, request.endTime, request.capacity),
        ).toResponse()
}
