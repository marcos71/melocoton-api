package ar.melocoton.shared.infrastructure.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.options

@SpringBootTest
@AutoConfigureMockMvc
class CorsConfigTest(@Autowired private val mvc: MockMvc) {

    private val pwa = "http://localhost:5173"
    private val other = "https://evil.example.com"

    @Test
    fun `the PWA origin passes the preflight`() {
        mvc.options("/api/v1/health") {
            header(HttpHeaders.ORIGIN, pwa)
            header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
            header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type")
        }.andExpect {
            status { isOk() }
            header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, pwa) }
        }
    }

    @Test
    fun `a request from the PWA origin gets the allow-origin header`() {
        mvc.get("/api/v1/health") { header(HttpHeaders.ORIGIN, pwa) }.andExpect {
            status { isOk() }
            header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, pwa) }
        }
    }

    @Test
    fun `any other origin is rejected in the preflight`() {
        mvc.options("/api/v1/health") {
            header(HttpHeaders.ORIGIN, other)
            header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
        }.andExpect {
            status { isForbidden() }
            header { doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN) }
        }
    }

    @Test
    fun `any other origin is rejected in a real request`() {
        mvc.get("/api/v1/health") { header(HttpHeaders.ORIGIN, other) }.andExpect {
            status { isForbidden() }
            header { doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN) }
        }
    }
}
