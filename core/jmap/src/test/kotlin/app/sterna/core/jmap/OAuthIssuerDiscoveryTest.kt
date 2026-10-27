package app.sterna.core.jmap

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The shipped code only fetches over https and `MockWebServer` speaks cleartext here, so the
 * injected discovery client carries one interceptor handing `https://idm.example.com` to the
 * local server. Every decision runs outside it, on the typed https issuer. The client is
 * otherwise `JmapClient.defaultHttpClient()`: the redirect policy under test is the shipped one.
 */
class OAuthIssuerDiscoveryTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    /** Every call the client was asked to make, whatever its host: the local server sees one only. */
    private val attempted = AtomicInteger()

    private val client: OAuthClient by lazy {
        val routed = JmapClient.defaultHttpClient().newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                attempted.incrementAndGet()
                if (request.url.isHttps && request.url.host == ISSUER_HOST) {
                    val local = request.url.newBuilder()
                        .scheme("http")
                        .host(server.hostName)
                        .port(server.port)
                        .build()
                    chain.proceed(request.newBuilder().url(local).build())
                } else {
                    chain.proceed(request)
                }
            }
            .build()
        OAuthClient(routed, defaultOAuthHttpClient(), JmapClient.DefaultJson)
    }

    private fun document(
        issuer: String? = ISSUER,
        authorization: String? = "$ISSUER/authorize",
        token: String? = "$ISSUER/token",
        device: String? = null,
        registration: String? = null,
    ): MockResponse {
        val fields = listOfNotNull(
            issuer?.let { "\"issuer\":\"$it\"" },
            authorization?.let { "\"authorization_endpoint\":\"$it\"" },
            token?.let { "\"token_endpoint\":\"$it\"" },
            device?.let { "\"device_authorization_endpoint\":\"$it\"" },
            registration?.let { "\"registration_endpoint\":\"$it\"" },
            "\"jwks_uri\":\"$ISSUER/jwks\"",
            "\"response_types_supported\":[\"code\"]",
        )
        return MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(fields.joinToString(",", "{", "}"))
    }

    // ---- Where the request goes ----

    @Test fun theRequestGoesToTheWellKnownPathAppendedAfterTheIssuersOwnPath() = runBlocking {
        server.enqueue(document())

        client.discoverAtIssuer(ISSUER)

        assertEquals(
            "OpenID Connect Discovery appends the well-known path, RFC 8414 would insert it",
            "/oauth2/openid/monclient/.well-known/openid-configuration",
            server.takeRequest().path,
        )
    }

    @Test fun anIssuerTypedWithoutASchemeIsAskedOverHttps() = runBlocking {
        server.enqueue(document())

        val metadata = client.discoverAtIssuer("idm.example.com/oauth2/openid/monclient")

        assertNotNull("a bare host and path means https, as every other server field does", metadata)
        assertEquals(
            "/oauth2/openid/monclient/.well-known/openid-configuration",
            server.takeRequest().path,
        )
    }

    @Test fun aCleartextIssuerIsRefusedBeforeAnyRequestLeaves() = runBlocking {
        server.enqueue(document())

        val metadata = client.discoverAtIssuer(server.url("/oauth2/openid/monclient").toString())

        assertNull("an http issuer is unusable", metadata)
        assertEquals("and nothing may have been sent to find that out", 0, server.requestCount)
    }

    @Test fun anIssuerThatIsNoUrlIsRefusedBeforeAnyRequestLeaves() = runBlocking {
        assertNull(client.discoverAtIssuer(""))
        assertNull(client.discoverAtIssuer("   "))
        assertNull(client.discoverAtIssuer("https://"))
        assertNull(client.discoverAtIssuer("ftp://idm.example.com/x"))
        assertEquals(0, server.requestCount)
    }

    @Test fun aCleartextSchemeWrittenWithOneSlashIsNotTakenForAHost() = runBlocking {
        val metadata = client.discoverAtIssuer("http:/idm.example.com")

        assertNull(metadata)
        assertEquals("no call may leave for a host named after the scheme", 0, attempted.get())
        assertNull(OpenIdDiscovery.issuerOf("http:/idm.example.com"))
        assertNull(OpenIdDiscovery.configurationUrlFor("http:/idm.example.com/realm"))
    }

    // ---- A conforming document ----

    @Test fun aConformingDocumentIsUsable() = runBlocking {
        server.enqueue(document())

        val metadata = client.discoverAtIssuer(ISSUER)

        assertEquals("$ISSUER/authorize", metadata?.authorizationEndpoint)
        assertEquals("$ISSUER/token", metadata?.tokenEndpoint)
        assertNull(metadata?.deviceAuthorizationEndpoint)
        assertTrue(metadata?.supportsCodeFlow == true)
        assertFalse(metadata?.supportsDeviceFlow == true)
        assertFalse("nothing was named elsewhere", metadata?.endpointsOffDomain == true)
    }

    @Test fun aConformingDocumentWithADeviceEndpointKeepsIt() = runBlocking {
        server.enqueue(document(device = "$ISSUER/device"))

        val metadata = client.discoverAtIssuer(ISSUER)

        assertEquals("$ISSUER/device", metadata?.deviceAuthorizationEndpoint)
        assertTrue(metadata?.supportsCodeFlow == true)
        assertTrue(metadata?.supportsDeviceFlow == true)
        assertFalse(metadata?.endpointsOffDomain == true)
    }

    /** The client id is typed on this path: an issuer's own registration endpoint is not used. */
    @Test fun aRegistrationEndpointOnTheTypedHostIsDropped() = runBlocking {
        server.enqueue(document(registration = "$ISSUER/register"))

        val metadata = client.discoverAtIssuer(ISSUER)

        assertNotNull("the document is usable otherwise", metadata)
        assertNull(metadata?.registrationEndpoint)
        assertFalse(metadata?.asksRegistration == true)
    }

    @Test fun endpointsOnASubdomainOfTheTypedHostAreKept() = runBlocking {
        server.enqueue(
            document(
                authorization = "https://login.idm.example.com/authorize",
                token = "https://api.idm.example.com/token",
            ),
        )

        val metadata = client.discoverAtIssuer(ISSUER)

        assertEquals("https://login.idm.example.com/authorize", metadata?.authorizationEndpoint)
        assertEquals("https://api.idm.example.com/token", metadata?.tokenEndpoint)
        assertFalse(metadata?.endpointsOffDomain == true)
    }

    // ---- The issuer the document claims (OpenID Connect Discovery §4.3) ----

    @Test fun aDocumentClaimingAnotherIssuerOnTheSameHostIsRejected() = runBlocking {
        server.enqueue(document(issuer = "https://idm.example.com/oauth2/openid/autreclient"))

        assertNull(client.discoverAtIssuer(ISSUER))
    }

    @Test fun aDocumentClaimingAnIssuerOnAnotherHostIsRejected() = runBlocking {
        server.enqueue(document(issuer = "https://ailleurs.example/oauth2/openid/monclient"))

        assertNull(client.discoverAtIssuer(ISSUER))
    }

    @Test fun aDocumentClaimingTheSameIssuerOverCleartextIsRejected() = runBlocking {
        server.enqueue(document(issuer = "http://idm.example.com/oauth2/openid/monclient"))

        assertNull(client.discoverAtIssuer(ISSUER))
    }

    @Test fun aDocumentWithoutAnIssuerIsRejected() = runBlocking {
        server.enqueue(document(issuer = null))

        assertNull(client.discoverAtIssuer(ISSUER))
    }

    @Test fun theIssuerMatchesWhenOnlyTheDocumentCarriesATrailingSlash() = runBlocking {
        server.enqueue(document(issuer = "$ISSUER/"))

        assertNotNull(client.discoverAtIssuer(ISSUER))
    }

    @Test fun theIssuerMatchesWhenOnlyTheTypedOneCarriesATrailingSlash() = runBlocking {
        server.enqueue(document())

        assertNotNull(client.discoverAtIssuer("$ISSUER/"))
        assertEquals(
            "and the slash is not doubled on the wire",
            "/oauth2/openid/monclient/.well-known/openid-configuration",
            server.takeRequest().path,
        )
    }

    @Test fun theIssuerMatchesAcrossHostCaseAndTheDefaultPort() = runBlocking {
        server.enqueue(document())

        assertNotNull(client.discoverAtIssuer("https://IDM.Example.COM:443/oauth2/openid/monclient"))
    }

    @Test fun theIssuersPathIsComparedExactly() = runBlocking {
        server.enqueue(document(issuer = "https://idm.example.com/oauth2/openid/MonClient"))

        assertNull("a path is case-sensitive, only the host is not", client.discoverAtIssuer(ISSUER))
    }

    // ---- The host guard: the typed host and nothing else ----

    @Test fun aTokenEndpointOnAnotherHostMakesTheDocumentUnusable() = runBlocking {
        server.enqueue(document(token = "https://ailleurs.example/token"))

        assertNull(client.discoverAtIssuer(ISSUER))
    }

    @Test fun anAuthorizationEndpointOnAnotherHostMakesTheDocumentUnusable() = runBlocking {
        server.enqueue(document(authorization = "https://ailleurs.example/authorize"))

        assertNull(client.discoverAtIssuer(ISSUER))
    }

    @Test fun aHostThatMerelyEndsLikeTheTypedOneIsAnotherHost() = runBlocking {
        server.enqueue(document(token = "https://evilidm.example.com/token"))

        assertNull(client.discoverAtIssuer(ISSUER))
    }

    @Test fun theParentDomainOfTheTypedHostIsAnotherHost() = runBlocking {
        server.enqueue(document(token = "https://example.com/token"))

        assertNull("the typed host owns its subdomains, not its parent", client.discoverAtIssuer(ISSUER))
    }

    @Test fun aCleartextTokenEndpointOnTheTypedHostMakesTheDocumentUnusable() = runBlocking {
        server.enqueue(document(token = "http://idm.example.com/oauth2/openid/monclient/token"))

        assertNull(client.discoverAtIssuer(ISSUER))
    }

    @Test fun aDocumentWithoutAnAuthorizationEndpointIsUnusable() = runBlocking {
        server.enqueue(document(authorization = null, device = "$ISSUER/device"))

        assertNull("the device endpoint does not make up for it", client.discoverAtIssuer(ISSUER))
    }

    @Test fun aDocumentWithoutATokenEndpointIsUnusable() = runBlocking {
        server.enqueue(document(token = null))

        assertNull(client.discoverAtIssuer(ISSUER))
    }

    @Test fun aDeviceEndpointOnAnotherHostIsBlankedAndTheFactIsCarried() = runBlocking {
        server.enqueue(document(device = "https://ailleurs.example/device"))

        val metadata = client.discoverAtIssuer(ISSUER)

        assertNotNull("the device endpoint does not count towards usable", metadata)
        assertNull(metadata?.deviceAuthorizationEndpoint)
        assertTrue(metadata?.supportsCodeFlow == true)
        assertTrue("another host was named", metadata?.endpointsOffDomain == true)
    }

    @Test fun aCleartextDeviceEndpointOnTheTypedHostIsBlankedWithoutTheFact() = runBlocking {
        server.enqueue(document(device = "http://idm.example.com/device"))

        val metadata = client.discoverAtIssuer(ISSUER)

        assertNotNull(metadata)
        assertNull(metadata?.deviceAuthorizationEndpoint)
        assertFalse("cleartext on our own host names nobody else", metadata?.endpointsOffDomain == true)
    }

    // ---- An answer that is no document ----

    @Test fun aNon200AnswerIsNull() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}"""))

        assertNull(client.discoverAtIssuer(ISSUER))
        assertEquals("the request did leave, the null is the answer's", 1, server.requestCount)
    }

    @Test fun aValidDocumentUnderAnErrorStatusIsNull() = runBlocking {
        server.enqueue(document().setResponseCode(500))

        assertNull("the status decides before the body is read", client.discoverAtIssuer(ISSUER))
        assertEquals(1, server.requestCount)
    }

    @Test fun anUnreadableBodyIsNull() = runBlocking {
        server.enqueue(MockResponse().setBody("<html><body>Sign in</body></html>"))

        assertNull(client.discoverAtIssuer(ISSUER))
        assertEquals(1, server.requestCount)
    }

    // ---- Redirects: followed, and they move no trust ----

    @Test fun aDocumentBehindARedirectIsJudgedAgainstTheTypedHostNotTheOneThatServedIt() = runBlocking {
        val second = MockWebServer().apply { start() }
        try {
            val elsewhere = second.url("/realm").toString().replace("http://", "https://")
            second.enqueue(
                document(
                    issuer = elsewhere,
                    authorization = "$elsewhere/authorize",
                    token = "$elsewhere/token",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(307)
                    .setHeader("Location", second.url("/realm/.well-known/openid-configuration").toString()),
            )

            val metadata = client.discoverAtIssuer(ISSUER)

            assertEquals("the redirect itself is followed", 1, second.requestCount)
            assertNull("but the host it led to gains nothing by it", metadata)
        } finally {
            second.shutdown()
        }
    }

    @Test fun theHostARedirectLedToOwnsNoEndpoint() = runBlocking {
        val second = MockWebServer().apply { start() }
        try {
            second.enqueue(document(token = "https://${second.hostName}:${second.port}/token"))
            server.enqueue(
                MockResponse().setResponseCode(307)
                    .setHeader("Location", second.url("/elsewhere/openid-configuration").toString()),
            )

            val metadata = client.discoverAtIssuer(ISSUER)

            assertEquals("the redirect itself is followed", 1, second.requestCount)
            assertNull("the issuer is the typed one, the token endpoint is the server's own", metadata)
        } finally {
            second.shutdown()
        }
    }

    @Test fun aDocumentBehindARedirectIsKeptWhenItStillDescribesTheTypedIssuer() = runBlocking {
        val second = MockWebServer().apply { start() }
        try {
            second.enqueue(document())
            server.enqueue(
                MockResponse().setResponseCode(307)
                    .setHeader("Location", second.url("/elsewhere/openid-configuration").toString()),
            )

            val metadata = client.discoverAtIssuer(ISSUER)

            assertEquals(1, second.requestCount)
            assertEquals("$ISSUER/token", metadata?.tokenEndpoint)
            assertTrue(metadata?.supportsCodeFlow == true)
        } finally {
            second.shutdown()
        }
    }

    // ---- The URL builder, with no network ----

    @Test fun url_appendsAfterTheIssuersPath() {
        assertEquals(
            "https://idm.example.com/oauth2/openid/monclient/.well-known/openid-configuration",
            OpenIdDiscovery.configurationUrlFor("https://idm.example.com/oauth2/openid/monclient"),
        )
        assertEquals(
            "https://idm.example.com/.well-known/openid-configuration",
            OpenIdDiscovery.configurationUrlFor("https://idm.example.com"),
        )
    }

    @Test fun url_dropsTrailingSlashesAndSurroundingSpace() {
        val expected = "https://idm.example.com/realms/a/.well-known/openid-configuration"
        assertEquals(expected, OpenIdDiscovery.configurationUrlFor("https://idm.example.com/realms/a/"))
        assertEquals(expected, OpenIdDiscovery.configurationUrlFor("  https://idm.example.com/realms/a  "))
        assertEquals(
            "https://idm.example.com/.well-known/openid-configuration",
            OpenIdDiscovery.configurationUrlFor("https://idm.example.com/"),
        )
    }

    @Test fun url_defaultsAMissingSchemeToHttps() {
        assertEquals(
            "https://idm.example.com/realms/a/.well-known/openid-configuration",
            OpenIdDiscovery.configurationUrlFor("idm.example.com/realms/a"),
        )
        assertEquals(
            "https://idm.example.com:8443/.well-known/openid-configuration",
            OpenIdDiscovery.configurationUrlFor("idm.example.com:8443"),
        )
        assertEquals(
            "a port is not a scheme",
            "https://idm.example.com:8443/realm/.well-known/openid-configuration",
            OpenIdDiscovery.configurationUrlFor("idm.example.com:8443/realm"),
        )
    }

    @Test fun url_normalisesHostCaseAndTheDefaultPortButNotThePath() {
        assertEquals(
            "https://idm.example.com/Realms/A/.well-known/openid-configuration",
            OpenIdDiscovery.configurationUrlFor("HTTPS://IDM.Example.com:443/Realms/A"),
        )
    }

    @Test fun url_takesAPastedConfigurationUrlForItsIssuer() {
        assertEquals(
            "https://idm.example.com/realms/a/.well-known/openid-configuration",
            OpenIdDiscovery.configurationUrlFor("https://idm.example.com/realms/a/.well-known/openid-configuration"),
        )
        assertEquals("https://idm.example.com/realms/a", OpenIdDiscovery.issuerOf("https://idm.example.com/realms/a/.well-known/openid-configuration"))
        assertEquals(
            "a trailing slash after the well-known path changes nothing",
            "https://idm.example.com/realms/a/.well-known/openid-configuration",
            OpenIdDiscovery.configurationUrlFor("https://idm.example.com/realms/a/.well-known/openid-configuration/"),
        )
    }

    @Test fun url_refusesWhatIsNotAnHttpsIssuer() {
        assertNull("cleartext", OpenIdDiscovery.configurationUrlFor("http://idm.example.com/realms/a"))
        assertNull("cleartext, whatever the case", OpenIdDiscovery.configurationUrlFor("HTTP://idm.example.com"))
        assertNull("empty", OpenIdDiscovery.configurationUrlFor(""))
        assertNull("blank", OpenIdDiscovery.configurationUrlFor("   "))
        assertNull("no host", OpenIdDiscovery.configurationUrlFor("https://"))
        assertNull("another scheme", OpenIdDiscovery.configurationUrlFor("ftp://idm.example.com"))
        assertNull("a space in the host", OpenIdDiscovery.configurationUrlFor("https://idm example.com"))
    }

    @Test fun url_refusesAnIssuerCarryingAQueryAFragmentOrCredentials() {
        assertNull(OpenIdDiscovery.configurationUrlFor("https://idm.example.com/realms/a?tenant=b"))
        assertNull(OpenIdDiscovery.configurationUrlFor("https://idm.example.com/realms/a#frag"))
        assertNull(OpenIdDiscovery.configurationUrlFor("https://someone:secret@idm.example.com/realms/a"))
        assertNull(OpenIdDiscovery.configurationUrlFor("https://someone@idm.example.com/realms/a"))
    }

    @Test fun issuer_isTheCanonicalFormTheDocumentIsComparedTo() {
        assertEquals("https://idm.example.com", OpenIdDiscovery.issuerOf("idm.example.com/"))
        assertEquals("https://idm.example.com/realms/a", OpenIdDiscovery.issuerOf("https://IDM.example.com:443/realms/a/"))
        assertNull(OpenIdDiscovery.issuerOf("http://idm.example.com"))
    }

    private companion object {
        const val ISSUER_HOST = "idm.example.com"
        const val ISSUER = "https://$ISSUER_HOST/oauth2/openid/monclient"
    }
}
