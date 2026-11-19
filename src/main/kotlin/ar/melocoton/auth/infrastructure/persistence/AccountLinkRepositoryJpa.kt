package ar.melocoton.auth.infrastructure.persistence

import ar.melocoton.auth.application.port.AccountLinkRepository
import ar.melocoton.auth.domain.AccountLink
import ar.melocoton.auth.domain.AccountLinkPurpose
import org.springframework.stereotype.Repository
import java.time.Instant

/** Adapter of [AccountLinkRepository] over Spring Data JPA. */
@Repository
class AccountLinkRepositoryJpa(private val jpa: AccountLinkJpaRepository) : AccountLinkRepository {

    override fun save(link: AccountLink) {
        jpa.save(link.toEntity())
    }

    override fun lockByTokenHash(tokenHash: String): AccountLink? = jpa.lockByTokenHash(tokenHash)?.toDomain()

    override fun countCreatedSince(userId: Long, purpose: AccountLinkPurpose, since: Instant): Int =
        jpa.countByUserIdAndPurposeAndCreatedAtGreaterThanEqual(userId, purpose, since).toInt()

    override fun useAllOpen(userId: Long, now: Instant) {
        jpa.useAllOpen(userId, now)
    }
}
