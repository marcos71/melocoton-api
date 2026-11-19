package ar.melocoton.slots.infrastructure.web

import ar.melocoton.slots.domain.CapacityBelowOccupancyDetails
import ar.melocoton.slots.domain.SlotOverlapDetails
import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.oas.models.Components
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The `details` of the slot errors (DI-23) are plain domain classes that no endpoint returns
 * directly, so they are added to the contract here for the PWA to get their types. The domain
 * cannot carry OpenAPI annotations, so every field of these classes (all non-null) is marked required here.
 */
@Configuration
class SlotOpenApiConfig {

    @Bean
    fun slotErrorDetailsCustomizer() = OpenApiCustomizer { openApi ->
        val components = openApi.components ?: Components().also { openApi.components = it }
        listOf(SlotOverlapDetails::class.java, CapacityBelowOccupancyDetails::class.java)
            .flatMap { ModelConverters.getInstance().readAll(it).entries }
            .forEach { (name, schema) ->
                schema.required = schema.properties?.keys?.toList()
                components.addSchemas(name, schema)
            }
    }
}
