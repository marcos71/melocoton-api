package ar.melocoton.shared.infrastructure.persistence

import ar.melocoton.shared.application.port.AfterCommit
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * [AfterCommit] over Spring's transaction synchronization: the action is registered on the
 * transaction in progress and Spring calls it after a successful commit. On rollback it is dropped.
 */
@Component
class TransactionAfterCommit : AfterCommit {

    override fun run(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = action()
        })
    }
}
