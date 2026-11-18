package ar.melocoton.auth.application

import ar.melocoton.auth.application.port.UserAccountRepository
import ar.melocoton.auth.domain.UserAccount
import ar.melocoton.shared.domain.Role
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** CU-02 D1: the assistant teachers, optionally only the active or the inactive ones. */
@Service
class ListAssistantTeachers(private val accounts: UserAccountRepository) {

    @Transactional(readOnly = true)
    fun list(active: Boolean?): List<UserAccount> = accounts.findAllByRole(Role.ASSISTANT_TEACHER, active)
}
