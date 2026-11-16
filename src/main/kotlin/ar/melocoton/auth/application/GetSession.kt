package ar.melocoton.auth.application

import ar.melocoton.auth.application.port.UserAccountRepository
import ar.melocoton.auth.domain.UserAccount
import ar.melocoton.shared.domain.SessionUser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** CU-01 step 3: who is logged in, so the PWA can build the navigation for the role (RF-02). */
@Service
class GetSession(private val accounts: UserAccountRepository) {

    @Transactional(readOnly = true)
    fun get(user: SessionUser): UserAccount =
        // The security filter just checked that this account exists and is active.
        checkNotNull(accounts.findById(user.userId)) { "Session user ${user.userId} not found" }
}
