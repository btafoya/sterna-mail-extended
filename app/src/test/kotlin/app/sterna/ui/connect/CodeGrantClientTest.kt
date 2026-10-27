package app.sterna.ui.connect

import app.sterna.core.jmap.JmapException
import app.sterna.core.jmap.OAuthMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Executes [codeGrantClientFor] and [oauthRouteFor]. Every expected value is written out, none is read from the
 * constants the code under test uses. Who calls it, with which redirect, and what is done with
 * its answer is `OAuthCodeWiringTest`'s, which reads the text.
 */
class CodeGrantClientTest {

    private val discovered = OAuthMetadata(
        issuer = "https://mail.example.org",
        authorizationEndpoint = "https://mail.example.org/authorize",
        tokenEndpoint = "https://mail.example.org/token",
    )

    private val registering = discovered.copy(registrationEndpoint = "https://mail.example.org/register")

    @Test fun `a server that asks for registration is signed into with the id it issued`() = runBlocking {
        var calls = 0

        val client = codeGrantClientFor(registering) {
            calls++
            "swc1.issued"
        }

        assertEquals(1, calls)
        assertEquals(
            CodeGrantClient(clientId = "swc1.issued", scope = "urn:ietf:params:oauth:scope:mail offline_access"),
            client,
        )
    }

    @Test fun `a server that does not ask for registration keeps Sterna's own id, unregistered`() = runBlocking {
        val client = codeGrantClientFor(discovered) { error("no registration may be sent here") }

        assertEquals(
            CodeGrantClient(
                clientId = "sterna",
                scope = "urn:ietf:params:jmap:core urn:ietf:params:jmap:mail urn:ietf:params:jmap:submission offline_access",
            ),
            client,
        )
    }

    /** No fallback to `sterna`: the registering server refuses it, and the caller says why it failed. */
    @Test fun `a failed registration is not replaced by Sterna's own id`() {
        val refused = JmapException("Client registration failed: HTTP 400", httpCode = 400, oauthError = "invalid_redirect_uri")

        val outcome = runCatching { runBlocking { codeGrantClientFor(registering) { throw refused } } }

        assertSame(refused, outcome.exceptionOrNull())
    }

    private val sternaClient = CodeGrantClient(
        clientId = "sterna",
        scope = "urn:ietf:params:jmap:core urn:ietf:params:jmap:mail urn:ietf:params:jmap:submission offline_access",
    )

    /** Stalwart before 1.0: device flow, code grant and a registration endpoint that wants a token. */
    private val stalwart016 = registering.copy(deviceAuthorizationEndpoint = "https://mail.example.org/device")

    private fun refusal(code: Int, oauthError: String? = null) =
        JmapException("Client registration failed: HTTP $code", httpCode = code, oauthError = oauthError)

    private fun routeAfter(metadata: OAuthMetadata, thrown: Throwable): Pair<Result<OAuthRoute>, Int> {
        var calls = 0
        val outcome = runCatching {
            runBlocking {
                oauthRouteFor(metadata) {
                    calls++
                    throw thrown
                }
            }
        }
        return outcome to calls
    }

    @Test fun `a 401 at registration falls back on the device flow the document offers, asking once`() {
        val (outcome, calls) = routeAfter(stalwart016, refusal(401))

        assertEquals(OAuthRoute.Device, outcome.getOrThrow())
        assertEquals(1, calls)
    }

    @Test fun `a 403 at registration falls back on the device flow the document offers`() {
        val (outcome, _) = routeAfter(stalwart016, refusal(403))

        assertEquals(OAuthRoute.Device, outcome.getOrThrow())
    }

    @Test fun `a 401 at registration without a device flow signs in as sterna, asking once`() {
        val (outcome, calls) = routeAfter(registering, refusal(401, oauthError = "invalid_token"))

        assertEquals(OAuthRoute.Code(sternaClient), outcome.getOrThrow())
        assertEquals(1, calls)
    }

    @Test fun `a 403 at registration without a device flow signs in as sterna`() {
        val (outcome, _) = routeAfter(registering, refusal(403))

        assertEquals(OAuthRoute.Code(sternaClient), outcome.getOrThrow())
    }

    /** Only "authenticate first" means the document's endpoint is not for us; anything else is said. */
    @Test fun `any other registration failure is thrown to the caller, device flow or not`() {
        listOf(
            refusal(400, oauthError = "invalid_redirect_uri"),
            refusal(400, oauthError = "invalid_client_metadata"),
            refusal(404),
            refusal(500),
            refusal(503),
            JmapException("The client registration endpoint must be https"),
            IOException("unreachable"),
        ).forEach { thrown ->
            listOf(registering, stalwart016).forEach { metadata ->
                val (outcome, calls) = routeAfter(metadata, thrown)
                assertRethrown("${thrown.message} on ${metadata.deviceAuthorizationEndpoint}", thrown, outcome)
                assertEquals(1, calls)
            }
        }
    }

    @Test fun `a cancelled registration stays cancelled`() {
        val cancelled = CancellationException("left the screen")

        val (outcome, _) = routeAfter(stalwart016, cancelled)

        assertRethrown("cancellation", cancelled, outcome)
    }

    /** Same instance, or the copy coroutines' stack-trace recovery makes of a copyable exception. */
    private fun assertRethrown(what: String, thrown: Throwable, outcome: Result<OAuthRoute>) {
        val seen = outcome.exceptionOrNull()
        assertTrue(
            "$what: expected $thrown rethrown, got ${outcome.getOrNull() ?: seen}",
            seen === thrown || (seen != null && seen::class == thrown::class && seen.message == thrown.message),
        )
    }

    @Test fun `a registration that answers signs in with the issued id, even beside a device flow`() = runBlocking {
        val route = oauthRouteFor(stalwart016) { "swc1.issued" }

        assertEquals(
            OAuthRoute.Code(CodeGrantClient(clientId = "swc1.issued", scope = "urn:ietf:params:oauth:scope:mail offline_access")),
            route,
        )
    }

    @Test fun `a device flow server without registration is never registered at`() = runBlocking {
        val route = oauthRouteFor(discovered.copy(deviceAuthorizationEndpoint = "https://mail.example.org/device")) {
            error("no registration may be sent here")
        }

        assertEquals(OAuthRoute.Device, route)
    }

    @Test fun `a code-only server without registration signs in as sterna, unregistered`() = runBlocking {
        val route = oauthRouteFor(discovered) { error("no registration may be sent here") }

        assertEquals(OAuthRoute.Code(sternaClient), route)
    }
}
