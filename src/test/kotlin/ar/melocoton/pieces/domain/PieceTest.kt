package ar.melocoton.pieces.domain

import ar.melocoton.pieces.domain.PieceStatus.BISQUE_FIRED
import ar.melocoton.pieces.domain.PieceStatus.FINISHED
import ar.melocoton.pieces.domain.PieceStatus.GLAZED
import ar.melocoton.pieces.domain.PieceStatus.IN_PROGRESS
import ar.melocoton.shared.domain.BusinessErrorKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PieceTest {

    private val at = Instant.parse("2026-11-26T15:00:00Z")
    private val operationId = UUID.randomUUID()
    private val ownerId = 10L
    private val teacherId = 99L

    private fun piece(
        status: PieceStatus = IN_PROGRESS,
        voided: Boolean = false,
        studentId: Long = ownerId,
        name: String = "Taza con asa",
    ) = Piece(id = 1, studentId = studentId, name = name, notes = null, status = status, voided = voided)

    private fun change(
        piece: Piece,
        target: PieceStatus,
        expected: PieceStatus = piece.status,
        namesInUse: List<PieceName> = emptyList(),
    ) = piece.changeTo(expected, target, namesInUse, teacherId, at, operationId)

    private fun voidedConflict(block: () -> Unit) = assertThatThrownBy(block)
        .isInstanceOfSatisfying(PieceStatusConflict::class.java) {
            assertThat(it.code).isEqualTo("status_conflict")
            assertThat((it.details as PieceConflictDetails).pieces.single().reason).isEqualTo(PieceConflictReason.VOIDED)
        }

    // ---- NewPiece and PieceText ----

    @Test
    fun `RF25_aNewPieceStartsInProgressWithACreationEvent`() {
        val newPiece = NewPiece.create(ownerId, "Bowl", null, emptyList())

        val event = newPiece.createdEvent(pieceId = 5, by = teacherId, at = at, operationId = operationId)

        assertThat(event).isEqualTo(
            PieceHistoryEvent(5, PieceEventType.CREATED, previousStatus = null, newStatus = IN_PROGRESS, occurredAt = at, userId = teacherId, operationId = operationId),
        )
    }

    @Test
    fun `RF24_theNameIsRequiredTrimmedAndAtMost100Characters`() {
        assertThat(PieceText.of("  Bowl  ", null).name).isEqualTo("Bowl")
        assertThat(PieceText.of("a".repeat(PieceText.NAME_MAX_LENGTH), null).name).hasSize(PieceText.NAME_MAX_LENGTH)
        assertThatThrownBy { PieceText.of("   ", null) }
            .isInstanceOfSatisfying(InvalidPiece::class.java) {
                assertThat(it.code).isEqualTo("validation_error")
                assertThat(it.kind).isEqualTo(BusinessErrorKind.INVALID)
            }
        assertThatThrownBy { PieceText.of("a".repeat(PieceText.NAME_MAX_LENGTH + 1), null) }.isInstanceOf(InvalidPiece::class.java)
    }

    @Test
    fun `DI72_blankNotesAreStoredAsNoNotes`() {
        assertThat(PieceText.of("Bowl", "   ").notes).isNull()
        assertThat(PieceText.of("Bowl", null).notes).isNull()
        assertThat(PieceText.of("Bowl", "  esmalte verde ").notes).isEqualTo("esmalte verde")
    }

    @Test
    fun `DI72_notesLongerThan500AreInvalid`() {
        assertThat(PieceText.of("Bowl", "n".repeat(PieceText.NOTES_MAX_LENGTH)).notes).hasSize(PieceText.NOTES_MAX_LENGTH)
        assertThatThrownBy { PieceText.of("Bowl", "n".repeat(PieceText.NOTES_MAX_LENGTH + 1)) }.isInstanceOf(InvalidPiece::class.java)
    }

    // ---- Changing the status ----

    @Test
    fun `RF26_advancesOneStepAtATime`() {
        val piece = piece()

        listOf(BISQUE_FIRED, GLAZED, FINISHED).forEach { target ->
            val event = change(piece, target)

            assertThat(event.eventType).isEqualTo(PieceEventType.ADVANCED)
            assertThat(piece.status).isEqualTo(target)
        }
    }

    @Test
    fun `RF26_revertsOneStepAtATime`() {
        val piece = piece(status = FINISHED)

        listOf(GLAZED, BISQUE_FIRED, IN_PROGRESS).forEach { target ->
            val event = change(piece, target)

            assertThat(event.eventType).isEqualTo(PieceEventType.REVERTED)
            assertThat(piece.status).isEqualTo(target)
        }
    }

    @Test
    fun `RF26_fromBisqueFiredOnlyGlazedOrInProgressAreAllowed`() {
        assertThat(change(piece(status = BISQUE_FIRED), GLAZED).newStatus).isEqualTo(GLAZED)
        assertThat(change(piece(status = BISQUE_FIRED), IN_PROGRESS).newStatus).isEqualTo(IN_PROGRESS)
        assertThatThrownBy { change(piece(status = BISQUE_FIRED), FINISHED) }.isInstanceOf(InvalidTransition::class.java)
        assertThatThrownBy { change(piece(status = BISQUE_FIRED), BISQUE_FIRED) }.isInstanceOf(InvalidTransition::class.java)
    }

    @Test
    fun `RF26_aJumpOrTheSameStatusIsAnInvalidTransition`() {
        assertThatThrownBy { change(piece(status = IN_PROGRESS), GLAZED) }
            .isInstanceOfSatisfying(InvalidTransition::class.java) {
                assertThat(it.code).isEqualTo("invalid_transition")
                assertThat(it.kind).isEqualTo(BusinessErrorKind.INVALID)
            }
        assertThatThrownBy { change(piece(status = FINISHED), IN_PROGRESS) }.isInstanceOf(InvalidTransition::class.java)
        assertThatThrownBy { change(piece(status = GLAZED), GLAZED) }.isInstanceOf(InvalidTransition::class.java)
    }

    @Test
    fun `RF26_aStaleExpectedStatusIsAConflictAndChangesNothing`() {
        val piece = piece(status = GLAZED)

        assertThatThrownBy { change(piece, FINISHED, expected = BISQUE_FIRED) }
            .isInstanceOfSatisfying(PieceStatusConflict::class.java) {
                assertThat(it.kind).isEqualTo(BusinessErrorKind.CONFLICT)
                assertThat(it.message).contains("Taza con asa")
                assertThat((it.details as PieceConflictDetails).pieces)
                    .containsExactly(ConflictingPiece(1, "Taza con asa", PieceConflictReason.CHANGED))
            }
        assertThat(piece.status).isEqualTo(GLAZED)
    }

    @Test
    fun `CP21_revertingAGlazedPieceIsRecordedAsACorrection`() {
        val event = change(piece(status = GLAZED), BISQUE_FIRED)

        assertThat(event.eventType).isEqualTo(PieceEventType.REVERTED)
        assertThat(event.previousStatus).isEqualTo(GLAZED)
        assertThat(event.newStatus).isEqualTo(BISQUE_FIRED)
        assertThat(event.eventType.isCorrection).isTrue()
    }

    @Test
    fun `RF28_theEventCarriesStatusesDateUserAndOperation`() {
        val event = piece(status = IN_PROGRESS).changeTo(IN_PROGRESS, BISQUE_FIRED, emptyList(), teacherId, at, operationId)

        assertThat(event).isEqualTo(
            PieceHistoryEvent(1, PieceEventType.ADVANCED, IN_PROGRESS, BISQUE_FIRED, at, teacherId, operationId),
        )
        assertThat(event.eventType.isCorrection).isFalse()
    }

    @Test
    fun `DI91_revertingAFinishedPieceWhoseNameAnotherActivePieceHoldsIsRejectedAndStaysFinished`() {
        val piece = piece(status = FINISHED, name = "Taza con asa")
        val names = listOf(
            PieceName(1, "Taza con asa", FINISHED),
            PieceName(2, "  taza CON asa ", IN_PROGRESS),
        )

        assertThatThrownBy { change(piece, GLAZED, namesInUse = names) }.isInstanceOf(DuplicatePieceName::class.java)
        assertThat(piece.status).isEqualTo(FINISHED)
    }

    @Test
    fun `DI91_revertingAFinishedPieceWithAFreeNameWorks`() {
        val piece = piece(status = FINISHED, name = "Taza con asa")
        val names = listOf(
            PieceName(1, "Taza con asa", FINISHED),
            PieceName(2, "Taza con asa", FINISHED),
            PieceName(3, "Jarra", IN_PROGRESS),
        )

        val event = change(piece, GLAZED, namesInUse = names)

        assertThat(event.eventType).isEqualTo(PieceEventType.REVERTED)
        assertThat(piece.status).isEqualTo(GLAZED)
    }

    @Test
    fun `DI91_thePieceOwnNameDoesNotCountAgainstItself`() {
        val piece = piece(status = FINISHED, name = "Taza con asa")
        val names = listOf(PieceName(piece.id, "Taza con asa", GLAZED))

        val event = change(piece, GLAZED, namesInUse = names)

        assertThat(event.eventType).isEqualTo(PieceEventType.REVERTED)
        assertThat(piece.status).isEqualTo(GLAZED)
    }

    @Test
    fun `DI91_onlyLeavingFinishedReclaimsTheName`() {
        assertThat(piece(status = FINISHED).reclaimsNameOn(GLAZED)).isTrue()
        assertThat(piece(status = GLAZED).reclaimsNameOn(FINISHED)).isFalse()
        assertThat(piece(status = GLAZED).reclaimsNameOn(BISQUE_FIRED)).isFalse()
        assertThat(piece(status = IN_PROGRESS).reclaimsNameOn(BISQUE_FIRED)).isFalse()

        val clashing = listOf(PieceName(2, "Taza con asa", IN_PROGRESS))
        val advanced = change(piece(status = GLAZED, name = "Taza con asa"), FINISHED, namesInUse = clashing)
        assertThat(advanced.eventType).isEqualTo(PieceEventType.ADVANCED)
    }

    // ---- Voiding ----

    @Test
    fun `RF56_voidingNeedsAReason`() {
        assertThatThrownBy { piece().void("   ", teacherId, at, operationId) }.isInstanceOf(InvalidPiece::class.java)
        assertThatThrownBy { piece().void("r".repeat(Piece.VOID_REASON_MAX_LENGTH + 1), teacherId, at, operationId) }
            .isInstanceOf(InvalidPiece::class.java)
    }

    @Test
    fun `DI74_voidingRecordsTheStatusThePieceHad`() {
        val piece = piece(status = GLAZED)

        val event = piece.void("  Se rompió en el horno  ", teacherId, at, operationId)

        assertThat(event).isEqualTo(
            PieceHistoryEvent(1, PieceEventType.VOIDED, previousStatus = GLAZED, newStatus = null, occurredAt = at, userId = teacherId, operationId = operationId),
        )
        assertThat(piece.voided).isTrue()
        assertThat(piece.voidReason).isEqualTo("Se rompió en el horno")
        assertThat(piece.status).isEqualTo(GLAZED)
    }

    @Test
    fun `RF61_aVoidedPieceAcceptsNoMoreChanges`() {
        val piece = piece(voided = true)
        val text = PieceText.of("Otro nombre", null)

        voidedConflict { change(piece, BISQUE_FIRED) }
        voidedConflict { piece.edit(text, emptyList()) }
        voidedConflict { piece.void("otra vez", teacherId, at, operationId) }
    }

    @Test
    fun `RF61_theConflictOfAPieceThatIsNotVoidedIsAChange`() {
        assertThat((piece().conflict().details as PieceConflictDetails).pieces.single().reason)
            .isEqualTo(PieceConflictReason.CHANGED)
        assertThat((piece(voided = true).conflict().details as PieceConflictDetails).pieces.single().reason)
            .isEqualTo(PieceConflictReason.VOIDED)
    }

    // ---- Editing ----

    @Test
    fun `RF27_theOwnerEditsOnlyWhileInProgress`() {
        val inProgress = piece(status = IN_PROGRESS)
        inProgress.editAsOwner(ownerId, PieceText.of("Jarra", "con tapa"), emptyList())

        assertThat(inProgress.name).isEqualTo("Jarra")
        assertThat(inProgress.notes).isEqualTo("con tapa")

        listOf(BISQUE_FIRED, GLAZED, FINISHED).forEach { status ->
            assertThatThrownBy { piece(status = status).editAsOwner(ownerId, PieceText.of("Jarra", null), emptyList()) }
                .isInstanceOfSatisfying(PieceNotEditable::class.java) {
                    assertThat(it.code).isEqualTo("piece_not_editable")
                    assertThat(it.kind).isEqualTo(BusinessErrorKind.CONFLICT)
                }
        }
    }

    @Test
    fun `RF27_anotherStudentsPieceIsNotFoundForTheOwner`() {
        assertThatThrownBy { piece(studentId = ownerId).editAsOwner(ownerId + 1, PieceText.of("Jarra", null), emptyList()) }
            .isInstanceOfSatisfying(PieceNotFound::class.java) { assertThat(it.kind).isEqualTo(BusinessErrorKind.NOT_FOUND) }
        assertThatThrownBy { piece(voided = true).editAsOwner(ownerId, PieceText.of("Jarra", null), emptyList()) }
            .isInstanceOf(PieceNotFound::class.java)
    }

    @Test
    fun `RF27_teachersEditInAnyStatus`() {
        val piece = piece(status = FINISHED)

        piece.edit(PieceText.of("Jarra", "nota"), emptyList())

        assertThat(piece.name).isEqualTo("Jarra")
        assertThat(piece.notes).isEqualTo("nota")
    }

    @Test
    fun `DI26_editingToTheNameOfAnotherActivePieceIsRejectedAndChangesNothing`() {
        val piece = piece(name = "Taza")
        val others = listOf(PieceName(pieceId = 2, name = "Jarra", status = IN_PROGRESS))

        assertThatThrownBy { piece.edit(PieceText.of("jarra", null), others) }.isInstanceOf(DuplicatePieceName::class.java)
        assertThat(piece.name).isEqualTo("Taza")
    }

    // ---- RN-04 ----

    @Test
    fun `DI70_advanceToAndRevertToFollowRN04`() {
        assertThat(IN_PROGRESS.next()).isEqualTo(BISQUE_FIRED)
        assertThat(BISQUE_FIRED.next()).isEqualTo(GLAZED)
        assertThat(GLAZED.next()).isEqualTo(FINISHED)
        assertThat(FINISHED.next()).isNull()
        assertThat(IN_PROGRESS.previous()).isNull()
        assertThat(BISQUE_FIRED.previous()).isEqualTo(IN_PROGRESS)
        assertThat(FINISHED.previous()).isEqualTo(GLAZED)
        assertThat(PieceStatus.entries.filter { it.editableByOwner }).containsExactly(IN_PROGRESS)
    }

    // ---- Visibility (DI-74, DI-75) ----

    @Test
    fun `DI74_aVoidedPieceIsNotFoundForWhoeverLoadsIt`() {
        assertThatThrownBy { piece(voided = true).requireVisible() }.isInstanceOf(PieceNotFound::class.java)
        piece().requireVisible()
    }

    @Test
    fun `DI75_aPieceIsVisibleOnlyToItsOwnerAndOnlyWhileNotVoided`() {
        piece().requireVisibleTo(ownerId)
        assertThatThrownBy { piece().requireVisibleTo(ownerId + 1) }.isInstanceOf(PieceNotFound::class.java)
        assertThatThrownBy { piece(voided = true).requireVisibleTo(ownerId) }.isInstanceOf(PieceNotFound::class.java)
    }

    @Test
    fun `RF27_anOwnerEditThatMissedIsNotFoundWhenVoidedAndNotEditableOtherwise`() {
        assertThat(piece(voided = true).ownerEditConflict()).isInstanceOf(PieceNotFound::class.java)
        assertThat(piece(status = BISQUE_FIRED).ownerEditConflict()).isInstanceOf(PieceNotEditable::class.java)
    }
}
