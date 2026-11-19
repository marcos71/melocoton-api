package ar.melocoton.auth.application

import ar.melocoton.auth.application.port.SessionToken
import ar.melocoton.auth.application.port.SessionTokens
import ar.melocoton.shared.domain.Role
import ar.melocoton.shared.domain.SessionUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class RefreshSessionTest {

    private val now = Instant.parse("2026-11-16T13:00:00Z")

    @Test
    fun `DI-28 the renewed token keeps the cut-off of the session it renews and lasts 30 days from now`() {
        val cutOff = Instant.parse("2026-11-01T10:00:00.123456Z")
        val tokens = SessionTokens { userId, role, sessionsValidFrom, at ->
            SessionToken("$userId-$role-$sessionsValidFrom", at.plus(Duration.ofDays(30)))
        }

        val token = RefreshSession(tokens, Clock.fixed(now, ZoneOffset.UTC))
            .refresh(SessionUser(7, Role.LEAD_TEACHER, cutOff))

        // Taking the account's current cut-off instead would revive a session a password change closed.
        assertThat(token).isEqualTo(SessionToken("7-LEAD_TEACHER-$cutOff", now.plus(Duration.ofDays(30))))
    }
}
