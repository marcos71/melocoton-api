package ar.melocoton.auth.infrastructure.persistence

import ar.melocoton.shared.domain.Role
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface UserAccountJpaRepository : JpaRepository<UserAccountEntity, Long> {

    // lower(...) on both sides, so PostgreSQL can use the unique index app_user_email_uq.
    // PESSIMISTIC_WRITE adds FOR UPDATE: a parallel attempt waits until this one commits.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserAccountEntity u WHERE lower(u.email) = lower(:email)")
    fun lockByEmail(email: String): UserAccountEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserAccountEntity u WHERE u.id = :id")
    fun lockById(id: Long): UserAccountEntity?

    // A null `active` means both states. cast(... as boolean) lets PostgreSQL type the null parameter.
    @Query(
        "SELECT u FROM UserAccountEntity u WHERE u.role = :role " +
            "AND (cast(:active as boolean) IS NULL OR u.active = :active) ORDER BY lower(u.name), u.id",
    )
    fun findAllByRole(role: Role, active: Boolean?): List<UserAccountEntity>
}
