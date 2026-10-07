package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.RecordingAccountStorage
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.SmtpClient
import app.sterna.core.jmap.Jmap
import app.sterna.core.jmap.JmapAuth
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.OAuthClient
import app.sterna.core.jmap.OAuthMetadata
import app.sterna.core.jmap.OAuthTokens
import app.sterna.core.jmap.OpenIdDiscovery
import app.sterna.core.jmap.jmapDiscoveryClient
import app.sterna.core.jmap.model.JmapSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The three OAuth entries of `MailAccounts` that name a client, run: the authorization URL, the
 * code exchange and the account written to the store. Each takes the client id as an argument
 * since a sign-in can be at an issuer the user typed (#249), which knows her client id and no
 * other, and each keeps Sterna's own as its default for the servers that were discovered.
 *
 * The exchange is posted to a local server and read back off the wire; the store writes down
 * what it was handed. Every expected value is written out, none is computed from the constants
 * the code under test reads.
 *
 * What this does not cover:
 * - Who passes the typed client id in. That is `OAuthCodeSignIn`, which no JVM test can build,
 *   and `OidcWiringTest` reads its text instead.
 * - The refresh. It reads the two fields this test sees written, and is not run here.
 * - The real store: `RecordingAccountStorage` stores nothing, so what `addOAuth` does with a
 *   client id is not judged.
 */
class OAuthClientIdFollowsTheSignInTest {

    private lateinit var server: MockWebServer

    private val requests = mutableListOf<Seen>()

    private data class Seen(val path: String, val authorization: String?, val body: String)

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                requests += Seen(path, request.getHeader("Authorization"), request.body.readUtf8())
                val body = when (path) {
                    "/idp/token" -> """{"access_token":"ACCESS-1","refresh_token":"REFRESH-1","expires_in":600}"""
                    "/.well-known/jmap" -> Json.encodeToString(
                        JmapSession.serializer(),
                        JmapSession(
                            apiUrl = server.url("/jmap/api").toString(),
                            primaryAccounts = mapOf(Jmap.MAIL_CAPABILITY to "u1"),
                        ),
                    )
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        server.start()
    }

    @After fun stop() {
        server.shutdown()
    }

    /** A store that can hand back the account it was just told to add, which the add reads next. */
    private class StoreKnowingTheAdded : RecordingAccountStorage() {
        override fun credentials(id: String): AccountCredentials? {
            super.credentials(id)
            return AccountCredentials(server = "unused", username = "alex@example.test", password = "", id = id)
        }
    }

    private val store = StoreKnowingTheAdded()

    /** The first sync is answered, not run; no session is warm, so nothing is reconciled. */
    private class HostAnsweringTheFirstSync : MailAccountHost {
        override suspend fun jmapSession(credentials: AccountCredentials): MailRepository.JmapAccountSession =
            error("no path here needs the repository's session")

        override suspend fun jmapAuth(credentials: AccountCredentials): JmapAuth =
            error("no path here builds an auth of its own")

        override suspend fun firstSync(credentials: AccountCredentials): MailboxMeta =
            MailboxMeta(accountName = "Alex", mailboxId = "mb-inbox", mailboxName = "Inbox", unreadCount = 0)

        override fun cachedJmapSession(): MailRepository.JmapAccountSession? = null
    }

    private inline fun <reified T> untouched(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, called, _ ->
            error("${T::class.simpleName}.${called.name} was not expected here.")
        } as T

    private fun accounts() = MailAccounts(
        client = JmapClient(),
        discoveryClient = jmapDiscoveryClient(),
        imap = ImapMailService(
            ImapClient(), SmtpClient(), OAuthTokenRefresher(OAuthClient(), store),
            pendingExpunges = InMemoryPendingExpunges(),
        ),
        emailDao = untouched(),
        emailFtsDao = untouched(),
        emailBodyDao = untouched(),
        mailboxDao = untouched(),
        snoozedDao = untouched(),
        serverContactDao = untouched(),
        accountStore = store,
        oauthClient = OAuthClient(),
        bgScope = CoroutineScope(Dispatchers.Unconfined),
        host = HostAnsweringTheFirstSync(),
    )

    /** An issuer's document: its endpoints live on the issuer, which is not the mail server. */
    private val issuerMetadata = OAuthMetadata(
        issuer = "https://idp.example.org/realms/mail",
        authorizationEndpoint = "https://idp.example.org/realms/mail/authorize",
        tokenEndpoint = "https://idp.example.org/realms/mail/token",
    )

    private fun query(url: String, name: String): String? = url.toHttpUrl().queryParameter(name)

    private fun form(body: String): Map<String, String> =
        body.split('&').filter { it.isNotEmpty() }.associate {
            val (k, v) = it.split('=', limit = 2)
            java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
        }

    @Test fun `the authorization URL carries the client id and the scope it is given`() {
        val url = accounts().authorizationUrl(
            metadata = issuerMetadata,
            redirectUri = "app.sterna://oauth",
            state = "STATE-1",
            codeChallenge = "CHALLENGE-1",
            clientId = "typed-client",
            scope = OpenIdDiscovery.SCOPE,
        )

        assertEquals("typed-client", query(url, "client_id"))
        assertEquals("openid email offline_access", query(url, "scope"))
    }

    @Test fun `left to its defaults the authorization URL is still Sterna's own`() {
        val url = accounts().authorizationUrl(issuerMetadata, "app.sterna://oauth", "STATE-1", "CHALLENGE-1")

        assertEquals("sterna", query(url, "client_id"))
        assertEquals(
            "urn:ietf:params:jmap:core urn:ietf:params:jmap:mail urn:ietf:params:jmap:submission offline_access",
            query(url, "scope"),
        )
    }

    @Test fun `the code exchange posts the client id it is given`() {
        val metadata = issuerMetadata.copy(tokenEndpoint = server.url("/idp/token").toString())

        val tokens = runBlocking {
            accounts().exchangeCode(
                metadata = metadata,
                code = "CODE-42",
                redirectUri = "app.sterna://oauth",
                codeVerifier = "VERIFIER-1",
                clientId = "typed-client",
            )
        }

        assertEquals("ACCESS-1", tokens.accessToken)
        assertEquals("typed-client", form(requests.single().body)["client_id"])
    }

    @Test fun `left to its default the code exchange posts Sterna's own client id`() {
        val metadata = issuerMetadata.copy(tokenEndpoint = server.url("/idp/token").toString())

        runBlocking { accounts().exchangeCode(metadata, "CODE-42", "app.sterna://oauth", "VERIFIER-1") }

        assertEquals("sterna", form(requests.single().body)["client_id"])
    }

    private val tokens = OAuthTokens(accessToken = "ACCESS-1", refreshToken = "REFRESH-1", expiresIn = 600)

    private fun jmapHost() = server.url("/").toString().removeSuffix("/")

    /**
     * The refresh reads these two fields and nothing else, so a typed issuer's account must be
     * written with the issuer's token endpoint and the client id its tokens were issued to.
     */
    @Test fun `the account is stored with the client id it is given and the issuer's token endpoint`() {
        val created = runBlocking {
            accounts().addOAuthAccount(
                host = jmapHost(),
                email = "alex@example.test",
                metadata = issuerMetadata,
                tokens = tokens,
                accountName = "Work",
                clientId = "typed-client",
            )
        }

        val stored = store.args("addOAuth")
        assertEquals("typed-client", stored["clientId"])
        assertEquals("https://idp.example.org/realms/mail/token", stored["tokenEndpoint"])
        assertEquals("the JMAP host, never the issuer", jmapHost(), stored["server"])
        assertEquals("alex@example.test", stored["username"])
        assertEquals(true, created)
        assertEquals(
            "the access token went to the JMAP host as a Bearer credential, and nowhere else",
            listOf(Seen("/.well-known/jmap", "Bearer ACCESS-1", "")),
            requests,
        )
    }

    @Test fun `left to its default the account is stored with Sterna's own client id`() {
        runBlocking {
            accounts().addOAuthAccount(jmapHost(), "alex@example.test", issuerMetadata, tokens, "Work")
        }

        assertEquals("sterna", store.args("addOAuth")["clientId"])
    }
}
