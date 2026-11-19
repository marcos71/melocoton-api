package ar.melocoton.auth.domain

import ar.melocoton.shared.domain.BusinessErrorKind
import ar.melocoton.shared.domain.Role
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class AssistantTeacherAccountTest {

    private val now = Instant.parse("2026-11-16T13:00:00.123456789Z")

    private fun account(active: Boolean) = UserAccount(
        id = 1, name = "Martín", email = "martin@test.local", phone = "1100000000", role = Role.ASSISTANT_TEACHER,
        passwordHash = "hash", active = active, failedLoginAttempts = 0, lockedUntil = null,
        sessionsValidFrom = now,
    )

    @Test
    fun `RF53_deactivatingAnActiveAccountLeavesItInactive`() {
        val account = account(active = true)

        account.deactivate()

        assertThat(account.active).isFalse()
        assertThat(account.acceptsSession(account.sessionsValidFrom)).isFalse()
    }

    @Test
    fun `RF53_deactivatingAnInactiveAccountIsAStatusConflict`() {
        assertThatThrownBy { account(active = false).deactivate() }
            .isInstanceOfSatisfying(AccountAlreadyInactive::class.java) {
                assertThat(it.code).isEqualTo("status_conflict")
                assertThat(it.kind).isEqualTo(BusinessErrorKind.CONFLICT)
            }
    }

    @Test
    fun `RF53_aNewAssistantTeacherIsActiveWithoutPasswordAndTrimmed`() {
        val new = NewAccount.assistantTeacher("  Martín ", " martin@test.local ", " 1100000000 ", now)

        assertThat(new.role).isEqualTo(Role.ASSISTANT_TEACHER)
        assertThat(new.passwordHash).isNull()
        assertThat(new.active).isTrue()
        assertThat(new.name).isEqualTo("Martín")
        assertThat(new.email).isEqualTo("martin@test.local")
        assertThat(new.phone).isEqualTo("1100000000")
        assertThat(new.sessionsValidFrom).isEqualTo(Instant.parse("2026-11-16T13:00:00.123456Z"))
    }
}
