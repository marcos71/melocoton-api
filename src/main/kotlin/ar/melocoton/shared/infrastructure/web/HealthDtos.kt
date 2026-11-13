package ar.melocoton.shared.infrastructure.web

import ar.melocoton.shared.application.ComponentStatus
import ar.melocoton.shared.application.HealthReport

/** JSON returned by GET /api/v1/health. */
data class HealthResponse(val status: ComponentStatus, val database: ComponentStatus)

fun HealthReport.toResponse() = HealthResponse(status = status, database = database)
