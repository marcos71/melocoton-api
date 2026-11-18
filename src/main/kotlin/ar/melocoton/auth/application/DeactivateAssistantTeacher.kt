package ar.melocoton.auth.application

import ar.melocoton.auth.application.port.UserAccountRepository
import ar.melocoton.auth.domain.AssistantTeacherNotFound
import ar.melocoton.auth.domain.UserAccount
import ar.melocoton.shared.domain.Role
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** CU-02 D1a, RF-53: deactivates an assistant teacher. Nothing is deleted; their sessions stop working (RF-45). */
@Service
class DeactivateAssistantTeacher(private val accounts: UserAccountRepository) {

    @Transactional
    fun deactivate(id: Long): UserAccount {
        // Any other role is reported as missing: this endpoint never reveals or touches other accounts.
        val account = accounts.lockById(id)?.takeIf { it.role == Role.ASSISTANT_TEACHER }
            ?: throw AssistantTeacherNotFound()
        account.deactivate()
        accounts.saveActive(account)
        return account
    }
}
