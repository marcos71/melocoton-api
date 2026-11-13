package ar.melocoton.shared.infrastructure.web

import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED

/**
 * Body of every error response: `{ "error": { "code", "message" } }` (`02`, 7.1; DI-20).
 * The fields are marked as required by hand because this schema is added to the contract
 * directly, without the Kotlin nullability support springdoc applies to endpoint DTOs.
 */
data class ErrorResponse(@field:Schema(requiredMode = REQUIRED) val error: ErrorBody) {

    data class ErrorBody(
        @field:Schema(requiredMode = REQUIRED, description = "Stable identifier in English, for the client")
        val code: String,
        @field:Schema(requiredMode = REQUIRED, description = "Text for the person, in Spanish")
        val message: String,
    )

    companion object {
        fun of(code: String, message: String) = ErrorResponse(ErrorBody(code, message))
    }
}
