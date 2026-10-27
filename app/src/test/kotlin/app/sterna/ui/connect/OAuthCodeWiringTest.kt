package app.sterna.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test, same instrument and disclaimer as [OAuthWiringTest].
 */
class OAuthCodeWiringTest {

    /**
     * The exchange posts what [tokenExchangeFor] decided, whole, and nothing it re-derived here.
     */
    @Test fun `the exchange posts what tokenExchangeFor decided`() {
        val body = functionBody(SIGN_IN, "exchange")
        val lines = body.lines().map { it.trim() }
        listOf(
            "val exchange = tokenExchangeFor(verdict, redirectUri)",
            "metadata = exchange.metadata,",
            "code = exchange.code,",
            "redirectUri = exchange.redirectUri,",
            "codeVerifier = exchange.codeVerifier,",
        ).forEach { required ->
            assertTrue(
                "OAuthCodeSignIn.exchange must carry exactly this whole line:\n  $required\n" +
                    "The provenance IS the rule: an exchange with a freshly drawn verifier is " +
                    "still an exchange, still non-blank, still compiles — and PKCE is then " +
                    "decoration, because the server holds the challenge of the other one. Body " +
                    "was:\n$body",
                lines.contains(required),
            )
        }
    }

    /**
     * A new sign-in replaces the one in flight. An abandoned request (browser opened, Back pressed)
     */
    @Test fun `a new sign-in replaces the request in flight`() {
        val body = functionBody(SIGN_IN, "start")
        val offenders = body.lines().map { it.trim() }.filter { it.contains("slot.isArmed()") }
        assertEquals(
            "start() must not refuse because a request is already armed. Found:\n" +
                offenders.joinToString("\n") + "\nBody was:\n$body",
            emptyList<String>(), offenders,
        )
        assertTrue(
            "start() must arm the new request. Body was:\n$body",
            body.contains("slot.arm("),
        )
    }

    /**
     * A failure is said out loud, like a success.
     */
    @Test fun `a failure nobody is watching is still said`() {
        val body = functionBody(SIGN_IN, "fail")
        assertTrue(
            "fail() must toast the message as well as emitting it, as this whole line:\n  " +
                "$TOASTS\nBody was:\n$body",
            body.lines().map { it.trim() }.contains(TOASTS),
        )
    }

    /** Nothing drawn or received here is loggable: verifier, state, code, tokens. */
    @Test fun `the code flow logs nothing`() {
        val offenders = (SIGN_IN.readText() + REDIRECT.readText()).lines()
            .filter { LOGGING.containsMatchIn(it) }
            .map { it.trim() }
        assertEquals(
            "the authorization-code files must not log: every value they hold is a secret or " +
                "identifies one. Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(), offenders,
        )
    }

    /**
     * The route is oauthRouteFor's, and the search is the shared one. Only oauthRouteFor's lambda
     * registers, and only the code branch hands over, with the id and scope that route decided.
     */
    @Test fun `the screen hands over only on the authorization code grant`() {
        val body = functionBody(VIEW_MODEL, "connectOAuth")
        val lines = body.lines().map { it.trim() }
        listOf(PROBES, HANDS_OVER).forEach { required ->
            assertTrue(
                "connectOAuth must carry exactly this whole line:\n  $required\n⚠ The anchor " +
                    "is the point: a route re-derived here instead of oauthRouteFor's compiles " +
                    "and sends a device-flow server to a browser. Body was:\n$body",
                lines.contains(required),
            )
        }
        val span = codeBranch(body)
        val branch = lines.slice(span)
        STARTS.forEach { required ->
            assertTrue(
                "the AUTHORIZATION_CODE branch of connectOAuth must carry exactly this whole " +
                    "line:\n  $required\n`start(host, found, accountName, emailTrim)` compiles " +
                    "and stores the account label as its address; a start left to its defaults " +
                    "compiles and sends `sterna` to a server that refuses it. Branch was:\n" +
                    branch.joinToString("\n"),
                branch.contains(required),
            )
        }
        val outside = lines.filterIndexed { i, line -> i !in span && STARTS_CODE.containsMatchIn(line) }
        assertEquals(
            "only the OAuthRoute.Code branch of connectOAuth may hand over to " +
                "oauthCodeSignIn.start. Found outside it:\n" + outside.joinToString("\n"),
            emptyList<String>(), outside,
        )
        val routed = lines.indexOf(ROUTES)
        assertTrue(
            "connectOAuth must ask oauthRouteFor, as this whole line:\n  $ROUTES\nBody was:\n$body",
            routed >= 0,
        )
        assertEquals(
            "the registration must be oauthRouteFor's lambda, on the line right after it, with " +
                "the driver's redirect: a registration with another redirect is refused at the " +
                "exchange, and one outside the lambda escapes the 401/403 fallback. Body was:\n$body",
            REGISTERS, lines.getOrNull(routed + 1),
        )
        val registers = lines.filter { REGISTERS_OR_CHOOSES.containsMatchIn(it) }
        assertEquals(
            "connectOAuth may register once, inside oauthRouteFor, and choose no grant or client " +
                "itself. Found:\n" + registers.joinToString("\n"),
            listOf(ROUTES, REGISTERS), registers,
        )
    }

    /**
     * A redirect is a one-shot payload, and it must be handed over from both doors: `onNewIntent`
     */
    @Test fun `both doors hand the redirect over, and it is dropped once taken`() {
        listOf("onCreate", "onNewIntent").forEach { door ->
            val body = functionBody(MAIN_ACTIVITY, door)
            assertTrue(
                "MainActivity.$door must hand the intent to the code driver, as this whole line:" +
                    "\n  $HANDS_REDIRECT\nBody was:\n$body",
                body.lines().map { it.trim() }.contains(HANDS_REDIRECT),
            )
        }
        val body = functionBody(MAIN_ACTIVITY, "consumeOAuthRedirect")
        assertTrue(
            "MainActivity.consumeOAuthRedirect must clear the payload it consumed, as this whole " +
                "line:\n  $STRIPS\nBody was:\n$body",
            body.lines().map { it.trim() }.contains(STRIPS),
        )
    }

    /**
     * What these two say when the add is over. Both are app-scoped drivers whose toast is the only
     */
    @Test fun `both app-scoped sign-ins let the shared choice pick the sentence`() {
        listOf(
            Triple(SIGN_IN, "exchange", CODE_SAYS_IT),
            Triple(OUTLOOK, "start", OUTLOOK_SAYS_IT),
        ).forEach { (file, function, required) ->
            val lines = functionBody(file, function).lines().map { it.trim() }
            val at = lines.indexOf(required.first())
            assertTrue(
                "${file.name}.$function must say what happened through the shared choice, as " +
                    "these whole lines:\n  " + required.joinToString("\n  ") +
                    "\nWithout them a sign-in that only refreshed an account already installed " +
                    "still reads \"added\", and the reader goes looking for a second account that " +
                    "was never created. Lines were:\n" + lines.joinToString("\n"),
                at >= 0 && lines.subList(at, minOf(at + required.size, lines.size)) == required,
            )
            val offenders = lines.filter { REFRESHED_SENTENCE.containsMatchIn(it) }
            assertEquals(
                "and ${file.name}.$function must not name R.string.connect_account_refreshed " +
                    "itself: it is one of the two answers accountAddedToast picks between, and " +
                    "naming it here takes the decision back into a class no test can build. " +
                    "Found:\n" + offenders.joinToString("\n"),
                emptyList<String>(), offenders,
            )
        }
    }

    // -- reading the files -------------------------------------------------------------------------

    private fun codeBranch(body: String): IntRange {
        val lines = body.lines()
        val start = lines.indexOfFirst { it.trim() == HANDS_OVER }
        check(start >= 0) { "connectOAuth has no `$HANDS_OVER` line: the branch would read empty." }
        var depth = 0
        for (i in start until lines.size) {
            depth += lines[i].count { it == '{' } - lines[i].count { it == '}' }
            if (depth <= 0) return start..i
        }
        return start until lines.size
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
        val REDIRECT = repoFile("app/src/main/kotlin/app/sterna/ui/connect/OAuthRedirect.kt")
        val VIEW_MODEL = repoFile("app/src/main/kotlin/app/sterna/ui/connect/ConnectViewModel.kt")
        val MAIN_ACTIVITY = repoFile("app/src/main/kotlin/app/sterna/MainActivity.kt")
        val OUTLOOK = repoFile("app/src/main/kotlin/app/sterna/ui/connect/OutlookSignIn.kt")

        /** The end of the code-grant add: its own sentence on a create, the shared choice on both. */
        val CODE_SAYS_IT = listOf(
            "accountAddedToast(created, R.string.connect_account_added)?.let { toast(string(it)) }",
        )

        /** The same, on the Outlook driver, whose call is wrapped over two lines. */
        val OUTLOOK_SAYS_IT = listOf(
            "accountAddedToast(created, R.string.connect_outlook_added)",
            "?.let { toast(appContext.getString(it)) }",
        )

        val REFRESHED_SENTENCE = Regex("""\bR\.string\.connect_account_refreshed\b""")

        const val HANDS_OVER = "if (route is OAuthRoute.Code) {"

        const val ROUTES = "oauthRouteFor(found) {"

        const val REGISTERS =
            "container.mailRepository.accounts.registerOAuthClient(found, container.oauthCodeSignIn.redirectUri)"
        val STARTS = listOf(
            "val client = route.client",
            "container.oauthCodeSignIn.start(",
            "host = host,",
            "metadata = found,",
            "email = emailTrim,",
            "accountName = accountName,",
            "clientId = client.clientId,",
            "scope = client.scope,",
        )
        val STARTS_CODE = Regex("""\boauthCodeSignIn\.start\(""")
        val REGISTERS_OR_CHOOSES =
            Regex("""\bregisterOAuthClient\(|\bcodeGrantClientFor\(|\bchooseOAuthGrant\(|\boauthRouteFor\(""")
        /**
         * The search is now the fallback arm: the address step already asked, under its budget,
         * and `reusableOAuth` hands its answer over rather than probing a second time (pinned in
         * [OAuthHandoverWiringTest]). What must not change is that when there IS no answer to
         * reuse (the manual button, a process death) the same shared search runs, with the same
         * candidates and the same credentials-free probe.
         */
        const val PROBES =
            "?: collectOAuthHosts(candidates) { container.mailRepository.accounts.discoverOAuth(it, addressDomain) }"
        const val HANDS_REDIRECT = "consumeOAuthRedirect()"
        const val STRIPS = "current.data = null"
        const val TOASTS = "toast(message)"

        val LOGGING = Regex("""\bLog\.[a-z]|\bprintln\(""")

        fun repoFile(path: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, path).isFile }
                ?.let { File(it, path) }
                ?: error(
                    "cannot locate $path from ${File("").absolutePath} — this test reads source " +
                        "files as text and needs a working directory inside the checkout",
                )
    }
}
