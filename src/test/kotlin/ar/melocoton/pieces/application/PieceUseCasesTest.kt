package ar.melocoton.pieces.application

import ar.melocoton.pieces.application.port.PieceDetail
import ar.melocoton.pieces.application.port.PieceQueries
import ar.melocoton.pieces.application.port.PieceRepository
import ar.melocoton.pieces.domain.BulkItem
import ar.melocoton.pieces.domain.Piece
import ar.melocoton.pieces.domain.PieceConflictDetails
import ar.melocoton.pieces.domain.PieceConflictReason
import ar.melocoton.pieces.domain.PieceEventType
import ar.melocoton.pieces.domain.PieceHistoryEvent
import ar.melocoton.pieces.domain.PieceNotEditable
import ar.melocoton.pieces.domain.PieceNotFound
import ar.melocoton.pieces.domain.PieceStatus
import ar.melocoton.pieces.domain.PieceStatusConflict
import ar.melocoton.students.application.port.StudentRepository
import ar.melocoton.students.domain.EnrollmentType
import ar.melocoton.students.domain.Student
import ar.melocoton.students.domain.StudentInactive
import ar.melocoton.students.domain.StudentNotFound
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The use cases coordinate ports and the domain: what they load, what they save and what they never write
 * after a missed guarded UPDATE. The rules are tested in the domain; the database in the endpoint tests.
 */
class PieceUseCasesTest {

    private val now = Instant.parse("2026-11-26T15:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val students = mockk<StudentRepository>()
    private val pieces = mockk<PieceRepository>(relaxed = true)
    private val queries = mockk<PieceQueries>()

    private val studentId = 10L
    private val teacherId = 99L

    private fun student(active: Boolean = true) = Student(studentId, EnrollmentType.DROP_IN, null, null, active)

    private fun piece(
        id: Long = 1,
        owner: Long = studentId,
        status: PieceStatus = PieceStatus.IN_PROGRESS,
        voided: Boolean = false,
    ) = Piece(id, owner, "Taza", null, status, voided)

    private fun detail(id: Long = 1, status: PieceStatus = PieceStatus.IN_PROGRESS) =
        PieceDetail(id, studentId, "Ana", "Taza", null, status, now, now, emptyList())

    // ---- CreatePiece ----

    @Test
    fun `RF28_creatingAPieceAppendsOneCreationEventByTheActor`() {
        every { students.lockById(studentId) } returns student()
        every { pieces.namesInUse(studentId) } returns emptyList()
        every { pieces.create(any(), now) } returns 7
        every { queries.findDetail(7) } returns detail(7)
        val events = slot<List<PieceHistoryEvent>>()
        every { pieces.appendHistory(capture(events)) } just runs

        CreatePiece(students, pieces, queries, clock).create(studentId, "Bowl", null, actorId = teacherId)

        assertThat(events.captured).hasSize(1)
        with(events.captured.single()) {
            assertThat(pieceId).isEqualTo(7)
            assertThat(eventType).isEqualTo(PieceEventType.CREATED)
            assertThat(userId).isEqualTo(teacherId)
            assertThat(occurredAt).isEqualTo(now)
        }
    }

    @Test
    fun `RF24_aTeacherCannotCreateAPieceForAnInactiveStudent`() {
        every { students.lockById(studentId) } returns student(active = false)

        assertThatThrownBy { CreatePiece(students, pieces, queries, clock).create(studentId, "Bowl", null, teacherId) }
            .isInstanceOf(StudentInactive::class.java)
        verify(exactly = 0) { pieces.create(any(), any()) }
    }

    @Test
    fun `RF24_anIdThatIsNotAStudentIsNotFound`() {
        every { students.lockById(5) } returns null

        assertThatThrownBy { CreatePiece(students, pieces, queries, clock).create(5, "Bowl", null, teacherId) }
            .isInstanceOf(StudentNotFound::class.java)
        verify(exactly = 0) { pieces.create(any(), any()) }
    }

    // ---- ChangePieceStatus ----

    @Test
    fun `D34_whenTheGuardedUpdateTouchesNoRowNoHistoryIsWrittenAndItIsAConflict`() {
        every { pieces.findById(1) } returnsMany listOf(piece(), piece(voided = true))
        every { pieces.saveStatus(1, PieceStatus.IN_PROGRESS, PieceStatus.BISQUE_FIRED) } returns false

        assertThatThrownBy {
            ChangePieceStatus(pieces, queries, clock).change(1, PieceStatus.IN_PROGRESS, PieceStatus.BISQUE_FIRED, teacherId)
        }.isInstanceOfSatisfying(PieceStatusConflict::class.java) {
            assertThat((it.details as PieceConflictDetails).pieces.single().reason).isEqualTo(PieceConflictReason.VOIDED)
        }
        verify(exactly = 0) { pieces.appendHistory(any()) }
    }

    @Test
    fun `DI74_aPieceAlreadyVoidedWhenLoadedIsNotFoundForEveryAction`() {
        every { pieces.findById(1) } returns piece(voided = true)

        assertThatThrownBy { ChangePieceStatus(pieces, queries, clock).change(1, PieceStatus.IN_PROGRESS, PieceStatus.BISQUE_FIRED, teacherId) }
            .isInstanceOf(PieceNotFound::class.java)
        assertThatThrownBy { VoidPiece(pieces, clock).voidPiece(1, "Rota", teacherId) }.isInstanceOf(PieceNotFound::class.java)
        assertThatThrownBy { EditPiece(students, pieces, queries).edit(1, "Jarra", null) }.isInstanceOf(PieceNotFound::class.java)
        assertThatThrownBy { EditPiece(students, pieces, queries).editOwn(studentId, 1, "Jarra", null) }
            .isInstanceOf(PieceNotFound::class.java)
        verify(exactly = 0) { pieces.appendHistory(any()) }
    }

    @Test
    fun `CP21_aRevertAppendsAReversalEvent`() {
        every { pieces.findById(1) } returns piece(status = PieceStatus.GLAZED)
        every { pieces.saveStatus(1, PieceStatus.GLAZED, PieceStatus.BISQUE_FIRED) } returns true
        every { queries.findDetail(1) } returns detail(status = PieceStatus.BISQUE_FIRED)
        val events = slot<List<PieceHistoryEvent>>()
        every { pieces.appendHistory(capture(events)) } just runs

        ChangePieceStatus(pieces, queries, clock).change(1, PieceStatus.GLAZED, PieceStatus.BISQUE_FIRED, teacherId)

        assertThat(events.captured.single().eventType).isEqualTo(PieceEventType.REVERTED)
        assertThat(events.captured.single().userId).isEqualTo(teacherId)
    }

    // ---- EditPiece ----

    @Test
    fun `RF27_aStudentEditingAnotherStudentsPieceGetsNotFoundAndLocksNothing`() {
        every { pieces.findById(1) } returns piece(owner = studentId + 1)

        assertThatThrownBy { EditPiece(students, pieces, queries).editOwn(studentId, 1, "Jarra", null) }
            .isInstanceOf(PieceNotFound::class.java)
        verify(exactly = 0) { students.lockById(any()) }
    }

    @Test
    fun `RF27_editingDoesNotAppendHistory`() {
        every { pieces.findById(1) } returns piece()
        every { students.lockById(studentId) } returns student()
        every { pieces.namesInUse(studentId) } returns emptyList()
        every { pieces.saveText(any(), PieceStatus.IN_PROGRESS) } returns true
        every { queries.findDetail(1) } returns detail()

        EditPiece(students, pieces, queries).editOwn(studentId, 1, "Jarra", "notas")

        verify(exactly = 0) { pieces.appendHistory(any()) }
    }

    @Test
    fun `RF27_anOwnerEditThatMissesBecauseThePieceLeftInProgressIsNotEditable`() {
        every { pieces.findById(1) } returnsMany listOf(piece(), piece(status = PieceStatus.BISQUE_FIRED))
        every { students.lockById(studentId) } returns student()
        every { pieces.namesInUse(studentId) } returns emptyList()
        every { pieces.saveText(any(), PieceStatus.IN_PROGRESS) } returns false

        assertThatThrownBy { EditPiece(students, pieces, queries).editOwn(studentId, 1, "Jarra", null) }
            .isInstanceOf(PieceNotEditable::class.java)
    }

    // ---- VoidPiece ----

    @Test
    fun `RF56_voidingAppendsAVoidEventWithThePreviousStatus`() {
        every { pieces.findById(1) } returns piece(status = PieceStatus.GLAZED)
        every { pieces.saveVoid(1, PieceStatus.GLAZED, "Rota", teacherId, now) } returns true
        val events = slot<List<PieceHistoryEvent>>()
        every { pieces.appendHistory(capture(events)) } just runs

        VoidPiece(pieces, clock).voidPiece(1, " Rota ", teacherId)

        with(events.captured.single()) {
            assertThat(eventType).isEqualTo(PieceEventType.VOIDED)
            assertThat(previousStatus).isEqualTo(PieceStatus.GLAZED)
            assertThat(newStatus).isNull()
        }
    }

    @Test
    fun `RF61_aVoidThatMissesBecauseSomeoneElseChangedTheStatusWritesNoHistory`() {
        every { pieces.findById(1) } returnsMany listOf(piece(status = PieceStatus.GLAZED), piece(status = PieceStatus.FINISHED))
        every { pieces.saveVoid(any(), any(), any(), any(), any()) } returns false

        assertThatThrownBy { VoidPiece(pieces, clock).voidPiece(1, "Rota", teacherId) }
            .isInstanceOfSatisfying(PieceStatusConflict::class.java) {
                assertThat((it.details as PieceConflictDetails).pieces.single().reason).isEqualTo(PieceConflictReason.CHANGED)
            }
        verify(exactly = 0) { pieces.appendHistory(any()) }
    }

    // ---- BulkChangePieceStatus ----

    @Test
    fun `RF30_whenTheUpdateMissesAPieceNoHistoryIsWrittenAndItIsAConflict`() {
        val bisqueFired = PieceStatus.BISQUE_FIRED
        every { pieces.findByIds(listOf(1L, 2L)) } returns listOf(piece(1, status = bisqueFired), piece(2, status = bisqueFired))
        every { pieces.advanceAll(listOf(1L, 2L), bisqueFired, PieceStatus.GLAZED) } returns setOf(1L)
        every { pieces.findByIds(listOf(2L)) } returns listOf(piece(2, status = PieceStatus.GLAZED))
        val items = listOf(BulkItem(1, bisqueFired), BulkItem(2, bisqueFired))

        assertThatThrownBy { BulkChangePieceStatus(pieces, clock).advance(items, teacherId) }
            .isInstanceOfSatisfying(PieceStatusConflict::class.java) {
                assertThat((it.details as PieceConflictDetails).pieces.map { p -> p.id }).containsExactly(2L)
            }
        verify(exactly = 0) { pieces.appendHistory(any()) }
    }

    @Test
    fun `RF29_aBulkChangeWritesOneEventPerPieceUnderOneOperation`() {
        val glazed = PieceStatus.GLAZED
        every { pieces.findByIds(listOf(1L, 2L)) } returns listOf(piece(1, status = glazed), piece(2, owner = 11, status = glazed))
        every { pieces.advanceAll(listOf(1L, 2L), glazed, PieceStatus.FINISHED) } returns setOf(1L, 2L)
        val events = slot<List<PieceHistoryEvent>>()
        every { pieces.appendHistory(capture(events)) } just runs

        val result = BulkChangePieceStatus(pieces, clock).advance(listOf(BulkItem(1, glazed), BulkItem(2, glazed)), teacherId)

        assertThat(events.captured).hasSize(2)
        assertThat(events.captured.map { it.operationId }.distinct()).containsExactly(result.operationId)
        assertThat(result.count).isEqualTo(2)
        assertThat(result.studentCount).isEqualTo(2)
        assertThat(result.newStatus).isEqualTo(PieceStatus.FINISHED)
    }
}
