package ar.melocoton.shared.infrastructure.web

import ar.melocoton.shared.application.CheckHealth
import ar.melocoton.shared.application.ComponentStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/health")
@Tag(name = "Health")
class HealthController(private val checkHealth: CheckHealth) {

    @GetMapping
    @Operation(summary = "Whether the API and its database are working")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Everything is up"),
        ApiResponse(responseCode = "503", description = "Something is down; the body says what"),
    )
    fun health(): ResponseEntity<HealthResponse> {
        val report = checkHealth.check()
        val status = if (report.status == ComponentStatus.UP) HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE
        return ResponseEntity.status(status).body(report.toResponse())
    }
}
