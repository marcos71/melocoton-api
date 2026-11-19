package ar.melocoton.shared.infrastructure.web

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED

/**
 * Body of every error response: `{ "error": { "code", "message", "details"? } }` (`02`, 7.1; DI-20).
 * The fields are marked as required by hand because this schema is added to the contract
 * directly, without the Kotlin nullability support springdoc applies to endpoint DTOs.
 */
data class ErrorResponse(@field:Schema(requiredMode = REQUIRED) val error: ErrorBody) {

    data class ErrorBody(
        @field:Schema(requiredMode = REQUIRED, description = "Stable identifier in English, for the client")
        val code: String,
        @field:Schema(requiredMode = REQUIRED, description = "Text for the person, in Spanish")
        val message: String,
        @field:JsonInclude(JsonInclude.Include.NON_NULL)
        @field:Schema(
            description = "Only for some codes (DI-23): its shape depends on the code; each endpoint names the schema.",
        )
        val details: Any? = null,
    )

    companion object {
        fun of(code: String, message: String, details: Any? = null) =
            ErrorResponse(ErrorBody(code, message, details))
    }
}
