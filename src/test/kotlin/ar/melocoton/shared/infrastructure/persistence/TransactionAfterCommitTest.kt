package ar.melocoton.shared.infrastructure.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager

/** The synchronization is driven by hand, as the transaction manager does on commit. */
class TransactionAfterCommitTest {

    private val afterCommit = TransactionAfterCommit()

    @AfterEach
    fun cleanUp() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `RF53_insideATransactionTheActionRunsOnlyAfterTheCommit`() {
        var ran = false
        TransactionSynchronizationManager.initSynchronization()

        afterCommit.run { ran = true }
        assertThat(ran).isFalse()

        TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
        assertThat(ran).isTrue()
    }

    @Test
    fun `RF53_ifTheTransactionRollsBackTheActionNeverRuns`() {
        var ran = false
        TransactionSynchronizationManager.initSynchronization()

        afterCommit.run { ran = true }
        // A rollback only calls afterCompletion, never afterCommit.
        TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCompletion(2) }

        assertThat(ran).isFalse()
    }

    @Test
    fun `RF53_outsideATransactionTheActionRunsRightAway`() {
        var ran = false

        afterCommit.run { ran = true }

        assertThat(ran).isTrue()
    }
}
