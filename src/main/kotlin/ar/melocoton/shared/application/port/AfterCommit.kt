package ar.melocoton.shared.application.port

/**
 * Runs an action once the current transaction has committed, and never if it rolls back.
 * Used for side effects that cannot be undone, such as sending an email. Outside a
 * transaction the action runs right away.
 */
fun interface AfterCommit {
    fun run(action: () -> Unit)
}
