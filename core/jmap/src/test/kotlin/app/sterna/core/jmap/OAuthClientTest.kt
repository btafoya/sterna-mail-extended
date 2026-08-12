package app.sterna.core.jmap

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OAuthClientTest {
    private lateinit var server: MockWebServer
    private val client = OAuthClient()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun metadata() = OAuthMetadata(
        issuer = server.url("/").toString(),
        tokenEndpoint = server.url("/auth/token").toString(),
        deviceAuthorizationEndpoint = server.url("/auth/device").toString(),
    )

    @Test fun startDeviceAuthorization_parsesAndSendsClientAndScope() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"device_code":"DC","user_code":"WDJB-MJHT",
                   "verification_uri":"https://srv/device",
                   "verification_uri_complete":"https://srv/device?code=WDJB-MJHT",
                   "expires_in":1800,"interval":5}""".trimIndent(),
            ),
        )

        val device = client.startDeviceAuthorization(metadata(), "sterna", "scope-a offline_access")

        assertEquals("DC", device.deviceCode)
        assertEquals("WDJB-MJHT", device.userCode)
        assertEquals(5, device.interval)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("client_id=sterna"))
        assertTrue(body.contains("scope=scope-a"))
    }

    @Test fun pollDeviceToken_pendingThenSuccess() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"authorization_pending"}"""))
        assertEquals(DeviceTokenResult.Pending, client.pollDeviceToken(metadata(), "DC", "sterna"))

        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"AT","refresh_token":"RT","expires_in":3600,"token_type":"Bearer"}""",
            ),
        )
        val result = client.pollDeviceToken(metadata(), "DC", "sterna")
        assertTrue(result is DeviceTokenResult.Success)
        assertEquals("AT", (result as DeviceTokenResult.Success).tokens.accessToken)
        assertEquals("RT", result.tokens.refreshToken)
    }

    @Test fun pollDeviceToken_slowDownAndDenied() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"slow_down"}"""))
        assertEquals(DeviceTokenResult.SlowDown, client.pollDeviceToken(metadata(), "DC", "sterna"))

        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"access_denied"}"""))
        val denied = client.pollDeviceToken(metadata(), "DC", "sterna")
        assertTrue(denied is DeviceTokenResult.Failed)
        assertEquals("access_denied", (denied as DeviceTokenResult.Failed).error)
    }

    @Test fun parseError_extractsErrorDescriptionAndAadsts() {
        val parsed = client.parseError(
            """{"error":"invalid_grant","error_description":"AADSTS90094: The grant requires admin permission."}""",
        )
        assertEquals("invalid_grant", parsed?.error)
        assertTrue(parsed?.description?.contains("admin permission") == true)
        assertEquals("AADSTS90094", parsed?.aadstsCode)
    }

    @Test fun parseError_noAadstsWhenAbsent() {
        val parsed = client.parseError(
            """{"error":"expired_token","error_description":"The code expired."}""",
        )
        assertEquals("expired_token", parsed?.error)
        assertNull(parsed?.aadstsCode)
        assertTrue(parsed?.description?.isNotEmpty() == true)
    }

    @Test fun parseError_returnsNullForNonError() {
        assertNull(client.parseError("""{"foo":"bar"}"""))
        assertNull(client.parseError("not json"))
    }

    @Test fun pollDeviceToken_failedCarriesDescriptionAndAadsts() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":"authorization_declined","error_description":"AADSTS650051: app not approved."}""",
            ),
        )
        val result = client.pollDeviceToken(metadata(), "DC", "sterna")
        assertTrue(result is DeviceTokenResult.Failed)
        result as DeviceTokenResult.Failed
        assertEquals("authorization_declined", result.error)
        assertEquals("AADSTS650051", result.aadstsCode)
        assertTrue(result.description.contains("not approved"))
    }

    @Test fun refresh_exchangesRefreshTokenForAccessToken() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"access_token":"AT2","refresh_token":"RT2","expires_in":7200}"""),
        )
        val tokens = client.refresh(server.url("/auth/token").toString(), "RT", "sterna")
        assertEquals("AT2", tokens.accessToken)
        assertEquals(7200, tokens.expiresIn)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("grant_type=refresh_token"))
        assertTrue(body.contains("refresh_token=RT"))
    }

    // ---- A malformed token response must not carry the tokens into the error ----
    //
    // kotlinx.serialization puts a SLICE OF THE OFFENDING JSON in its message, and for a token
    // endpoint that JSON is the access and refresh tokens themselves. That message travels:
    // refresh() -> OAuthTokenRefresher -> jmapAuth -> MailRepository.refresh ->
    // InboxViewModel's `t.message` -> the list's error banner.
    //
    // ⚠ The trigger is a response the parser cannot finish, NOT the `"expires_in": "3600"` a
    // lenient-looking provider sends: this Json coerces that one to the property default without
    // complaining (verified — the test that assumed it threw stayed green while the tokens were
    // never at risk). A truncated body, a proxy's HTML error page with a JSON content type, a
    // number where a string belongs: those throw, and those carry the payload.

    @Test fun refresh_doesNotPutTheTokensInTheErrorOfAMalformedResponse() {
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"SECRET-AT","refresh_token":"SECRET-RT","expires_in":36""",
            ),
        )
        try {
            runBlocking { client.refresh(server.url("/auth/token").toString(), "RT", "sterna") }
            throw AssertionError("expected the malformed response to fail")
        } catch (e: Exception) {
            val text = generateSequence<Throwable>(e) { it.cause }.joinToString(" ") { "${it.javaClass} ${it.message}" }
            assertFalse("the error carried the access token: $text", text.contains("SECRET-AT"))
            assertFalse("the error carried the refresh token: $text", text.contains("SECRET-RT"))
        }
    }

    @Test fun pollDeviceToken_doesNotPutTheTokensInTheErrorOfAMalformedResponse() {
        // Same payload on the device-flow path, whose failure text reaches the connect screen.
        // ⚠ Read whatever surfaces — a returned Failed or a thrown exception: the guard is that
        // the tokens are in neither, and it must not depend on which shape the failure takes.
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"SECRET-AT","refresh_token":"SECRET-RT","expires_in":36""",
            ),
        )
        val outcome = runCatching { runBlocking { client.pollDeviceToken(metadata(), "DC", "sterna") } }
        val text = outcome.fold(
            onSuccess = { it.toString() },
            onFailure = { e ->
                generateSequence<Throwable>(e) { it.cause }.joinToString(" ") { "${it.javaClass} ${it.message}" }
            },
        )
        assertFalse("the failure carried the access token: $text", text.contains("SECRET-AT"))
        assertFalse("the failure carried the refresh token: $text", text.contains("SECRET-RT"))
    }

    /**
     * A body this client cannot read is NOT a transport failure. The poll used to wrap the whole
     * exchange, decode included, so a proxy's HTML error page served with a 200 came back as
     * `network_error` — and the screen sent the reader to check a connection that was working.
     */
    @Test fun pollDeviceToken_doesNotCallAnUnreadableAnswerANetworkFailure() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/html")
                .setBody("<html><body>SECRET-PROXY-PAGE: authentication required</body></html>"),
        )

        val outcome = runCatching { runBlocking { client.pollDeviceToken(metadata(), "DC", "sterna") } }

        assertNotEquals(
            "a 200 whose body cannot be read is not a network failure",
            DeviceTokenResult.Failed("network_error"), outcome.getOrNull(),
        )
        val failure = outcome.exceptionOrNull()
        assertTrue("expected the unreadable answer to surface as a JmapException, got $outcome", failure is JmapException)
        val text = generateSequence<Throwable>(failure) { it.cause }
            .joinToString(" ") { "${it.javaClass} ${it.message}" }
        assertFalse("the error carried the body: $text", text.contains("SECRET-PROXY-PAGE"))
    }

    @Test fun startDeviceAuthorization_doesNotPutTheResponseInItsError() {
        server.enqueue(
            MockResponse().setBody(
                """{"device_code":"SECRET-DC","user_code":"UC","verification_uri":"https://s/d","interval":""",
            ),
        )
        try {
            runBlocking { client.startDeviceAuthorization(metadata(), "sterna", "scope-a") }
            throw AssertionError("expected the malformed response to fail")
        } catch (e: Exception) {
            val text = generateSequence<Throwable>(e) { it.cause }.joinToString(" ") { "${it.javaClass} ${it.message}" }
            assertFalse("the error carried the payload: $text", text.contains("SECRET-DC"))
        }
    }

    // ---- The refusal must reach the screen, and only the protocol's word for it ----
    //
    // Two halves of the same rule. The connect screen has to be able to say WHY a sign-in failed,
    // so the server's `error` field has to survive the call — but an exception message here is
    // written by OkHttp and carries the URL, the host and the port, and the response body is the
    // caller's own tokens. So: the closed vocabulary of RFC 6749 §5.2 travels, the prose does not.

    @Test fun pollDeviceToken_reportsAnUnreachableServerWithoutNamingIt() = runBlocking {
        val endpoint = server.url("/auth/token").toString()
        val port = server.port
        server.shutdown() // nothing is listening any more: a real transport failure
        val metadata = OAuthMetadata(tokenEndpoint = endpoint, deviceAuthorizationEndpoint = endpoint)

        val result = client.pollDeviceToken(metadata, "DC", "sterna")

        assertTrue("expected a terminal failure, got $result", result is DeviceTokenResult.Failed)
        result as DeviceTokenResult.Failed
        assertEquals("network_error", result.error)
        // The connect screen renders this text: OkHttp's own message would put the host and port
        // of the account's server on a screen anyone can be looking over.
        val text = result.error + " " + result.description
        assertFalse("the failure named the host: $text", text.contains("localhost"))
        assertFalse("the failure named the host: $text", text.contains("127.0.0.1"))
        assertFalse("the failure named the port: $text", text.contains(port.toString()))
        assertFalse("the failure carried OkHttp's prose: $text", text.contains("Failed to connect", ignoreCase = true))

        server = MockWebServer().apply { start() } // tearDown shuts one down
    }

    @Test fun startDeviceAuthorization_carriesTheOAuthErrorButNotTheBody() {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":"invalid_client","error_description":"SECRET-DESCRIPTION for tenant X"}""",
            ),
        )

        val outcome = runCatching {
            runBlocking { client.startDeviceAuthorization(metadata(), "sterna", "scope-a") }
        }

        val failure = outcome.exceptionOrNull()
        assertTrue("expected a JmapException, got $outcome", failure is JmapException)
        failure as JmapException
        assertEquals("invalid_client", failure.oauthError)
        assertEquals(400, failure.httpCode)
        val text = generateSequence<Throwable>(failure) { it.cause }
            .joinToString(" ") { "${it.javaClass} ${it.message}" }
        assertFalse("the error carried the response body: $text", text.contains("SECRET-DESCRIPTION"))
        assertFalse("the error carried the response body: $text", text.contains("error_description"))
    }

    // ---- A 3xx on one of the three POSTs must not send the secret to the host it names ----
    //
    // OkHttp follows a 307/308 by re-issuing the SAME method with the SAME body (verified against
    // okhttp 4.12: on a 307 the second server receives
    // `grant_type=refresh_token&refresh_token=SECRET-RT` verbatim), and a 301/302/303 by turning
    // the POST into a bodyless GET whose answer the caller then parses as a token grant. The first
    // hands the redirect target the long-lived refresh token outright; the second lets it choose
    // the access token the app will use.
    //
    // ⚠ Every test below goes through `client`, i.e. the PUBLIC OAuthClient() constructor. The
    // internal constructor takes an injected client, so a test that hands in its own would only
    // assert OkHttp's behaviour and would stay green with production wired to the shared,
    // redirect-following client. The wiring IS the fix, so the public constructor is the only
    // entry point these may use.

    private fun startSecondServer(): MockWebServer = MockWebServer().apply { start() }

    @Test fun refresh_doesNotReplayTheRefreshTokenToARedirectTarget() {
        val second = startSecondServer()
        try {
            // Armed on purpose: if the flag falls, the redirect lands here and this body comes
            // back to the caller as a perfectly valid-looking grant. The test can tell the
            // difference because the second server is able to answer.
            second.enqueue(
                MockResponse().setBody("""{"access_token":"EVIL-AT","refresh_token":"EVIL-RT"}"""),
            )
            server.enqueue(
                MockResponse().setResponseCode(307).setHeader("Location", second.url("/steal").toString()),
            )

            val outcome = runCatching {
                runBlocking { client.refresh(server.url("/auth/token").toString(), "SECRET-RT", "sterna") }
            }

            assertEquals("the refresh token must not be replayed to the redirect target", 0, second.requestCount)
            val failure = outcome.exceptionOrNull()
            assertTrue("expected the 307 to surface as a JmapException, got $outcome", failure is JmapException)
            // The status is what the calling layer reads off the exception, so pin the field.
            assertEquals(307, (failure as JmapException).httpCode)
            assertEquals("the endpoint we asked for was hit once, and only it", 1, server.requestCount)
            assertTrue(
                "the token still went to the endpoint the caller named",
                server.takeRequest().body.readUtf8().contains("refresh_token=SECRET-RT"),
            )
        } finally {
            second.shutdown()
        }
    }

    @Test fun refresh_refusesEvery3xx_notOnlyTheOnesThatKeepTheBody() {
        val second = startSecondServer()
        try {
            listOf(301, 302, 303, 307, 308).forEach { code ->
                second.enqueue(MockResponse().setBody("""{"access_token":"EVIL-AT"}"""))
                server.enqueue(
                    MockResponse().setResponseCode(code)
                        .setHeader("Location", second.url("/steal/$code").toString()),
                )

                val outcome = runCatching {
                    runBlocking { client.refresh(server.url("/auth/token").toString(), "SECRET-RT", "sterna") }
                }

                val failure = outcome.exceptionOrNull()
                assertTrue("HTTP $code was followed: $outcome", failure is JmapException)
                assertEquals("HTTP $code", code, (failure as JmapException).httpCode)
            }
            assertEquals("nothing may reach the redirect target, for any 3xx", 0, second.requestCount)
        } finally {
            second.shutdown()
        }
    }

    @Test fun pollDeviceToken_doesNotReplayTheDeviceCodeToARedirectTarget() = runBlocking {
        val second = startSecondServer()
        try {
            second.enqueue(
                MockResponse().setBody("""{"access_token":"EVIL-AT","refresh_token":"EVIL-RT"}"""),
            )
            server.enqueue(
                MockResponse().setResponseCode(307).setHeader("Location", second.url("/steal").toString()),
            )

            // This path swallows throwables (runCatching) and answers with a result, so pin both
            // the result the connect screen gets and the silence of the redirect target.
            val result = client.pollDeviceToken(metadata(), "SECRET-DC", "sterna")

            assertEquals("the device code must not be replayed to the redirect target", 0, second.requestCount)
            assertEquals(DeviceTokenResult.Failed("http_307"), result)
        } finally {
            second.shutdown()
        }
    }

    @Test fun startDeviceAuthorization_doesNotReplayTheRequestToARedirectTarget() {
        val second = startSecondServer()
        try {
            second.enqueue(
                MockResponse().setBody(
                    """{"device_code":"EVIL-DC","user_code":"UC","verification_uri":"https://evil.example/d"}""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(307).setHeader("Location", second.url("/steal").toString()),
            )

            val outcome = runCatching {
                runBlocking { client.startDeviceAuthorization(metadata(), "sterna", "scope-a offline_access") }
            }

            assertEquals("the device-authorization POST must not be replayed", 0, second.requestCount)
            val failure = outcome.exceptionOrNull()
            assertTrue("expected the 307 to surface as a JmapException, got $outcome", failure is JmapException)
            assertEquals(307, (failure as JmapException).httpCode)
        } finally {
            second.shutdown()
        }
    }

    // ---- The witness that the cut stopped at the POSTs ----
    //
    // Discovery is a GET carrying no secret, and a deployment whose
    // /.well-known/oauth-authorization-server sits behind a proxy or a vhost answers a 3xx there.
    // Refusing it would not look like a redirect to anyone: ConnectViewModel swallows the failure
    // and shows connect_oauth_unsupported, i.e. "your server does not support this" (#54, #137).
    // So this one MUST stay followed, and this test is what fails if the fix overreaches.

    @Test fun discoverMetadata_stillFollowsARedirect() = runBlocking {
        val second = startSecondServer()
        try {
            second.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"issuer":"https://idp.example","token_endpoint":"https://idp.example/token",
                       "device_authorization_endpoint":"https://idp.example/device"}""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(307).setHeader(
                    "Location",
                    second.url("/.well-known/oauth-authorization-server").toString(),
                ),
            )

            val metadata = client.discoverMetadata(server.url("/").toString())

            assertEquals("the discovery redirect must still be followed", 1, second.requestCount)
            assertEquals("https://idp.example/token", metadata?.tokenEndpoint)
            assertTrue("the metadata behind the redirect must be the one we use", metadata?.supportsDeviceFlow == true)
        } finally {
            second.shutdown()
        }
    }

    // ---- The flags, read off the object the factory builds ----

    @Test fun defaultOAuthHttpClient_followsNoRedirectAtAll() {
        val built = defaultOAuthHttpClient()
        assertFalse("a 3xx on a token POST must never be followed", built.followRedirects)
        assertFalse("and never across a scheme change either", built.followSslRedirects)
    }
}
