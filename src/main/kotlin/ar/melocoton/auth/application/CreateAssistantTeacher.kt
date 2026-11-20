package ar.melocoton.auth.application

import ar.melocoton.auth.application.port.UserAccountRepository
import ar.melocoton.auth.domain.AccountInvited
import ar.melocoton.auth.domain.NewAccount
import ar.melocoton.auth.domain.UserAccount
import ar.melocoton.shared.application.port.DomainEvents
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** CU-02 D1, RF-53: the lead teacher invites an assistant teacher, who defines a password from the emailed link (D-11). */
@Service
class CreateAssistantTeacher(
    private val accounts: UserAccountRepository,
    private val events: DomainEvents,
    private val clock: Clock,
) {
    @Transactional
    fun create(name: String, email: String, phone: String): UserAccount {
        val now = clock.instant()
        val new = NewAccount.assistantTeacher(name, email, phone, now)
        // A taken email fails here with EmailAlreadyUsed: the unique index is the only check (DI-34).
        val account = accounts.create(new)

        events.publish(AccountInvited(account.id, now))
        return account
    }
}
