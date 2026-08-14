package app.sterna.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and disclaimer as [OAuthWiringTest].
 * `OAuthCodeSignIn` takes a `MailRepository`, `ConnectViewModel` is an `AndroidViewModel` wired to
 * the app container, `MainActivity` is an activity: none can be instantiated here, and no test in
 * this repo runs them. Every DECISION they act on is covered by execution — the four exchange
 * arguments in [OAuthRedirectTest] (`tokenExchangeFor`), which hosts get probed in
 * [OAuthErrorMessagesTest] (`collectOAuthHosts`) — and what is left is that they act on them,
 * which is text.
 *
 * ⚠ These rules exist because mutation campaigns proved them necessary: with the pure decisions
 * covered, dropping the intent strip, sending a DEVICE-flow server to the browser, and passing
 * `email`/`accountName` positionally all left the suite green.
 *
 * ⚠ HOW IT COMPARES. Required shapes are whole trimmed lines compared for EQUALITY: a `contains`
 * on a fragment is blind to any mutation that lengthens the line — `tokenExchangeFor(verdict.copy(
 * request = …), redirectUri)` being exactly that — and that blindness has cost this repo real
 * defects. Forbidden tokens are the one place a substring search is sound.
 */
class OAuthCodeWiringTest {

    /**
     * The exchange posts what [tokenExchangeFor] decided, whole, and nothing it re-derived here.
     * ⛔ The call itself is pinned as a whole line: rebuilding the verdict inside the parentheses
     * keeps the name and changes the provenance, which is the mutation that matters.
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
     * A new sign-in REPLACES the one in flight. An abandoned request (browser opened, Back pressed)
     * is not completable any more, and a `start()` that returns early because something is armed
     * locks the user out of OAuth for the life of the process — and reopens the browser panel of
     * the dead request on the next visit to Add account.
     *
     * ⛔ This is not the one-shot rule, which lives on the RETURN ([AuthorizationRequestSlot]) and
     * is covered by execution.
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
     * A failure is said out loud, like a success. This driver is app-scoped precisely because the
     * screen may be gone during the browser trip; a success toasts, and an error that only travels
     * on a replay-less SharedFlow reaches nobody at all — the sign-in simply never happens and
     * nothing says why.
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
     * ⛔ The device flow stays the default, and the search is the shared one. A server offering
     * both grants must not be sent through a browser: that is the production path today.
     */
    @Test fun `the screen hands over only on the authorization code grant`() {
        val body = functionBody(VIEW_MODEL, "connectOAuth")
        val lines = body.lines().map { it.trim() }
        listOf(PROBES, HANDS_OVER, "host = host,", "metadata = found,", "email = emailTrim,", "accountName = accountName,")
            .forEach { required ->
                assertTrue(
                    "connectOAuth must carry exactly this whole line:\n  $required\n⚠ The anchors " +
                        "are the point: `!= OAuthGrant.NONE` compiles and sends every device-flow " +
                        "server to a browser; `start(host, found, accountName, emailTrim)` " +
                        "compiles and stores the account label as its address. Body was:\n$body",
                    lines.contains(required),
                )
            }
    }

    /**
     * A redirect is a one-shot payload, and it must be handed over from BOTH doors: `onNewIntent`
     * is the live-app path (the activity is singleTask, so the browser's answer re-enters the
     * running instance), `onCreate` the one after a process death. Losing either loses the whole
     * sign-in, silently.
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

    // -- reading the files -------------------------------------------------------------------------

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

        const val HANDS_OVER = "if (chooseOAuthGrant(found) == OAuthGrant.AUTHORIZATION_CODE) {"
        /**
         * ⚠ The search is now the FALLBACK arm: the address step already asked, under its budget,
         * and `reusableOAuth` hands its answer over rather than probing a second time (pinned in
         * [OAuthHandoverWiringTest]). What must not change is that when there IS no answer to
         * reuse — the manual button, a process death — the same shared search runs, with the same
         * candidates and the same credentials-free probe.
         */
        const val PROBES =
            "?: collectOAuthHosts(candidates) { container.mailRepository.discoverOAuth(it) }"
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
