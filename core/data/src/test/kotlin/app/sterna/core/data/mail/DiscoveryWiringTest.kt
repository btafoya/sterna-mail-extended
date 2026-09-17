package app.sterna.core.data.mail

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test, same instrument and same disclaimer as
 * [app.sterna.core.data.account.ImapStopsAtTheWindowIsDocumentedTest]: it reads a shipped file as
 * text and proves nothing about what runs.
 */
class DiscoveryWiringTest {

    @Test fun `autodiscovery asks each host through the probe the tests run, on its own client`() {
        val body = discoverJmapServerBody()
        assertTrue(
            "MailAccounts.discoverJmapServer must ask each candidate through " +
                "probeHost { discoveryClient.fetchSession(…) }. probeHost is where a session, a " +
                "401/403 and a cancellation are turned into a HostProbe — and the only version of " +
                "that translation any test executes; spelled out at this call site instead, it is " +
                "a decision nothing can hold, and replacing it with a constant leaves the whole " +
                "suite green. The client has to be discoveryClient and not the shared one: only " +
                "it bounds a candidate's connect below the discovery budget, and on the shared " +
                "client an unreachable bare domain spends that budget alone (#221). Body was:\n$body",
            PROBE_WIRING.containsMatchIn(body),
        )
    }

    @Test fun `autodiscovery takes the budget the app ships with, and does not restate it`() {
        val body = discoverJmapServerBody()
        assertTrue(
            "MailAccounts.discoverJmapServer must call discoverJmapAmong(hosts) with NO budget " +
                "argument: the shipped value then lives once, in JMAP_DISCOVERY_BUDGET_MS, where a " +
                "test pins it. Restating it here makes a second copy no test can reach. Body was:\n$body",
            BUDGET_LESS_CALL.containsMatchIn(body),
        )
    }

    /**
     * Whole line, never a fragment: `contains("BearerAuth(")` is blind to everything that
     * LENGTHENS the line, and this line's mutation shortens and lengthens it.
     */
    @Test fun `autodiscovery proves a token as a Bearer, and only falls back on Basic without one`() {
        val body = discoverJmapServerBody()
        assertTrue(
            "MailAccounts.discoverJmapServer must build its auth as:\n  $AUTH_LINE\n" +
                "Rewritten to Basic only, the whole suite stays green and #55 comes back in " +
                "silence: the OAuth cascade then probes all four candidates with an EMPTY " +
                "password, every one answers 401, and oauthServerToStore falls back on the " +
                "document's host — one mailbox added by password and by OAuth is two accounts " +
                "again, with nothing to see anywhere. Body was:\n$body",
            body.lines().map { it.trim() }.contains(AUTH_LINE),
        )
    }

    /**
     * Whole line, never a fragment, and the reason it is pinned at all is #188.
     */
    @Test fun `autodiscovery keeps the candidate order, so rank 0 is the domain she typed`() {
        val body = discoverJmapServerBody()
        assertTrue(
            "MailAccounts.discoverJmapServer must build its candidates as:\n  $HOSTS_LINE\n" +
                "Rank 0 must stay the domain the reader typed: it is the only rank whose 401/403 " +
                "is her password being refused, every other rank being a guess (#188). Reordered " +
                "or reversed here, that property is silently false and no test in the tree can " +
                "see it. Body was:\n$body",
            body.lines().map { it.trim() }.contains(HOSTS_LINE),
        )
    }

    @Test fun `autodiscovery is handed a client of its own, bounded for an unreachable host`() {
        val body = MAIL_REPOSITORY.readText()
        assertTrue(
            "MailRepository must build autodiscovery's client as:\n  $DISCOVERY_CLIENT_LINE\n" +
                "Given the shared `client` instead, discovery inherits its 20 s connect timeout, " +
                "which outlives JMAP_DISCOVERY_BUDGET_MS whole: a bare domain that swallows SYNs " +
                "on 443 then takes the entire budget by itself, mail./jmap./api. are never asked, " +
                "and she is sent back to 'tell me your server' (#221) — with nothing red " +
                "anywhere. Pinned whole and never by fragment: `= client` leaves the name " +
                "discoveryClient, and every rule above, standing. Not found in MailRepository.kt.",
            body.lines().map { it.trim() }.contains(DISCOVERY_CLIENT_LINE),
        )
    }

    private fun discoverJmapServerBody(): String {
        val text = MAIL_ACCOUNTS.readText()
        val start = text.indexOf(DECLARATION)
        check(start >= 0) {
            "no `$DECLARATION` in MailAccounts.kt — autodiscovery was renamed or moved, and the " +
                "rules in this file are now guarding nothing. Follow it, or delete this file."
        }
        // To the next top-level declaration of the class: enough to hold the function, and it does
        // not need brace matching to be right, only to stop somewhere after the call.
        val end = text.indexOf("\n    /**", start).takeIf { it > start } ?: text.length
        return text.substring(start, end)
    }

    private companion object {
        const val DECLARATION = "suspend fun discoverJmapServer("

        /**
         * The one line the OAuth half of #55 rests on, whole. A token is the only credential the
         * OAuth cascade has: `addOAuthAccount` calls this with `password = ""`.
         */
        const val AUTH_LINE =
            """val auth = if (token != null) BearerAuth(token) else BasicAuth(email.trim(), password)"""

        /**
         * The candidate list, whole. Its order is load-bearing since #188: rank 0 is the typed
         * domain, and only rank 0 can make the verdict BadCredentials.
         */
        const val HOSTS_LINE = """val hosts = Jmap.autodiscoverHosts(email)"""

        const val DISCOVERY_CLIENT_LINE =
            """private val discoveryClient: JmapClient = jmapDiscoveryClient(),"""

        /** The probe, as a whole call: `probeHost { discoveryClient.fetchSession(…) }`. */
        val PROBE_WIRING = Regex("""probeHost \{[^}]*discoveryClient\.fetchSession\(""")

        /** `discoverJmapAmong(hosts)` with exactly one argument before the trailing lambda. */
        val BUDGET_LESS_CALL = Regex("""discoverJmapAmong\(hosts\)\s*\{""")

        /** The module whose classes are running: the files read are then the tree that was built. */
        val MODULE: File by lazy {
            val classes = DiscoveryWiringTest::class.java.protectionDomain?.codeSource?.location
                ?: error("no code source; cannot tell which tree was built")
            var dir: File? = File(classes.toURI()).absoluteFile
            while (dir != null && dir.name != "build") dir = dir.parentFile
            dir?.parentFile
                ?: error("$classes is not under a 'build' directory; cannot locate the module source")
        }

        fun shippedFile(name: String): File =
            File(MODULE, "src/main/kotlin/app/sterna/core/data/mail/$name").also {
                check(it.isFile) { "no $name in the tree that was built ($MODULE)" }
            }

        val MAIL_ACCOUNTS: File by lazy { shippedFile("MailAccounts.kt") }
        val MAIL_REPOSITORY: File by lazy { shippedFile("MailRepository.kt") }
    }
}
