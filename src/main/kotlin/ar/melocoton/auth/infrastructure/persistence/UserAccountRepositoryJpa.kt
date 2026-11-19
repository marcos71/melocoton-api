package ar.melocoton.auth.infrastructure.persistence

import ar.melocoton.auth.application.port.UserAccountRepository
import ar.melocoton.auth.domain.EmailAlreadyUsed
import ar.melocoton.auth.domain.NewAccount
import ar.melocoton.auth.domain.UserAccount
import ar.melocoton.shared.domain.Role
import org.springframework.dao.DataIntegrityViolationException
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

    override fun create(account: NewAccount): UserAccount =
        try {
            // Flush now: the unique index is checked on INSERT, and a parallel request with the same
            // email must fail here as the same business error, not at commit (DI-34).
            jpa.saveAndFlush(account.toEntity()).toDomain()
        } catch (e: DataIntegrityViolationException) {
            if (e.mostSpecificCause.message?.contains(EMAIL_UNIQUE_INDEX) == true) throw EmailAlreadyUsed()
            throw e
        }

    override fun findAllByRole(role: Role, active: Boolean?): List<UserAccount> =
        jpa.findAllByRole(role, active).map { it.toDomain() }

    override fun saveContact(account: UserAccount) {
        val entity = checkNotNull(jpa.findByIdOrNull(account.id)) { "Account ${account.id} disappeared" }
        entity.name = account.name
        entity.phone = account.phone
        jpa.flush()
    }

    override fun saveActive(account: UserAccount) {
        val entity = checkNotNull(jpa.findByIdOrNull(account.id)) { "Account ${account.id} disappeared" }
        entity.active = account.active
    }

    private companion object {
        const val EMAIL_UNIQUE_INDEX = "app_user_email_uq"
    }
}
