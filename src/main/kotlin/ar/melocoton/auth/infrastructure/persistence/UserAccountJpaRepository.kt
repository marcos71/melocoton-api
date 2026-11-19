package ar.melocoton.auth.infrastructure.persistence

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
}
