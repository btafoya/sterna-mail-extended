package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [oauthServerToStore]: which host an OAuth sign-in writes into the account.
 *
 * The defect this pins (#55). Adding ONE mailbox two ways gave two accounts: with a password the
 * server is resolved by probing `Jmap.autodiscoverHosts` by rank, which keeps the apex
 * (`kagimail.com`); with OAuth the candidate list drops the apex, so the OAuth document comes from
 * `mail.kagimail.com` and that is what used to be stored. The stored host is half of the account's
 * identity, so the duplicate guard never saw the two adds as the same mailbox — two inboxes, two
 * badges, two sets of notifications for one account.
 *
 * The values below are LITERAL on both sides. Recomputing the rule here (asking the result
 * whether it is `Found`, re-deriving the host) would be a copy of the decision: the shipped
 * condition could be inverted and this file would stay green.
 */
class OAuthServerToStoreTest {

    /** The whole point: a resolution that succeeded outranks the host the tokens came from. */
    @Test fun `a resolved server is what gets stored, not the OAuth document's host`() {
        assertEquals(
            "masto.top",
            oauthServerToStore(MailRepository.DiscoveryResult.Found("masto.top"), "mail.example.com"),
        )
    }

    /**
     * And a silent resolution must not cost the user the account. Autodiscovery can answer
     * nothing for reasons that have nothing to do with this sign-in (the budget ran out, the apex
     * is behind a captive portal, the domain simply has no `/.well-known/jmap`) — the tokens are
     * already granted and valid at that point, so the add goes through under the document's host,
     * exactly as it did before this function existed.
     */
    @Test fun `nothing found falls back on the host the tokens came from`() {
        assertEquals(
            "mail.example.com",
            oauthServerToStore(MailRepository.DiscoveryResult.NotFound, "mail.example.com"),
        )
    }

    /**
     * A 401/403 from a candidate says nothing about the bearer token this add already validated —
     * the apex is often the organisation's website. Falling back, never failing.
     */
    @Test fun `a credentials rejection during discovery falls back too`() {
        assertEquals(
            "mail.example.com",
            oauthServerToStore(MailRepository.DiscoveryResult.BadCredentials, "mail.example.com"),
        )
    }

    /**
     * A blank winner is not a host. `Found("")` stored as the server yields an account whose
     * session URL cannot be built, i.e. an account that can never sync and that the user can only
     * fix by typing everything in again.
     */
    @Test fun `a blank resolved server is not a host, and falls back`() {
        assertEquals(
            "mail.example.com",
            oauthServerToStore(MailRepository.DiscoveryResult.Found(""), "mail.example.com"),
        )
    }
}
