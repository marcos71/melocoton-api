package ar.melocoton.pieces.infrastructure.web

import ar.melocoton.auth.infrastructure.security.JwtSessionTokens
import ar.melocoton.bookings.FixedClockTestConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

/**
 * The student's own piece routes, end to end against the test database (CU-12, CU-13; RF-24, RF-27, RF-31;
 * DI-26, DI-74, DI-75; CP-22). Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FixedClockTestConfig::class)
class MyPieceEndpointTest(
    @Autowired mvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired json: ObjectMapper,
    @Autowired tokens: JwtSessionTokens,
) {
    private val api = PieceApi(mvc, jdbc, json, tokens)
    private val fixtures = api.fixtures

    @Test
    fun `RF24_aStudentCreatesAPieceThatIsTheirs`() {
        val student = api.student()

        val created = api.post(student, "/students/me/pieces", mapOf("name" to " Bowl ", "notes" to "   "))

        created.andExpect {
            status { isCreated() }
            jsonPath("$.name") { value("Bowl") }
            jsonPath("$.notes") { doesNotExist() }
            jsonPath("$.status") { value("IN_PROGRESS") }
            jsonPath("$.editable") { value(true) }
            jsonPath("$.history.length()") { value(1) }
            jsonPath("$.history[0].eventType") { value("CREATED") }
            jsonPath("$.history[0].changedBy") { doesNotExist() }
            jsonPath("$.history[0].operationId") { doesNotExist() }
        }
        val id = api.idOf(created)
        assertThat(jdbc.queryForObject("SELECT student_id FROM piece WHERE id = ?", Long::class.java, id)).isEqualTo(student.userId)
        assertThat(jdbc.queryForObject("SELECT user_id FROM piece_status_history WHERE piece_id = ?", Long::class.java, id)).isEqualTo(student.userId)
    }

    @Test
    fun `RF24_aBlankNameIsAValidationError`() {
        api.post(api.student(), "/students/me/pieces", mapOf("name" to "  ")).isError(400, "validation_error")
    }

    @Test
    fun `DI26_aRepeatedActiveNameIsRejected`() {
        val student = api.student()
        api.post(student, "/students/me/pieces", mapOf("name" to "Bowl")).andExpect { status { isCreated() } }

        api.post(student, "/students/me/pieces", mapOf("name" to "bowl")).isError(409, "duplicate_piece_name")
    }

    @Test
    fun `RF27_aStudentEditsNameAndNotesWhileInProgress`() {
        val student = api.student()
        val piece = fixtures.piece(student.userId, name = "Taza")

        api.patch(student, "/students/me/pieces/$piece", mapOf("name" to " Jarra ", "notes" to "con tapa")).andExpect {
            status { isOk() }
            jsonPath("$.name") { value("Jarra") }
            jsonPath("$.notes") { value("con tapa") }
        }
        assertThat(api.eventCount(piece)).isZero()
    }

    @Test
    fun `RF27_aStudentCannotEditAPieceThatIsNotInProgress`() {
        val student = api.student()
        val piece = fixtures.piece(student.userId, status = "BISQUE_FIRED", name = "Taza")

        api.patch(student, "/students/me/pieces/$piece", mapOf("name" to "Jarra")).isError(409, "piece_not_editable")
        api.get(student, "/students/me/pieces/$piece").andExpect { jsonPath("$.editable") { value(false) } }
    }

    @Test
    fun `RF27_aStudentCannotSendAStatusInTheEdit`() {
        val student = api.student()
        val piece = fixtures.piece(student.userId)

        api.patch(student, "/students/me/pieces/$piece", """{"name":"Jarra","status":"FINISHED"}""").isError(400, "validation_error")
        assertThat(api.statusOf(piece)).isEqualTo("IN_PROGRESS")
    }

    @Test
    fun `RF31_aStudentSeesOnlyTheirOwnNonVoidedPieces`() {
        val student = api.student()
        val own = fixtures.piece(student.userId, withCreationEvent = true)
        val voided = fixtures.piece(student.userId)
        jdbc.update("UPDATE piece SET voided_at = now(), voided_by = ?, void_reason = 'Rota' WHERE id = ?", student.userId, voided)
        fixtures.piece(fixtures.monthlyStudent())

        val result = api.get(student, "/students/me/pieces")

        result.andExpect {
            status { isOk() }
            jsonPath("$.items[0].status") { value("IN_PROGRESS") }
        }
        assertThat(api.idsOf(result)).containsExactly(own)
    }

    @Test
    fun `DI87_theStudentsOwnListIsPagedToo`() {
        val student = api.student()
        repeat(3) { fixtures.piece(student.userId) }

        val first = api.get(student, "/students/me/pieces?limit=2")
        val cursor = checkNotNull(api.nextCursorOf(first))
        val second = api.get(student, "/students/me/pieces?limit=2&cursor=$cursor")

        assertThat(api.idsOf(first)).hasSize(2)
        assertThat(api.idsOf(second)).hasSize(1)
        assertThat(api.nextCursorOf(second)).isNull()
        api.get(student, "/students/me/pieces?cursor=garbage!").isError(400, "validation_error")
        api.get(student, "/students/me/pieces?limit=0").isError(400, "validation_error")
    }

    @Test
    fun `CP22_aCursorNeverWidensTheStudentsOwnList`() {
        val student = api.student()
        val other = fixtures.monthlyStudent()
        val own = fixtures.piece(student.userId)
        repeat(3) { fixtures.piece(other) }
        val teacherPage = api.get(api.lead(), "/pieces?limit=1")
        val cursor = checkNotNull(api.nextCursorOf(teacherPage))

        val result = api.get(student, "/students/me/pieces?cursor=$cursor")

        assertThat(api.idsOf(result)).containsOnly(own)
    }

    @Test
    fun `DI74_aVoidedPieceIs404ForItsOwner`() {
        val student = api.student()
        val piece = fixtures.piece(student.userId)
        jdbc.update("UPDATE piece SET voided_at = now(), voided_by = ?, void_reason = 'Rota' WHERE id = ?", student.userId, piece)

        api.get(student, "/students/me/pieces/$piece").isError(404, "not_found")
        api.patch(student, "/students/me/pieces/$piece", mapOf("name" to "Otro")).isError(404, "not_found")
    }

    @Test
    fun `CP22_studentACannotReadOrEditStudentBsPieceThroughTheirOwnRoutes`() {
        val studentA = api.student()
        val studentB = api.student()
        val pieceOfB = fixtures.piece(studentB.userId, name = "De B")

        api.get(studentA, "/students/me/pieces/$pieceOfB").isError(404, "not_found")
        api.patch(studentA, "/students/me/pieces/$pieceOfB", mapOf("name" to "Robada")).isError(404, "not_found")

        assertThat(jdbc.queryForObject("SELECT name FROM piece WHERE id = ?", String::class.java, pieceOfB)).isEqualTo("De B")
    }
}
