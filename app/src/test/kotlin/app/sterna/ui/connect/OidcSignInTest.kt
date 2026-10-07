package app.sterna.ui.connect

import app.sterna.R
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.jmap.OAuthMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Executes the decisions of a sign-in at an issuer the user typed (#249); it reads no source file.
 * Every probe is a lambda that writes down that it ran, so "nothing was asked" is observed and
 * not inferred from a return value.
 */
class OidcSignInTest {

    private val issuerMetadata = OAuthMetadata(
        issuer = "https://idp.example.org",
        authorizationEndpoint = "https://idp.example.org/authorize",
        tokenEndpoint = "https://idp.example.org/token",
    )

    // ---- The client id, before anything leaves ----

    @Test fun `without a client id nothing is asked of the issuer`() = runBlocking {
        listOf("", "   ", "\t\n").forEach { blank ->
            val asked = mutableListOf<String>()

            val verdict = issuerSignInFor("https://idp.example.org", blank) { asked += it; issuerMetadata }

            assertEquals(IssuerSignIn.NoClientId, verdict)
            assertEquals("the issuer was asked for its configuration: $asked", emptyList<String>(), asked)
        }
    }

    @Test fun `a typed client id is carried trimmed, with the issuer's own document`() = runBlocking {
        val asked = mutableListOf<String>()

        val verdict = issuerSignInFor("idp.example.org", "  typed-client \n") { asked += it; issuerMetadata }

        assertEquals(IssuerSignIn.Ready(issuerMetadata, "typed-client"), verdict)
        assertEquals("the issuer is asked once, as it was typed", listOf("idp.example.org"), asked)
    }

    @Test fun `an issuer with no usable configuration starts no sign-in`() = runBlocking {
        assertEquals(
            IssuerSignIn.NoConfiguration,
            issuerSignInFor("idp.example.org", "typed-client") { null },
        )
    }

    // ---- Which host is asked for a JMAP session ----

    private val neverDiscovered: suspend () -> MailRepository.DiscoveryResult =
        { error("a typed JMAP server is not second-guessed by autodiscovery") }

    @Test fun `a typed JMAP server is the host, and nothing is discovered`() = runBlocking {
        assertEquals(
            JmapHostChoice.Found("mail.example.com"),
            jmapHostAfterSignIn("mail.example.com", neverDiscovered),
        )
        assertEquals(
            JmapHostChoice.Found("mail.example.com"),
            jmapHostAfterSignIn("  mail.example.com \n", neverDiscovered),
        )
    }

    @Test fun `with no server typed the host is the one found from the address`() = runBlocking {
        var probes = 0

        val choice = jmapHostAfterSignIn("  ") {
            probes++
            MailRepository.DiscoveryResult.Found("jmap.example.com")
        }

        assertEquals(JmapHostChoice.Found("jmap.example.com"), choice)
        assertEquals(1, probes)
    }

    /**
     * No host is not a blank host: `Jmap.sessionUrlFor("")` is a blank URL, and a caller handed
     * a blank host would go on to add an account against it.
     */
    @Test fun `with no server typed and none found there is no server`() = runBlocking {
        assertEquals(JmapHostChoice.NoServer, jmapHostAfterSignIn("") { MailRepository.DiscoveryResult.NotFound })
        assertEquals(JmapHostChoice.NoServer, jmapHostAfterSignIn("") { MailRepository.DiscoveryResult.Found("  ") })
    }

    /** The mail server is there and says no: not the same fact as there being none. */
    @Test fun `a mail server refusing the token is not a server that was not found`() = runBlocking {
        assertEquals(
            JmapHostChoice.TokenRefused,
            jmapHostAfterSignIn("") { MailRepository.DiscoveryResult.BadCredentials },
        )
    }

    @Test fun `each way of having no host gets its own sentence`() {
        assertEquals(R.string.connect_oidc_no_server, noJmapHostSentence(JmapHostChoice.NoServer))
        assertEquals(R.string.connect_oidc_token_refused, noJmapHostSentence(JmapHostChoice.TokenRefused))
    }

    // ---- The client id, from the request to the token endpoint ----

    private fun request(clientId: String) = PendingAuthorization(
        state = "STATE-1",
        codeVerifier = "VERIFIER-1",
        host = "",
        metadata = issuerMetadata,
        email = "user@example.com",
        accountName = "Work",
        clientId = clientId,
    )

    @Test fun `the exchange posts the client id of the request being redeemed`() {
        val pending = request(clientId = "typed-client")
        val verdict = redirectVerdict(pending, "STATE-1", "CODE-42", null) as RedirectVerdict.Exchange

        assertEquals("typed-client", tokenExchangeFor(verdict, "app.sterna://oauth").clientId)
    }

    /** Two requests differing only by their client id do not exchange alike. */
    @Test fun `the client id is not a constant of the exchange`() {
        fun exchanged(clientId: String) = tokenExchangeFor(
            redirectVerdict(request(clientId), "STATE-1", "CODE-42", null) as RedirectVerdict.Exchange,
            "app.sterna://oauth",
        ).clientId

        assertEquals(listOf("client-a", "client-b"), listOf(exchanged("client-a"), exchanged("client-b")))
    }
}
