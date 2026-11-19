package ar.melocoton.auth.infrastructure.persistence

import ar.melocoton.auth.domain.NewAccount
import ar.melocoton.auth.domain.UserAccount
import ar.melocoton.shared.domain.Role
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** Row of `app_user` (V1, V2). Only this adapter knows JPA (DI-04). */
@Entity
@Table(name = "app_user")
class UserAccountEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(columnDefinition = "text")
    var name: String,

    @Column(columnDefinition = "text")
    var email: String,

    @Column(name = "password_hash", columnDefinition = "text")
    var passwordHash: String?,

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "text")
    var role: Role,

    @Column(columnDefinition = "text")
    var phone: String,

    var active: Boolean,

    @Column(name = "failed_login_attempts")
    var failedLoginAttempts: Int,

    @Column(name = "locked_until")
    var lockedUntil: Instant?,

    @Column(name = "sessions_valid_from")
    var sessionsValidFrom: Instant,
)

fun UserAccountEntity.toDomain() = UserAccount(
    id = checkNotNull(id) { "A stored user always has an id" },
    name = name,
    email = email,
    phone = phone,
    role = role,
    passwordHash = passwordHash,
    active = active,
    failedLoginAttempts = failedLoginAttempts,
    lockedUntil = lockedUntil,
    sessionsValidFrom = sessionsValidFrom,
)

fun NewAccount.toEntity() = UserAccountEntity(
    name = name,
    email = email,
    passwordHash = passwordHash,
    role = role,
    phone = phone,
    active = active,
    failedLoginAttempts = 0,
    lockedUntil = null,
    sessionsValidFrom = sessionsValidFrom,
)
