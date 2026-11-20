package ar.melocoton.shared.application.port

import ar.melocoton.shared.domain.DomainEvent

/**
 * Publishes a [DomainEvent] so the module that owns the reaction can handle it (DI-54).
 * Listeners run synchronously, in the same thread and in the same transaction as the publisher:
 * a listener error propagates to the publisher and rolls everything back.
 */
fun interface DomainEvents {
    fun publish(event: DomainEvent)
}
