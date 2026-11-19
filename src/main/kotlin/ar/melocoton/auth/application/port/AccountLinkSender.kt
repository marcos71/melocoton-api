package ar.melocoton.auth.application.port

import ar.melocoton.auth.domain.AccountLinkPurpose
import ar.melocoton.auth.domain.UserAccount

fun interface AccountLinkSender {
    /** Sends [account] the email with the link that carries [token]. */
    fun send(account: UserAccount, purpose: AccountLinkPurpose, token: String)
}
