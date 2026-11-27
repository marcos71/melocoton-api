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
 * The teachers' piece routes, end to end against the test database (CU-12, CU-13; RF-24 to RF-28, RF-31, RF-32,
 * RF-56, RF-61, RF-62; DI-26, DI-70, DI-74, DI-75). Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FixedClockTestConfig::class)
class PieceEndpointTest(
    @Autowired mvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired json: ObjectMapper,
    @Autowired tokens: JwtSessionTokens,
) {
    private val api = PieceApi(mvc, jdbc, json, tokens)
    private val fixtures = api.fixtures

    private fun create(session: PieceApi.Session, studentId: Long, name: String, notes: String? = null) =
        api.post(session, "/pieces", mapOf("studentId" to studentId, "name" to name, "notes" to notes))

    // ---- creating ----

    @Test
    fun `RF24_aLeadTeacherCreatesAPieceForAStudent`() {
        val lead = api.lead()
        val student = fixtures.monthlyStudent()

        val created = create(lead, student, "  Bowl  ", "esmalte verde")

        created.andExpect {
            status { isCreated() }
            jsonPath("$.name") { value("Bowl") }
            jsonPath("$.notes") { value("esmalte verde") }
            jsonPath("$.status") { value("IN_PROGRESS") }
            jsonPath("$.advanceTo") { value("BISQUE_FIRED") }
            jsonPath("$.student.id") { value(student) }
            jsonPath("$.history.length()") { value(1) }
            jsonPath("$.history[0].eventType") { value("CREATED") }
            jsonPath("$.history[0].changedBy.id") { value(lead.userId) }
        }
        assertThat(api.eventCount(api.idOf(created))).isEqualTo(1)
    }

    @Test
    fun `RF24_anAssistantTeacherCreatesAPieceForAStudent`() {
        create(api.assistant(), fixtures.monthlyStudent(), "Bowl").andExpect { status { isCreated() } }
    }

    @Test
    fun `RF24_aPieceForAnInactiveStudentIsRejected`() {
        val student = fixtures.monthlyStudent()
        jdbc.update("UPDATE app_user SET active = false WHERE id = ?", student)

        create(api.lead(), student, "Bowl").isError(409, "student_inactive")
    }

    @Test
    fun `RF24_aPieceForAnIdThatIsNotAStudentIsNotFound`() {
        create(api.lead(), fixtures.user("LEAD_TEACHER"), "Bowl").isError(404, "not_found")
    }

    @Test
    fun `RF24_aBlankNameIsAValidationError`() {
        create(api.lead(), fixtures.monthlyStudent(), "   ").isError(400, "validation_error")
    }

    @Test
    fun `DI26_aRepeatedActiveNameIsRejected`() {
        val lead = api.lead()
        val student = fixtures.monthlyStudent()
        create(lead, student, "Bowl").andExpect { status { isCreated() } }

        create(lead, student, "  bOWL ").isError(409, "duplicate_piece_name")
    }

    @Test
    fun `DI26_theNameOfAVoidedPieceCanBeReused`() {
        val lead = api.lead()
        val student = fixtures.monthlyStudent()
        val first = api.idOf(create(lead, student, "Bowl"))
        api.voidPiece(lead, first).andExpect { status { isNoContent() } }

        create(lead, student, "Bowl").andExpect { status { isCreated() } }
    }

    @Test
    fun `DI73_theNameOfAFinishedPieceCanBeReused`() {
        val student = fixtures.monthlyStudent()
        fixtures.piece(student, status = "FINISHED", name = "Bowl")

        create(api.lead(), student, "Bowl").andExpect { status { isCreated() } }
    }

    // ---- changing the status ----

    @Test
    fun `RF26_aTeacherAdvancesAPieceAndTheResponseOffersTheNextStep`() {
        val piece = fixtures.piece(fixtures.monthlyStudent(), withCreationEvent = true)

        api.changeStatus(api.lead(), piece, "IN_PROGRESS", "BISQUE_FIRED").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("BISQUE_FIRED") }
            jsonPath("$.advanceTo") { value("GLAZED") }
            jsonPath("$.revertTo") { value("IN_PROGRESS") }
            jsonPath("$.history[0].eventType") { value("ADVANCED") }
            jsonPath("$.history[0].correction") { value(false) }
        }
        assertThat(api.eventCount(piece)).isEqualTo(2)
    }

    @Test
    fun `RF26_aStaleExpectedStatusIs409AndChangesNothing`() {
        val piece = fixtures.piece(fixtures.monthlyStudent(), withCreationEvent = true)

        api.changeStatus(api.lead(), piece, "BISQUE_FIRED", "GLAZED").andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("status_conflict") }
            jsonPath("$.error.details.pieces[0].id") { value(piece) }
            jsonPath("$.error.details.pieces[0].reason") { value("CHANGED") }
        }
        assertThat(api.statusOf(piece)).isEqualTo("IN_PROGRESS")
        assertThat(api.eventCount(piece)).isEqualTo(1)
    }

    @Test
    fun `RF26_aJumpIs400InvalidTransition`() {
        val piece = fixtures.piece(fixtures.monthlyStudent())

        api.changeStatus(api.lead(), piece, "IN_PROGRESS", "GLAZED").isError(400, "invalid_transition")
        assertThat(api.statusOf(piece)).isEqualTo("IN_PROGRESS")
    }

    @Test
    fun `RF26_anUnknownStatusIs400`() {
        val piece = fixtures.piece(fixtures.monthlyStudent())

        api.changeStatus(api.lead(), piece, "IN_PROGRESS", "BROKEN").isError(400, "validation_error")
    }

    @Test
    fun `CP21_revertingAGlazedPieceShowsACorrectionInTheHistory`() {
        val student = api.student()
        val piece = fixtures.piece(student.userId, status = "GLAZED", withCreationEvent = true)

        api.changeStatus(api.lead(), piece, "GLAZED", "BISQUE_FIRED").andExpect { status { isOk() } }

        api.get(api.lead(), "/pieces/$piece").andExpect {
            jsonPath("$.history[0].eventType") { value("REVERTED") }
            jsonPath("$.history[0].correction") { value(true) }
        }
        api.get(student, "/students/me/pieces/$piece").andExpect {
            jsonPath("$.history[0].eventType") { value("REVERTED") }
            jsonPath("$.history[0].correction") { value(true) }
        }
    }

    // ---- editing ----

    @Test
    fun `RF27_aTeacherEditsAFinishedPiece`() {
        val piece = fixtures.piece(fixtures.monthlyStudent(), status = "FINISHED", name = "Taza")

        api.patch(api.lead(), "/pieces/$piece", mapOf("name" to "Jarra", "notes" to "con tapa")).andExpect {
            status { isOk() }
            jsonPath("$.name") { value("Jarra") }
            jsonPath("$.notes") { value("con tapa") }
            jsonPath("$.status") { value("FINISHED") }
        }
        assertThat(api.eventCount(piece)).isZero()
    }

    @Test
    fun `DI26_editingToTheNameOfAnotherPieceOfTheStudentIs409`() {
        val student = fixtures.monthlyStudent()
        fixtures.piece(student, name = "Jarra")
        val piece = fixtures.piece(student, name = "Taza")

        api.patch(api.lead(), "/pieces/$piece", mapOf("name" to "jarra")).isError(409, "duplicate_piece_name")
    }

    @Test
    fun `RF27_aTeacherCannotSendAStatusInTheEdit`() {
        val piece = fixtures.piece(fixtures.monthlyStudent())

        api.patch(api.lead(), "/pieces/$piece", """{"name":"Jarra","status":"FINISHED"}""").isError(400, "validation_error")
        assertThat(api.statusOf(piece)).isEqualTo("IN_PROGRESS")
    }

    // ---- listing ----

    @Test
    fun `RF31_teachersSeePiecesOfInactiveStudents`() {
        val student = fixtures.monthlyStudent()
        val piece = fixtures.piece(student)
        jdbc.update("UPDATE app_user SET active = false WHERE id = ?", student)

        assertThat(api.idsOf(api.get(api.assistant(), "/pieces?studentId=$student"))).containsExactly(piece)
    }

    @Test
    fun `RF32_filteringByStudentAndStatusReturnsOnlyPiecesMatchingBoth`() {
        val ana = fixtures.monthlyStudent()
        val luis = fixtures.monthlyStudent()
        val anaGlazed = fixtures.piece(ana, status = "GLAZED")
        fixtures.piece(ana, status = "IN_PROGRESS")
        fixtures.piece(luis, status = "GLAZED")

        val result = api.get(api.lead(), "/pieces?studentId=$ana&status=GLAZED")

        result.andExpect {
            status { isOk() }
            jsonPath("$[0].advanceTo") { value("FINISHED") }
            jsonPath("$[0].revertTo") { value("BISQUE_FIRED") }
            jsonPath("$[0].student.id") { value(ana) }
        }
        assertThat(api.idsOf(result)).containsExactly(anaGlazed)
    }

    @Test
    fun `RF32_anUnknownStatusFilterIs400`() {
        api.get(api.lead(), "/pieces?status=BROKEN").isError(400, "validation_error")
    }

    // ---- voiding ----

    @Test
    fun `RF56_voidingWithoutAReasonIs400`() {
        val piece = fixtures.piece(fixtures.monthlyStudent())

        api.voidPiece(api.lead(), piece, reason = "   ").isError(400, "validation_error")
        api.post(api.lead(), "/pieces/$piece/void", "{}").isError(400, "validation_error")
    }

    @Test
    fun `RF56_aVoidedPieceDisappearsAndKeepsItsHistory`() {
        val student = api.student()
        val piece = fixtures.piece(student.userId, status = "GLAZED", withCreationEvent = true)
        val lead = api.lead()

        api.voidPiece(lead, piece, reason = "  Se rompió en el horno ").andExpect { status { isNoContent() } }

        assertThat(api.idsOf(api.get(lead, "/pieces"))).doesNotContain(piece)
        assertThat(api.idsOf(api.get(student, "/students/me/pieces"))).isEmpty()
        api.get(lead, "/pieces/$piece").isError(404, "not_found")
        api.get(student, "/students/me/pieces/$piece").isError(404, "not_found")
        assertThat(
            jdbc.queryForObject(
                "SELECT event_type || ':' || previous_status FROM piece_status_history WHERE piece_id = ? ORDER BY id DESC LIMIT 1",
                String::class.java, piece,
            ),
        ).isEqualTo("VOIDED:GLAZED")
        assertThat(jdbc.queryForObject("SELECT void_reason FROM piece WHERE id = ?", String::class.java, piece)).isEqualTo("Se rompió en el horno")
    }

    @Test
    fun `DI74_aVoidedPieceIs404OnEveryTeacherRoute`() {
        val student = fixtures.monthlyStudent()
        val piece = fixtures.piece(student, withCreationEvent = true)
        val lead = api.lead()
        api.voidPiece(lead, piece).andExpect { status { isNoContent() } }

        api.get(lead, "/pieces/$piece").isError(404, "not_found")
        api.patch(lead, "/pieces/$piece", mapOf("name" to "Otro")).isError(404, "not_found")
        api.changeStatus(lead, piece, "IN_PROGRESS", "BISQUE_FIRED").isError(404, "not_found")
        api.voidPiece(lead, piece).isError(404, "not_found")
        assertThat(api.eventCount(piece)).isEqualTo(2)
    }

    // ---- bulk change ----

    private fun bulk(session: PieceApi.Session, expected: String, vararg pieceIds: Long) = api.post(
        session, "/pieces/bulk-change-status",
        mapOf("items" to pieceIds.map { mapOf("pieceId" to it, "expectedStatus" to expected) }),
    )

    @Test
    fun `RF29_aBulkChangeAdvancesAllAndReturnsTheSummary`() {
        val ana = fixtures.monthlyStudent()
        val luis = fixtures.monthlyStudent()
        val pieces = listOf(
            fixtures.piece(ana, status = "BISQUE_FIRED", withCreationEvent = true),
            fixtures.piece(ana, status = "BISQUE_FIRED", withCreationEvent = true),
            fixtures.piece(luis, status = "BISQUE_FIRED", withCreationEvent = true),
        )

        bulk(api.assistant(), "BISQUE_FIRED", *pieces.toLongArray()).andExpect {
            status { isOk() }
            jsonPath("$.newStatus") { value("GLAZED") }
            jsonPath("$.count") { value(3) }
            jsonPath("$.studentCount") { value(2) }
            jsonPath("$.operationId") { exists() }
        }

        assertThat(pieces.map { api.statusOf(it) }).containsOnly("GLAZED")
        val operations = jdbc.queryForList(
            "SELECT DISTINCT operation_id FROM piece_status_history WHERE event_type = 'ADVANCED' AND piece_id IN (${pieces.joinToString(",")})",
        )
        assertThat(operations).hasSize(1)
        assertThat(pieces.map { api.eventCount(it) }).containsOnly(2)
    }

    @Test
    fun `RF29_mixedStatusesIs400`() {
        val student = fixtures.monthlyStudent()
        val first = fixtures.piece(student, status = "BISQUE_FIRED")
        val second = fixtures.piece(student, status = "GLAZED")

        api.post(
            api.lead(), "/pieces/bulk-change-status",
            mapOf("items" to listOf(mapOf("pieceId" to first, "expectedStatus" to "BISQUE_FIRED"), mapOf("pieceId" to second, "expectedStatus" to "GLAZED"))),
        ).isError(400, "mixed_statuses")
    }

    @Test
    fun `RF29_finishedIs400NoNextStatus`() {
        val piece = fixtures.piece(fixtures.monthlyStudent(), status = "FINISHED")

        bulk(api.lead(), "FINISHED", piece).isError(400, "no_next_status")
    }

    @Test
    fun `RF29_anEmptySelectionIs400`() {
        api.post(api.lead(), "/pieces/bulk-change-status", mapOf("items" to emptyList<Any>())).isError(400, "validation_error")
    }

    @Test
    fun `RF61_aVoidedPieceInTheSelectionIs409AndNothingChanges`() {
        val student = fixtures.monthlyStudent()
        val voided = fixtures.piece(student, status = "BISQUE_FIRED", withCreationEvent = true)
        val other = fixtures.piece(student, status = "BISQUE_FIRED", withCreationEvent = true)
        jdbc.update("UPDATE piece SET voided_at = now(), voided_by = ?, void_reason = 'Rota' WHERE id = ?", student, voided)

        bulk(api.lead(), "BISQUE_FIRED", voided, other).andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("status_conflict") }
            jsonPath("$.error.details.pieces.length()") { value(1) }
            jsonPath("$.error.details.pieces[0].id") { value(voided) }
            jsonPath("$.error.details.pieces[0].reason") { value("VOIDED") }
        }
        assertThat(api.statusOf(other)).isEqualTo("BISQUE_FIRED")
        assertThat(api.eventCount(other)).isEqualTo(1)
    }

    @Test
    fun `CP22_aStudentCannotUseTheBulkChange`() {
        val piece = fixtures.piece(fixtures.monthlyStudent(), status = "BISQUE_FIRED")

        bulk(api.student(), "BISQUE_FIRED", piece).isError(403, "role_not_allowed")
        assertThat(api.statusOf(piece)).isEqualTo("BISQUE_FIRED")
    }

    // ---- roles ----

    @Test
    fun `CP22_aStudentCannotUseTheTeacherPieceRoutes`() {
        val studentA = api.student()
        val pieceOfB = fixtures.piece(fixtures.monthlyStudent())

        api.get(studentA, "/pieces").isError(403, "role_not_allowed")
        api.get(studentA, "/pieces/$pieceOfB").isError(403, "role_not_allowed")
        create(studentA, studentA.userId, "Bowl").isError(403, "role_not_allowed")
        api.changeStatus(studentA, pieceOfB, "IN_PROGRESS", "BISQUE_FIRED").isError(403, "role_not_allowed")
        assertThat(api.statusOf(pieceOfB)).isEqualTo("IN_PROGRESS")
    }

    @Test
    fun `CP22_teachersCannotUseTheStudentsOwnRoutes`() {
        api.get(api.lead(), "/students/me/pieces").isError(403, "role_not_allowed")
        api.get(api.assistant(), "/students/me/pieces").isError(403, "role_not_allowed")
    }

    @Test
    fun `RF62_anAssistantTeacherListsAndChangesPieces`() {
        val assistant = api.assistant()
        val piece = fixtures.piece(fixtures.monthlyStudent(), withCreationEvent = true)

        assertThat(api.idsOf(api.get(assistant, "/pieces"))).contains(piece)
        api.changeStatus(assistant, piece, "IN_PROGRESS", "BISQUE_FIRED").andExpect {
            status { isOk() }
            jsonPath("$.history[0].changedBy.id") { value(assistant.userId) }
        }
    }
}
