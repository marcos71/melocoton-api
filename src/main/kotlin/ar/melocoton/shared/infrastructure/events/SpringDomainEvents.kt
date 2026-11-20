package ar.melocoton.shared.infrastructure.events

import ar.melocoton.shared.application.port.DomainEvents
import ar.melocoton.shared.domain.DomainEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/**
 * [DomainEvents] over Spring's [ApplicationEventPublisher]: `@EventListener` methods are called
 * synchronously, in the publisher's thread and transaction (DI-54).
 */
@Component
class SpringDomainEvents(private val publisher: ApplicationEventPublisher) : DomainEvents {
    override fun publish(event: DomainEvent) = publisher.publishEvent(event)
}
