package ar.melocoton.shared.domain

/**
 * Marker for a fact that already happened in one module and that another module may react to (DI-54).
 * The event lives in the domain of the module that owns the reaction.
 */
interface DomainEvent
