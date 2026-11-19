package ar.melocoton.shared.infrastructure.web

/**
 * Extra check for email fields, next to `@Email`: bean validation alone accepts "a@b", so the pattern
 * asks for a dot in the domain, as a person would write it. Shared by every request that takes an email.
 */
const val EMAIL_WITH_DOMAIN_DOT = "^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$"
