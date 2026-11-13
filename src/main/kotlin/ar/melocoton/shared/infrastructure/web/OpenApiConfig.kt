package ar.melocoton.shared.infrastructure.web

import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
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
    fun openApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("Melocotón API")
            .version("v1")
            .description(
                "REST API for a ceramics workshop. Business errors use " +
                    "{ \"error\": { \"code\", \"message\" } } with a Spanish message.",
            ),
    )

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
