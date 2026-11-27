package ar.melocoton.pieces.domain

import ar.melocoton.pieces.domain.PieceStatus.BISQUE_FIRED
import ar.melocoton.pieces.domain.PieceStatus.FINISHED
import ar.melocoton.pieces.domain.PieceStatus.IN_PROGRESS
import ar.melocoton.shared.domain.BusinessErrorKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PieceNamesTest {

    private val inUse = listOf(PieceName(pieceId = 1, name = "Bowl 1", status = IN_PROGRESS))

    @Test
    fun `DI26_aNameInUseIsRejectedIgnoringCaseAndSpaces`() {
        assertThatThrownBy { requireUniqueName("  bOWL 1 ", inUse) }
            .isInstanceOfSatisfying(DuplicatePieceName::class.java) {
                assertThat(it.code).isEqualTo("duplicate_piece_name")
                assertThat(it.kind).isEqualTo(BusinessErrorKind.CONFLICT)
            }
        assertThatCode { requireUniqueName("Bowl 2", inUse) }.doesNotThrowAnyException()
    }

    @Test
    fun `DI26_voidedPiecesDoNotCount`() {
        // The port never returns voided pieces (DI-74), so a voided piece is simply absent from the list.
        assertThatCode { requireUniqueName("Bowl 1", emptyList()) }.doesNotThrowAnyException()
    }

    @Test
    fun `DI26_renamingAPieceToItsOwnNameIsAllowed`() {
        assertThatCode { requireUniqueName("Bowl 1", inUse, excludingPieceId = 1) }.doesNotThrowAnyException()
        assertThatThrownBy { requireUniqueName("Bowl 1", inUse, excludingPieceId = 2) }.isInstanceOf(DuplicatePieceName::class.java)
    }

    @Test
    fun `DI73_aFinishedPieceFreesItsName`() {
        val finished = listOf(PieceName(pieceId = 1, name = "Bowl 1", status = FINISHED))

        assertThatCode { requireUniqueName("Bowl 1", finished) }.doesNotThrowAnyException()
    }

    @Test
    fun `DI26_aPieceThatIsNotFinishedStillHoldsItsName`() {
        val bisqueFired = listOf(PieceName(pieceId = 1, name = "Bowl 1", status = BISQUE_FIRED))

        assertThatThrownBy { requireUniqueName("Bowl 1", bisqueFired) }.isInstanceOf(DuplicatePieceName::class.java)
    }
}
