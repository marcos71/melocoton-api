package ar.melocoton.pieces.infrastructure.web

import ar.melocoton.auth.infrastructure.security.JwtSessionTokens
import ar.melocoton.bookings.FixedClockTestConfig
import ar.melocoton.schema.SchemaFixtures
import ar.melocoton.shared.domain.Role
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/** What the piece endpoint tests share: sessions of each role, calls to every route and a few checks on the database. */
class PieceApi(
    private val mvc: MockMvc,
    private val jdbc: JdbcTemplate,
    private val json: ObjectMapper,
    private val tokens: JwtSessionTokens,
) {
    val fixtures = SchemaFixtures(jdbc)

    /** A logged-in person: the account id and its token. */
    class Session(val userId: Long, val token: String)

    private fun sessionOf(userId: Long, role: Role): Session {
        val validFrom = jdbc.queryForObject("SELECT sessions_valid_from FROM app_user WHERE id = ?", Instant::class.java, userId)!!
        return Session(userId, tokens.issue(userId, role, validFrom, FixedClockTestConfig.NOW).value)
    }

    fun student(): Session = sessionOf(fixtures.monthlyStudent(), Role.STUDENT)

    fun lead(): Session = sessionOf(fixtures.user("LEAD_TEACHER"), Role.LEAD_TEACHER)

    fun assistant(): Session = sessionOf(fixtures.user("ASSISTANT_TEACHER"), Role.ASSISTANT_TEACHER)

    fun get(session: Session, path: String) = mvc.get("/api/v1$path") { header(HttpHeaders.AUTHORIZATION, "Bearer ${session.token}") }

    fun post(session: Session, path: String, body: Any? = null) = mvc.post("/api/v1$path") {
        header(HttpHeaders.AUTHORIZATION, "Bearer ${session.token}")
        body?.let {
            contentType = MediaType.APPLICATION_JSON
            content = if (it is String) it else json.writeValueAsString(it)
        }
    }

    fun patch(session: Session, path: String, body: Any) = mvc.patch("/api/v1$path") {
        header(HttpHeaders.AUTHORIZATION, "Bearer ${session.token}")
        contentType = MediaType.APPLICATION_JSON
        content = if (body is String) body else json.writeValueAsString(body)
    }

    fun changeStatus(session: Session, pieceId: Long, expected: String, new: String) =
        post(session, "/pieces/$pieceId/change-status", mapOf("expectedStatus" to expected, "newStatus" to new))

    fun voidPiece(session: Session, pieceId: Long, reason: String = "Se rompió") =
        post(session, "/pieces/$pieceId/void", mapOf("reason" to reason))

    fun idOf(result: ResultActionsDsl): Long = json.readTree(result.andReturn().response.contentAsString)["id"].asLong()

    fun idsOf(result: ResultActionsDsl): List<Long> {
        val items = json.readTree(result.andReturn().response.contentAsString)
        return (0 until items.size()).map { items[it]["id"].asLong() }
    }

    fun statusOf(pieceId: Long): String = jdbc.queryForObject("SELECT status FROM piece WHERE id = ?", String::class.java, pieceId)!!

    fun eventCount(pieceId: Long): Int =
        jdbc.queryForObject("SELECT count(*) FROM piece_status_history WHERE piece_id = ?", Int::class.java, pieceId)!!
}

/** The response is a business error with this HTTP status and `code`. */
fun ResultActionsDsl.isError(status: Int, code: String) = andExpect {
    status { isEqualTo(status) }
    jsonPath("$.error.code") { value(code) }
}
