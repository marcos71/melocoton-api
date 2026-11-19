package ar.melocoton.shared.infrastructure.web

import ar.melocoton.auth.infrastructure.security.JwtSessionTokens
import ar.melocoton.shared.domain.Role
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** A clock the test can move, to cross the rate limit window without waiting. */
class MovableClock(private var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
    fun advance(by: Duration) {
        now = now.plus(by)
    }
}

/**
 * Request limits of RNF-25 and DI-31 (DI-36), with the real values (30 login attempts per IP,
 * 60 requests per user, per minute) and a clock the test controls. Other tests run with the
 * limits practically off (application-test.yml); this class has its own context with the real
 * ones. The limiter keeps state for the whole context, so each test uses its own IPs and users.
 */
@SpringBootTest(properties = ["melocoton.rate-limit.login-per-ip=30", "melocoton.rate-limit.per-user=60"])
@AutoConfigureMockMvc
@Transactional
class RateLimitEndpointTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val tokens: JwtSessionTokens,
    @Autowired private val clock: MovableClock,
) {
    @TestConfiguration
    class Config {
        @Bean
        @Primary
        fun movableClock() = MovableClock(Instant.now())
    }

    private val tooMany = "Hiciste demasiados pedidos seguidos. Esperá un minuto y probá de nuevo."

    private fun loginFrom(address: String, body: String = """{"email":"nadie@test.local","password":"x"}""") =
        mvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = body
            with { it.remoteAddr = address; it }
        }

    private fun ResultActionsDsl.isTooManyRequests() = andExpect {
        status { isTooManyRequests() }
        jsonPath("$.error.code") { value("too_many_requests") }
        jsonPath("$.error.message") { value(tooMany) }
        jsonPath("$.error.details") { doesNotExist() }
        header { doesNotExist(HttpHeaders.RETRY_AFTER) }
    }

    private fun newUserToken(): String {
        val id = jdbc.queryForObject(
            """INSERT INTO app_user (name, email, password_hash, role, phone, active)
               VALUES ('Ana', ?, 'x', 'STUDENT', '1100000000', true) RETURNING id""",
            Long::class.java, "${UUID.randomUUID()}@test.local",
        )!!
        val validFrom = jdbc.queryForObject(
            "SELECT sessions_valid_from FROM app_user WHERE id = ?", Instant::class.java, id,
        )!!
        return tokens.issue(id, Role.STUDENT, validFrom, clock.instant()).value
    }

    private fun session(token: String) =
        mvc.get("/api/v1/auth/session") { header(HttpHeaders.AUTHORIZATION, "Bearer $token") }

    @Test
    fun `DI31 the 31st login attempt in a minute from one IP gets 429`() {
        val address = "10.1.0.1"
        repeat(30) { loginFrom(address).andExpect { status { isUnauthorized() } } }

        loginFrom(address).isTooManyRequests()
    }

    @Test
    fun `DI31 another IP is not affected`() {
        repeat(31) { loginFrom("10.1.0.2") }

        loginFrom("10.1.0.3").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `DI31 every attempt counts, even one with an invalid body`() {
        val address = "10.1.0.4"
        repeat(30) { loginFrom(address, "{}").andExpect { status { isBadRequest() } } }

        loginFrom(address).isTooManyRequests()
    }

    @Test
    fun `DI31 attempts are accepted again once the window passes`() {
        val address = "10.1.0.5"
        repeat(31) { loginFrom(address) }

        clock.advance(Duration.ofMinutes(1))

        loginFrom(address).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `DI36 rejected login attempts do not extend the block`() {
        val address = "10.1.0.6"
        repeat(30) { loginFrom(address) }
        clock.advance(Duration.ofSeconds(30))
        repeat(10) { loginFrom(address).isTooManyRequests() }

        clock.advance(Duration.ofSeconds(30))

        loginFrom(address).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `DI31 the login limit does not count other endpoints`() {
        val address = "10.1.0.7"
        repeat(40) { mvc.get("/api/v1/health") { with { it.remoteAddr = address; it } } }

        loginFrom(address).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `RNF25 the 61st authenticated request in a minute from one user gets 429`() {
        val token = newUserToken()
        repeat(60) { session(token).andExpect { status { isOk() } } }

        session(token).isTooManyRequests()
    }

    @Test
    fun `RNF25 another user is not affected`() {
        val first = newUserToken()
        val second = newUserToken()
        repeat(61) { session(first) }

        session(second).andExpect { status { isOk() } }
    }

    @Test
    fun `RNF25 requests are accepted again once the window passes`() {
        val token = newUserToken()
        repeat(61) { session(token) }

        clock.advance(Duration.ofMinutes(1))

        session(token).andExpect { status { isOk() } }
    }

    @Test
    fun `RNF25 public endpoints are not counted per user`() {
        val token = newUserToken()
        repeat(100) { mvc.get("/api/v1/health").andExpect { status { isOk() } } }
        repeat(100) { mvc.get("/api/v1/health") { header(HttpHeaders.AUTHORIZATION, "Bearer $token") } }

        session(token).andExpect { status { isOk() } }
    }

    @Test
    fun `RNF25 requests with an invalid token are not counted per user and stay 401`() {
        repeat(100) { session("not-a-token").andExpect { status { isUnauthorized() } } }
    }
}
