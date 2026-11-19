package ar.melocoton.auth.infrastructure.persistence

import ar.melocoton.auth.domain.AccountLinkPurpose
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface AccountLinkJpaRepository : JpaRepository<AccountLinkEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM AccountLinkEntity l WHERE l.tokenHash = :tokenHash")
    fun lockByTokenHash(tokenHash: String): AccountLinkEntity?

    // Spring Data builds the query from the method name:
    // SELECT count(*) ... WHERE user_id = ? AND purpose = ? AND created_at >= ?
    fun countByUserIdAndPurposeAndCreatedAtGreaterThanEqual(userId: Long, purpose: AccountLinkPurpose, since: Instant): Long

    // @Modifying: an UPDATE instead of a SELECT. It skips JPA's cache, so clearAutomatically
    // drops loaded entities that would otherwise still show the old used_at.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AccountLinkEntity l SET l.usedAt = :now WHERE l.userId = :userId AND l.usedAt IS NULL")
    fun useAllOpen(userId: Long, now: Instant): Int
}
