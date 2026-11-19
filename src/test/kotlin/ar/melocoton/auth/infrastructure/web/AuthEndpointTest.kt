package ar.melocoton.auth.infrastructure.web

import ar.melocoton.auth.infrastructure.security.JwtSessionTokens
import ar.melocoton.shared.domain.Role
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Logging in, using and renewing a session, end to end against the real test database
 * (CU-01; RF-01, RF-02, RF-45; DI-28, DI-30, DI-31). Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthEndpointTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val json: ObjectMapper,
    @Autowired private val tokens: JwtSessionTokens,
    @Autowired private val entityManager: EntityManager,
) {
    private val password = PASSWORD
    private val invalidCredentials = "Email o contraseña incorrectos. Si dejaste el taller, consultá con la docente."

    private fun user(role: Role = Role.STUDENT, active: Boolean = true): Pair<Long, String> {
        val email = "${UUID.randomUUID()}@test.local"
        val id = jdbc.queryForObject(
            """INSERT INTO app_user (name, email, password_hash, role, phone, active)
               VALUES ('Ana', ?, ?, ?, '1100000000', ?) RETURNING id""",
            Long::class.java, email, PASSWORD_HASH, role.name, active,
        )!!
        return id to email
    }

    private fun login(email: String, password: String = this.password) = mvc.post("/api/v1/auth/login") {
        contentType = MediaType.APPLICATION_JSON
        content = json.writeValueAsString(mapOf("email" to email, "password" to password))
    }

    private fun tokenOf(email: String): String {
        val body = login(email).andReturn().response.contentAsString
        return json.readTree(body)["accessToken"].asString()
    }

    /**
     * Changes the user with SQL, as another request would. The whole test is one transaction, so
     * JPA's cache is cleared to read the change; in the real API each request has its own.
     */
    private fun changeUser(sql: String, id: Long) {
        jdbc.update(sql, id)
        entityManager.clear()
    }

    private fun sessionsValidFrom(id: Long): Instant =
        jdbc.queryForObject("SELECT sessions_valid_from FROM app_user WHERE id = ?", Instant::class.java, id)!!

    private fun session(token: String) =
        mvc.get("/api/v1/auth/session") { header(HttpHeaders.AUTHORIZATION, "Bearer $token") }

    private fun ResultActionsDsl.isNotAuthenticated() = andExpect {
        status { isUnauthorized() }
        jsonPath("$.error.code") { value("not_authenticated") }
        jsonPath("$.error.message") { value("Tenés que iniciar sesión.") }
    }

    private fun ResultActionsDsl.isInvalidCredentials() = andExpect {
        status { isUnauthorized() }
        jsonPath("$.error.code") { value("invalid_credentials") }
        jsonPath("$.error.message") { value(invalidCredentials) }
    }

    @Test
    fun `RF-01 logging in returns a token that expires in 30 days and the user`() {
        val (id, email) = user(Role.LEAD_TEACHER)

        val body = login(email).andExpect {
            status { isOk() }
            jsonPath("$.user.id") { value(id) }
            jsonPath("$.user.name") { value("Ana") }
            jsonPath("$.user.role") { value("LEAD_TEACHER") }
        }.andReturn().response.contentAsString

        val expiresAt = Instant.parse(json.readTree(body)["expiresAt"].asString())
        assertThat(Duration.between(Instant.now(), expiresAt))
            .isBetween(Duration.ofDays(30).minusMinutes(1), Duration.ofDays(30))
    }

    @Test
    fun `RF-02 the token gives access to the session, with the role`() {
        val (id, email) = user(Role.ASSISTANT_TEACHER)

        session(tokenOf(email)).andExpect {
            status { isOk() }
            jsonPath("$.user.id") { value(id) }
            jsonPath("$.user.role") { value("ASSISTANT_TEACHER") }
        }
    }

    @Test
    fun `the email is not case sensitive`() {
        val (_, email) = user()

        login(email.uppercase()).andExpect { status { isOk() } }
    }

    @Test
    fun `DI-30 a wrong password and an unknown email get the same answer`() {
        val (_, email) = user()

        login(email, "otra-contraseña").isInvalidCredentials()
        login("nadie@test.local").isInvalidCredentials()
    }

    @Test
    fun `RF-45 DI-30 an inactive user cannot log in, even with the right password`() {
        val (_, email) = user(active = false)

        login(email).isInvalidCredentials()
    }

    @Test
    fun `DI-31 five wrong passwords lock the account, and then the right one is rejected too`() {
        val (id, email) = user()

        repeat(5) { login(email, "otra-contraseña").isInvalidCredentials() }
        login(email).isInvalidCredentials()

        val lockedUntil = jdbc.queryForObject("SELECT locked_until FROM app_user WHERE id = ?", Instant::class.java, id)
        assertThat(lockedUntil).isAfter(Instant.now().plus(Duration.ofMinutes(14)))
    }

    @Test
    fun `an empty email or password is a validation error`() {
        login("", "").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("validation_error") }
        }
    }

    @Test
    fun `without a token the API answers 401 in the error shape`() {
        mvc.get("/api/v1/auth/session").isNotAuthenticated()
            .andExpect { header { string(HttpHeaders.WWW_AUTHENTICATE, "Bearer") } }
    }

    @Test
    fun `a token that is not ours is rejected`() {
        session("not-a-token").isNotAuthenticated()
    }

    @Test
    fun `DI-28 a token not refreshed for 30 days is rejected`() {
        val (id, _) = user()
        val old = tokens.issue(id, Role.STUDENT, sessionsValidFrom(id), Instant.now().minus(Duration.ofDays(30)).minusSeconds(1))

        session(old.value).isNotAuthenticated()
    }

    @Test
    fun `RF-45 DI-28 deactivating a user closes the sessions already open`() {
        val (id, email) = user()
        val token = tokenOf(email)

        changeUser("UPDATE app_user SET active = false WHERE id = ?", id)

        session(token).isNotAuthenticated()
    }

    @Test
    fun `DI-28 a password change closes the sessions already open`() {
        val (id, email) = user()
        val token = tokenOf(email)

        changeUser("UPDATE app_user SET sessions_valid_from = now() + interval '5 seconds' WHERE id = ?", id)

        session(token).isNotAuthenticated()
    }

    @Test
    fun `DI-28 refreshing returns a new token that works`() {
        val (_, email) = user()

        val body = mvc.post("/api/v1/auth/refresh") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokenOf(email)}") }
            .andExpect {
                status { isOk() }
                jsonPath("$.expiresAt") { isNotEmpty() }
            }.andReturn().response.contentAsString

        session(json.readTree(body)["accessToken"].asString()).andExpect { status { isOk() } }
    }

    @Test
    fun `DI-28 a token issued under an earlier cut-off is rejected, however recent it is`() {
        val (id, _) = user()
        val earlier = tokens.issue(id, Role.STUDENT, sessionsValidFrom(id).minusMillis(1), Instant.now())

        session(earlier.value).isNotAuthenticated()
    }

    @Test
    fun `refreshing needs a session`() {
        mvc.post("/api/v1/auth/refresh").isNotAuthenticated()
    }

    @Test
    fun `the health check stays public`() {
        mvc.get("/api/v1/health").andExpect { status { isOk() } }
    }

    @Test
    fun `an expired token left in the PWA does not block logging in again`() {
        val (id, email) = user()
        val expired = tokens.issue(id, Role.STUDENT, sessionsValidFrom(id), Instant.now().minus(Duration.ofDays(31)))

        mvc.post("/api/v1/auth/login") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${expired.value}")
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("email" to email, "password" to password))
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `an invalid token on the health check is ignored`() {
        mvc.get("/api/v1/health") { header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token") }
            .andExpect { status { isOk() } }
    }

    companion object {
        private const val PASSWORD = "una-contraseña-larga"

        // Argon2 is slow on purpose: hash once for the class, not once per test.
        private val PASSWORD_HASH = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(PASSWORD)!!
    }
}
