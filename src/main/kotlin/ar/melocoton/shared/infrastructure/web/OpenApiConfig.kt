package ar.melocoton.shared.infrastructure.web

import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Metadata of the OpenAPI contract, published in openapi/openapi.json (DI-11).
 * The PWA generates its types from that file.
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        // Endpoints that need a session say so with @SecurityRequirement(name = "bearerAuth").
        .components(
            Components().addSecuritySchemes(
                "bearerAuth",
                SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"),
            ),
        )
        .info(
        Info()
            .title("Melocotón API")
            .version("v1")
            .description(
                "REST API for a ceramics workshop. Business errors use " +
                    "{ \"error\": { \"code\", \"message\" } } with a Spanish message.",
            ),
    )

    /**
     * Every endpoint that needs a session can answer 429 when the user goes over the per-user
     * limit (RNF-25, DI-36). It is not declared on each controller: the filter, not the
     * controller, produces it, so it is added here to the operations with a security requirement.
     */
    @Bean
    fun rateLimitResponseCustomizer() = OpenApiCustomizer { openApi ->
        val response = ApiResponse()
            .description("too_many_requests: more than 60 requests in a minute from the same user")
            .content(
                Content().addMediaType(
                    "application/json",
                    MediaType().schema(Schema<Any>().`$ref`("#/components/schemas/ErrorResponse")),
                ),
            )
        openApi.paths?.values?.flatMap { it.readOperations() }
            ?.filter { !it.security.isNullOrEmpty() }
            ?.forEach { it.responses.addApiResponse("429", response) }
    }

    /**
     * The error shape is part of the contract even though no endpoint returns it directly.
     * Added when the contract is built, so springdoc's Kotlin support marks non-null fields as required.
     */
    @Bean
    fun errorSchemaCustomizer() = OpenApiCustomizer { openApi ->
        val components = openApi.components ?: Components().also { openApi.components = it }
        ModelConverters.getInstance().readAll(ErrorResponse::class.java)
            .forEach { (name, schema) -> components.addSchemas(name, schema) }
    }
}
