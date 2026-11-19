package ar.melocoton.auth.application

import ar.melocoton.auth.application.port.UserAccountRepository
import ar.melocoton.shared.application.port.SessionUserLookup
import ar.melocoton.shared.domain.SessionUser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Runs on every authenticated request: a deactivated user or a token issued under an earlier
 * session cut-off (before a password change) is rejected at once (DI-28; RN-09, RF-45, D-30).
 */
@Service
class ValidateSession(private val accounts: UserAccountRepository) : SessionUserLookup {

    @Transactional(readOnly = true)
    override fun validSession(userId: Long, sessionsValidFrom: Instant): SessionUser? =
        accounts.findById(userId)
            ?.takeIf { it.acceptsSession(sessionsValidFrom) }
            ?.let { SessionUser(it.id, it.role, it.sessionsValidFrom) }
}
