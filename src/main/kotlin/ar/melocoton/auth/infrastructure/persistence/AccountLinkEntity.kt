package ar.melocoton.auth.infrastructure.persistence

import ar.melocoton.auth.domain.AccountLink
import ar.melocoton.auth.domain.AccountLinkPurpose
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** Row of `account_link` (V2). */
@Entity
@Table(name = "account_link")
class AccountLinkEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "user_id")
    val userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "text")
    val purpose: AccountLinkPurpose,

    @Column(name = "token_hash", columnDefinition = "text")
    val tokenHash: String,

    @Column(name = "created_at")
    val createdAt: Instant,

    @Column(name = "expires_at")
    val expiresAt: Instant,

    @Column(name = "used_at")
    var usedAt: Instant?,
)

fun AccountLinkEntity.toDomain() = AccountLink(
    id = id, userId = userId, purpose = purpose, tokenHash = tokenHash,
    createdAt = createdAt, expiresAt = expiresAt, usedAt = usedAt,
)

fun AccountLink.toEntity() = AccountLinkEntity(
    id = id, userId = userId, purpose = purpose, tokenHash = tokenHash,
    createdAt = createdAt, expiresAt = expiresAt, usedAt = usedAt,
)
