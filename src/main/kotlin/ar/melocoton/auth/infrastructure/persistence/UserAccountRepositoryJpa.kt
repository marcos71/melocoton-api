package ar.melocoton.auth.infrastructure.persistence

import ar.melocoton.auth.application.port.UserAccountRepository
import ar.melocoton.auth.domain.UserAccount
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository

/** Adapter of [UserAccountRepository] over Spring Data JPA. */
@Repository
class UserAccountRepositoryJpa(private val jpa: UserAccountJpaRepository) : UserAccountRepository {

    override fun lockByEmail(email: String): UserAccount? = jpa.lockByEmail(email)?.toDomain()

    override fun findById(id: Long): UserAccount? = jpa.findByIdOrNull(id)?.toDomain()

    override fun lockById(id: Long): UserAccount? = jpa.lockById(id)?.toDomain()

    override fun saveLoginState(account: UserAccount) {
        // Inside the use case's transaction the row is already loaded, so this is not a second query;
        // the change is written when the transaction commits.
        val entity = jpa.getReferenceById(account.id)
        entity.failedLoginAttempts = account.failedLoginAttempts
        entity.lockedUntil = account.lockedUntil
    }

    override fun savePassword(account: UserAccount) {
        val entity = jpa.getReferenceById(account.id)
        entity.passwordHash = account.passwordHash
        entity.failedLoginAttempts = account.failedLoginAttempts
        entity.lockedUntil = account.lockedUntil
        entity.sessionsValidFrom = account.sessionsValidFrom
    }
}
