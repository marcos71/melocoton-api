package ar.melocoton.shared.infrastructure.web

import ar.melocoton.shared.application.port.DatabaseProbe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
class HealthEndpointTest(@Autowired private val mvc: MockMvc) {

    @Test
    fun `GET health returns 200 with everything up against the real database`() {
        mvc.get("/api/v1/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
            jsonPath("$.database") { value("UP") }
        }
    }
}

@SpringBootTest
@AutoConfigureMockMvc
class HealthEndpointDatabaseDownTest(@Autowired private val mvc: MockMvc) {

    @TestConfiguration
    class Config {
        @Bean
        @Primary
        fun unreachableDatabase() = DatabaseProbe { false }
    }

    @Test
    fun `GET health returns 503 when the database does not answer`() {
        mvc.get("/api/v1/health").andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.status") { value("DOWN") }
            jsonPath("$.database") { value("DOWN") }
        }
    }
}
