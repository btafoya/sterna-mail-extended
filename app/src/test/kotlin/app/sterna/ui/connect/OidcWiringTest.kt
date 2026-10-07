package app.sterna.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test. It reads three function bodies as text and checks that each
 * carries given whole lines, for the sign-in at an issuer the user typed (#249).
 *
 * Why a lint: `ConnectViewModel` and `OAuthCodeSignIn` need an Android application and a
 * `MailRepository`, and no JVM test builds either. The decisions they call are executed elsewhere:
 * `OidcSignInTest` (the client id, the JMAP host, what the exchange posts) and, in `core/data`,
 * `OAuthClientIdFollowsTheSignInTest` (the URL, the POST, the stored account). What is left here
 * is the last hop, the arguments these two bodies hand over, which every one of those tests
 * would stay green without.
 *
 * What it does not cover:
 * - Behaviour. A line can be present and dead: placed after a `return`, or in a branch that is
 *   never taken. Only the device passes see the sign-in happen.
 * - Order, except where a test says so.
 * - The screen. Its fields and its button are `OidcScreenWiringTest`'s; nothing here reads it.
 *
 * Lines are compared whole and trimmed, never by `contains`: a line that is lengthened keeps
 * containing its old text.
 */
class OidcWiringTest {

    private fun lines(file: File, function: String): List<String> =
        functionBody(file, function).lines().map { it.trim() }

    private fun assertCarries(file: File, function: String, why: String, required: List<String>) {
        val body = lines(file, function)
        required.forEach { line ->
            assertTrue(
                "${file.name}.$function must carry exactly this whole line:\n  $line\n$why\nLines " +
                    "were:\n" + body.joinToString("\n"),
                body.contains(line),
            )
        }
    }

    /**
     * The typed route hands the driver its own client id and the OpenID scopes, and the JMAP
     * server field as the host. The issuer never appears as `host`: that host is sent the access
     * token as a JMAP credential.
     */
    @Test fun `the typed issuer hands over its client id, its scopes and the JMAP server field`() {
        assertCarries(
            VIEW_MODEL, "connectOidc",
            "Sterna's own client id is unknown to an identity provider, the JMAP scopes are " +
                "answered `invalid_scope`, and `host = issuer` sends the token to the issuer.",
            listOf(
                "val signIn = issuerSignInFor(issuer, clientId) { container.mailRepository.accounts.discoverAtIssuer(it) }",
                "is IssuerSignIn.Ready -> container.oauthCodeSignIn.start(",
                "host = server.trim(),",
                "metadata = signIn.metadata,",
                "email = emailTrim,",
                "accountName = accountName,",
                "clientId = signIn.clientId,",
                "scope = OpenIdDiscovery.SCOPE,",
            ),
        )
    }

    /** Without a client id the route gives the screen back, it does not leave it on its wheel. */
    @Test fun `a missing client id gives the screen back`() {
        assertCarries(
            VIEW_MODEL, "connectOidc",
            "The route sets Discovering before it decides: an arm that does nothing leaves the " +
                "screen there, with every button dead, and nothing in flight to end it.",
            listOf("IssuerSignIn.NoClientId -> _state.value = ConnectState.Idle"),
        )
    }

    /** Always the code grant with PKCE: a typed issuer is never sent through the device flow. */
    @Test fun `the typed issuer never takes the device flow`() {
        val offenders = lines(VIEW_MODEL, "connectOidc").filter { DEVICE_FLOW.containsMatchIn(it) }
        assertEquals(
            "connectOidc must not choose a grant nor start a device authorization. Found:\n" +
                offenders.joinToString("\n"),
            emptyList<String>(), offenders,
        )
    }

    /** One failure of the discovery, one sentence, and it is not one of the two older ones. */
    @Test fun `a typed issuer without a configuration gets its own sentence`() {
        val body = lines(VIEW_MODEL, "connectOidc")
        assertTrue(
            "connectOidc must answer a missing configuration with this whole line:\n  $NO_CONFIGURATION",
            body.contains(NO_CONFIGURATION),
        )
        val offenders = body.filter { OLDER_SENTENCES.containsMatchIn(it) }
        assertEquals(
            "connectOidc must not reuse the sentences of a discovered server: they tell the user " +
                "to use a password, on a route that has none. Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(), offenders,
        )
    }

    /** The URL and the remembered request are given the client id `start` received. */
    @Test fun `the driver builds the URL and remembers the request with what it was given`() {
        assertCarries(
            SIGN_IN, "start",
            "A constant in either place compiles: the browser is then sent with one client id " +
                "and the code redeemed with another, or the typed id is dropped altogether.",
            listOf(
                "metadata, redirectUri, state, Pkce.codeChallengeOf(verifier),",
                "clientId = clientId, scope = scope,",
                "clientId = clientId,",
            ),
        )
    }

    /**
     * A discovered server names neither: the route that existed before this one must go on
     * asking a mail server for Sterna's client id and the JMAP scopes.
     */
    @Test fun `left unnamed the driver asks for Sterna's own client id and scopes`() {
        assertCarries(
            SIGN_IN, "start",
            "With the OpenID scopes as the default, every discovered server is asked for scopes " +
                "it does not grant mail access under, and no executed test names this default.",
            listOf(
                "clientId: String = Jmap.OAUTH_CLIENT_ID,",
                "scope: String = Jmap.OAUTH_SCOPE,",
            ),
        )
    }

    /**
     * After the exchange: the client id posted is the one `tokenExchangeFor` decided, the host is
     * `jmapHostAfterSignIn`'s answer on the request's own host, and the account is added on that
     * answer with the request's client id.
     */
    @Test fun `the exchange posts the decided client id and adds the account on the decided host`() {
        assertCarries(
            SIGN_IN, "exchange",
            "`request.metadata.issuer` in place of `host` compiles, and sends the fresh access " +
                "token to the issuer as a JMAP credential.",
            listOf(
                "clientId = exchange.clientId,",
                "val choice = jmapHostAfterSignIn(request.host) {",
                """repo.accounts.discoverJmapServer(request.email, password = "", token = tokens.accessToken)""",
                "val host = when (choice) {",
                "is JmapHostChoice.Found -> choice.host",
                "val created = repo.accounts.addOAuthAccount(host, request.email, request.metadata, tokens, request.accountName, clientId = request.clientId)",
            ),
        )
    }

    /**
     * No host ends the sign-in out loud. The screen may be gone during the browser trip and the
     * outcome flow has no replay, so the toast is what reaches the user, and the progress must
     * leave Connecting or the screen that is still there spins for ever. The lines are read as
     * one contiguous run: each of them also occurs elsewhere in the body.
     */
    @Test fun `no JMAP host is said out loud and ends the sign-in`() {
        val body = lines(SIGN_IN, "exchange")
        val at = body.indexOf(NO_HOST.first())
        assertEquals(
            "OAuthCodeSignIn.exchange must end a sign-in without a JMAP host with these whole " +
                "lines, in this order. Lines were:\n" + body.joinToString("\n"),
            NO_HOST,
            if (at < 0) emptyList() else body.drop(at).take(NO_HOST.size),
        )
    }

    /** The body of a named function, as text. Braces counted raw, as the other source lints do. */
    private fun functionBody(file: File, name: String): String {
        val lines = file.readText().lines()
        val start = lines.indexOfFirst { Regex("""\bfun $name\b""").containsMatchIn(it) }
        check(start >= 0) {
            "$name() is gone from ${file.name}: this lint reads nothing, so rename it here too " +
                "rather than let the rules pass over an empty string."
        }
        val out = StringBuilder()
        var depth = 0
        var opened = false
        for (i in start until lines.size) {
            val line = lines[i]
            out.appendLine(line)
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (line.contains('{')) opened = true
            if (opened && depth <= 0) break
        }
        return out.toString()
    }

    private companion object {
        val SIGN_IN = repoFile("app/src/main/kotlin/app/sterna/ui/connect/OAuthCodeSignIn.kt")
        val VIEW_MODEL = repoFile("app/src/main/kotlin/app/sterna/ui/connect/ConnectViewModel.kt")

        val NO_HOST = listOf(
            "is JmapHostChoice.Missing -> {",
            "val message = string(noJmapHostSentence(choice))",
            "_progress.value = OAuthCodeProgress.Idle",
            "toast(message)",
            "_outcomes.emit(OAuthCodeOutcome.Error(message))",
            "return@launch",
            "}",
        )

        const val NO_CONFIGURATION =
            "_state.value = ConnectState.Error(string(R.string.connect_oidc_no_configuration))"

        val DEVICE_FLOW = Regex("""\bchooseOAuthGrant\(|\bstartDeviceAuthorization\(|\bpollForToken\(""")
        val OLDER_SENTENCES = Regex("""\bR\.string\.connect_oauth_(unsupported|elsewhere|flow_unsupported)\b""")

        fun repoFile(path: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, path).isFile }
                ?.let { File(it, path) }
                ?: error(
                    "cannot locate $path from ${File("").absolutePath}: this test reads source " +
                        "files as text and needs a working directory inside the checkout",
                )
    }
}
