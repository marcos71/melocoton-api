package ar.melocoton.auth.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class AccountLinkTest {

    private val now = Instant.parse("2026-11-16T13:00:00Z")

    @Test
    fun `DI-29 an invitation lasts 7 days`() {
        val link = AccountLink.issue(1, AccountLinkPurpose.INVITATION, "hash", now)

        assertThat(link.createdAt).isEqualTo(now)
        assertThat(link.expiresAt).isEqualTo(now.plus(Duration.ofDays(7)))
        assertThat(link.usedAt).isNull()
    }

    @Test
    fun `DI-29 a password reset link lasts 1 hour`() {
        val link = AccountLink.issue(1, AccountLinkPurpose.PASSWORD_RESET, "hash", now)

        assertThat(link.expiresAt).isEqualTo(now.plus(Duration.ofHours(1)))
    }

    @Test
    fun `a link can be used until just before it expires`() {
        val link = AccountLink.issue(1, AccountLinkPurpose.PASSWORD_RESET, "hash", now)

        link.checkUsableAt(now.plus(Duration.ofHours(1)).minusMillis(1))
    }

    @Test
    fun `CU-01 2f an expired link is rejected`() {
        val link = AccountLink.issue(1, AccountLinkPurpose.PASSWORD_RESET, "hash", now)

        assertThatThrownBy { link.checkUsableAt(now.plus(Duration.ofHours(1))) }
            .isInstanceOf(LinkExpired::class.java)
            .hasMessage("El enlace venció o ya no sirve. Pedí uno nuevo con «Olvidé mi contraseña».")
    }

    @Test
    fun `DI-29 a used link is rejected even before it expires`() {
        val link = AccountLink(
            id = 1, userId = 1, purpose = AccountLinkPurpose.INVITATION, tokenHash = "hash",
            createdAt = now, expiresAt = now.plus(Duration.ofDays(7)), usedAt = now.plusSeconds(60),
        )

        assertThatThrownBy { link.checkUsableAt(now.plusSeconds(120)) }.isInstanceOf(LinkExpired::class.java)
    }
}
