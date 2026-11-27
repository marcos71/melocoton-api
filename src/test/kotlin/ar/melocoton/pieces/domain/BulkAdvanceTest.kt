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

class BulkAdvanceTest {

    private fun piece(id: Long, status: PieceStatus = BISQUE_FIRED, studentId: Long = 1, voided: Boolean = false) =
        Piece(id, studentId, "Pieza $id", null, status, voided)

    private fun item(id: Long, expected: PieceStatus = BISQUE_FIRED) = BulkItem(id, expected)

    private fun conflictOf(block: () -> Unit): List<ConflictingPiece> {
        var details: PieceConflictDetails? = null
        assertThatThrownBy(block).isInstanceOfSatisfying(PieceStatusConflict::class.java) {
            assertThat(it.code).isEqualTo("status_conflict")
            details = it.details as PieceConflictDetails
        }
        return checkNotNull(details).pieces
    }

    @Test
    fun `RF29_piecesWithTheSameStatusAllAdvanceToTheNext`() {
        val plan = BulkAdvance.plan(
            listOf(item(1), item(2), item(3)),
            listOf(piece(1, studentId = 10), piece(2, studentId = 10), piece(3, studentId = 11)),
        )

        assertThat(plan.pieceIds).containsExactly(1L, 2L, 3L)
        assertThat(plan.from).isEqualTo(BISQUE_FIRED)
        assertThat(plan.to).isEqualTo(GLAZED)
        assertThat(plan.studentCount).isEqualTo(2)
    }

    @Test
    fun `RF29_mixedExpectedStatusesAreRejected`() {
        assertThatThrownBy {
            BulkAdvance.plan(listOf(item(1), item(2, GLAZED)), listOf(piece(1), piece(2, GLAZED)))
        }.isInstanceOfSatisfying(MixedStatuses::class.java) {
            assertThat(it.code).isEqualTo("mixed_statuses")
            assertThat(it.kind).isEqualTo(BusinessErrorKind.INVALID)
        }
    }

    @Test
    fun `RF29_finishedPiecesHaveNoNextStatus`() {
        assertThatThrownBy { BulkAdvance.plan(listOf(item(1, FINISHED)), listOf(piece(1, FINISHED))) }
            .isInstanceOfSatisfying(NoNextStatus::class.java) { assertThat(it.code).isEqualTo("no_next_status") }
    }

    @Test
    fun `RF29_anEmptyOrRepeatedSelectionIsInvalid`() {
        assertThatThrownBy { BulkAdvance.plan(emptyList(), emptyList()) }.isInstanceOf(InvalidPiece::class.java)
        assertThatThrownBy { BulkAdvance.plan(listOf(item(1), item(1)), listOf(piece(1))) }.isInstanceOf(InvalidPiece::class.java)
        val tooMany = (1L..BulkAdvance.MAX_ITEMS + 1).map { item(it) }
        assertThatThrownBy { BulkAdvance.plan(tooMany, emptyList()) }.isInstanceOf(InvalidPiece::class.java)
    }

    @Test
    fun `RF29_aPieceThatDoesNotExistIsNotFound`() {
        assertThatThrownBy { BulkAdvance.plan(listOf(item(1), item(2)), listOf(piece(1))) }.isInstanceOf(PieceNotFound::class.java)
    }

    @Test
    fun `RF29_aBulkChangeNeverReverts`() {
        listOf(IN_PROGRESS, BISQUE_FIRED, GLAZED).forEach { status ->
            val plan = BulkAdvance.plan(listOf(item(1, status)), listOf(piece(1, status)))

            assertThat(plan.to).isEqualTo(status.next())
        }
    }

    @Test
    fun `RF30_aPieceThatChangedIsAConflictThatNamesIt`() {
        val conflicts = conflictOf {
            BulkAdvance.plan(listOf(item(1), item(2), item(3)), listOf(piece(1), piece(2, GLAZED), piece(3)))
        }

        assertThat(conflicts).containsExactly(ConflictingPiece(2, "Pieza 2", PieceConflictReason.CHANGED))
    }

    @Test
    fun `RF61_aVoidedPieceInTheSelectionIsAConflict`() {
        val conflicts = conflictOf {
            BulkAdvance.plan(listOf(item(1), item(2)), listOf(piece(1, voided = true), piece(2)))
        }

        assertThat(conflicts).containsExactly(ConflictingPiece(1, "Pieza 1", PieceConflictReason.VOIDED))
    }

    @Test
    fun `RF29_everyEventSharesTheOperationId`() {
        val plan = BulkAdvance.plan(listOf(item(1), item(2)), listOf(piece(1), piece(2)))
        val operationId = UUID.randomUUID()
        val at = Instant.parse("2026-11-26T15:00:00Z")

        val events = plan.events(by = 99, at = at, operationId = operationId)

        assertThat(events).containsExactly(
            PieceHistoryEvent(1, PieceEventType.ADVANCED, BISQUE_FIRED, GLAZED, at, 99, operationId),
            PieceHistoryEvent(2, PieceEventType.ADVANCED, BISQUE_FIRED, GLAZED, at, 99, operationId),
        )
    }

    @Test
    fun `RF30_afterARaceTheReasonComesFromTheReloadedPieces`() {
        val plan = BulkAdvance.plan(listOf(item(1), item(2)), listOf(piece(1), piece(2)))

        val conflict = plan.conflictAfterRace(listOf(piece(1, GLAZED), piece(2, voided = true)))

        assertThat((conflict.details as PieceConflictDetails).pieces.map { it.reason })
            .containsExactly(PieceConflictReason.CHANGED, PieceConflictReason.VOIDED)
    }
}
