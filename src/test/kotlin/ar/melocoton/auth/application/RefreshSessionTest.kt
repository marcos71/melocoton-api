package ar.melocoton.auth.application

import ar.melocoton.auth.application.port.SessionToken
import ar.melocoton.auth.application.port.SessionTokens
import ar.melocoton.shared.domain.Role
import ar.melocoton.shared.domain.SessionUser
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
        val renewed = SessionToken("renewed", now.plus(Duration.ofDays(30)))
        val tokens = mockk<SessionTokens>()
        every { tokens.issue(7, Role.LEAD_TEACHER, cutOff, now) } returns renewed

        val token = RefreshSession(tokens, Clock.fixed(now, ZoneOffset.UTC))
            .refresh(SessionUser(7, Role.LEAD_TEACHER, cutOff))

        // Taking the account's current cut-off instead would revive a session a password change closed.
        assertThat(token).isEqualTo(renewed)
        verify(exactly = 1) { tokens.issue(7, Role.LEAD_TEACHER, cutOff, now) }
        confirmVerified(tokens)
    }
}
