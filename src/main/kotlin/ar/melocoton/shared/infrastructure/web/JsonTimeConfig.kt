package ar.melocoton.shared.infrastructure.web

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.module.SimpleModule
import tools.jackson.databind.ser.std.StdSerializer
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Times of day travel as `HH:mm` (a slot starts at "18:00"), never with seconds, in every response
 * and error detail. Spring Boot registers any Jackson module declared as a bean.
 */
@Configuration
class JsonTimeConfig {

    @Bean
    fun localTimeAsHoursAndMinutes(): SimpleModule = SimpleModule("LocalTimeAsHoursAndMinutes")
        .addSerializer(LocalTime::class.java, HoursAndMinutesSerializer())
}

private class HoursAndMinutesSerializer : StdSerializer<LocalTime>(LocalTime::class.java) {
    private val format = DateTimeFormatter.ofPattern("HH:mm")

    override fun serialize(value: LocalTime, gen: JsonGenerator, ctxt: SerializationContext) {
        gen.writeString(format.format(value))
    }
}
