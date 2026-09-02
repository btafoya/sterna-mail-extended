package app.sterna.ui.connect

import app.sterna.core.jmap.Jmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WHICH hosts the default path may ask how the mail signs in — [mailOAuthCandidates], EXECUTED,
 * with pinned arguments and pinned results.
 *
 * The defect: `Jmap.autodiscoverHosts` yields the BARE DOMAIN first, i.e. the organisation's
 * website, and the address step took that site's `/.well-known/oauth-authorization-server` for a
 * verdict about the mail. Measured on the bench: `nina@masto.top` was offered "Open the sign-in
 * page" onto that domain's Mastodon login form, and a second persona a device flow whose metadata
 * was only found by following a 301 to ANOTHER HOST — while her domain publishes a perfectly good
 * mail autoconfig. No persona could be added by the default path any more.
 *
 * ⛔ Every expected list below is HARD-CODED. A test that re-derives "drop the bare domain" from
 * the address it feeds in is a copy of the decision: the shipped rule could be inverted under it
 * and stay green.
 */
class MailOAuthCandidatesTest {

    /** ⭐ The persona the defect was measured on: her domain also hosts a Mastodon. */
    @Test fun `the site the organisation runs is not asked how the mail signs in`() {
        assertEquals(
            "the default path may ask ONLY the mail-named hosts. `masto.top` is the website: its " +
                "OAuth document is the Mastodon's, and taking it for a verdict is what opened " +
                "masto.top/auth/sign_in instead of a password field.",
            listOf("mail.masto.top", "jmap.masto.top", "api.masto.top"),
            mailOAuthCandidates("nina@masto.top"),
        )
        assertFalse(
            "the bare domain must not come back in under any spelling",
            mailOAuthCandidates("nina@masto.top").contains("masto.top"),
        )
    }

    /** The second bench case: a domain whose website redirects its OAuth lookup to another host. */
    @Test fun `a domain publishing mail autoconfig is asked only on its mail hosts`() {
        assertEquals(
            listOf("mail.example.net", "jmap.example.net", "api.example.net"),
            mailOAuthCandidates("theo@example.net"),
        )
    }

    /**
     * ⛔ Exactly ONE candidate is dropped, and it is the bare domain. Both halves matter: a rule
     * that returned an empty list would silence OAuth on the default path for everyone, and one
     * that dropped `api.` (Fastmail) instead would be invisible to the two tests above only if
     * they were written loosely.
     */
    @Test fun `what is removed is the bare domain, and nothing else`() {
        val all = Jmap.autodiscoverHosts("nina@masto.top")
        assertEquals(
            "the four candidates the sign-in button still uses, unchanged",
            listOf("masto.top", "mail.masto.top", "jmap.masto.top", "api.masto.top"),
            all,
        )
        assertEquals(
            listOf("masto.top"),
            all - mailOAuthCandidates("nina@masto.top").toSet(),
        )
    }

    /**
     * The address normalisation is [Jmap.autodiscoverHosts]' and is not restated here: an address
     * typed with capitals and a trailing dot must yield the same three hosts, or the rule has been
     * rebuilt somewhere it can drift.
     */
    @Test fun `case and a trailing dot are the same address`() {
        assertEquals(
            listOf("mail.masto.top", "jmap.masto.top", "api.masto.top"),
            mailOAuthCandidates("Nina@Masto.Top."),
        )
    }

    /**
     * ⛔ A DOMAIN OF THREE LABELS OR MORE, and the reason it is here rather than "for completeness":
     * every other case in this class has a two-label domain, and a rule spelled "keep the hosts
     * with exactly two dots" satisfies all of them while returning, for `example.co.uk`, THE BARE
     * DOMAIN AND NOTHING ELSE — the original defect in its purest form, for every address in the
     * United Kingdom, Australia, Japan, and every corporate subdomain. Found by the counter-audit
     * as a surviving mutation: the rule was right, and nothing held it.
     */
    @Test fun `a domain of three labels or more drops its bare domain too, and only that`() {
        assertEquals(
            listOf("mail.example.co.uk", "jmap.example.co.uk", "api.example.co.uk"),
            mailOAuthCandidates("alice@example.co.uk"),
        )
        assertEquals(
            "a mail subdomain is an ordinary domain: what is dropped is what the others are " +
                "subdomains OF, whatever its label count.",
            listOf("mail.mail.entreprise.fr", "jmap.mail.entreprise.fr", "api.mail.entreprise.fr"),
            mailOAuthCandidates("theo@mail.entreprise.fr"),
        )
    }

    @Test fun `an address nothing can be derived from asks nobody`() {
        listOf("", "   ", "no-at-sign", "alice@", "alice@localhost").forEach { input ->
            assertEquals(
                "\"$input\" is not an address with a domain: the default path must ask nobody, " +
                    "not fall back to some host built out of it.",
                emptyList<String>(),
                mailOAuthCandidates(input),
            )
        }
    }
}

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and disclaimer as [OAuthCodeWiringTest].
 * `ConnectViewModel` is an `AndroidViewModel` on the app container: no test in this module can
 * build one, so the DECISION is executed above ([MailOAuthCandidatesTest]) and what is left is
 * that each of the two call sites acts on the list it is entitled to — which is text.
 *
 * ⚠ Both sites are read INSIDE THEIR OWN FUNCTION BODY, never over the whole file. A pin on a
 * whole line is blind to the two lines being swapped between the two functions, and swapping them
 * is precisely the mutation that reopens the defect while narrowing the one door that must stay
 * wide: it would show a browser page to everyone at the address step AND tell the manual button's
 * user her server has no OAuth.
 */
class MailOAuthCandidatesWiringTest {

    /** The address step: mail hosts only, under the budgeted search, credentials-free. */
    @Test fun `the address step probes the mail hosts, never the bare domain`() {
        val body = functionBody("probeOAuthFor")
        assertTrue(
            "ConnectViewModel.probeOAuthFor must carry exactly this whole line:\n  $ADDRESS_STEP\n" +
                "This is the step every account added now goes through, and it asks before any " +
                "secret is typed. Body was:\n$body",
            body.lines().map { it.trim() }.contains(ADDRESS_STEP),
        )
        assertTrue(
            "ConnectViewModel.probeOAuthFor must carry exactly this whole line:\n  $ADDRESS_DOMAIN\n" +
                "It is the BOUND on what a discovery document may name (executed in " +
                "OAuthClientTest.oauthEndpointAllowed_*), and it is the ONE use of " +
                "autodiscoverHosts allowed in this body. Body was:\n$body",
            body.lines().map { it.trim() }.contains(ADDRESS_DOMAIN),
        )
        val offenders = body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") }
            .filter { it.contains("autodiscoverHosts") && it != ADDRESS_DOMAIN }
        assertEquals(
            "⛔ the address step must not reach for the four raw candidates: the first of them is " +
                "the organisation's website, whose OAuth document is not about the mail. The bare " +
                "domain may be READ (the line pinned just above), never PROBED. Found:\n" +
                offenders.joinToString("\n"),
            emptyList<String>(), offenders,
        )
    }

    /**
     * The manual fallback's OAuth button keeps ALL FOUR candidates, deliberately.
     *
     * ⛔ Here the reader has asserted that her server does OAuth, and may have typed its host. It
     * is the only door left for an OAuth published on the bare domain; narrowing it would answer
     * "this server doesn't support OAuth sign-in" to a sign-in that worked yesterday.
     */
    @Test fun `the manual button keeps all four candidates`() {
        val body = functionBody("connectOAuth")
        assertTrue(
            "ConnectViewModel.connectOAuth must carry exactly this whole line:\n  $MANUAL_BUTTON\n" +
                "Body was:\n$body",
            body.lines().map { it.trim() }.contains(MANUAL_BUTTON),
        )
        assertTrue(
            "ConnectViewModel.connectOAuth must carry exactly this whole line:\n  $MANUAL_DOMAIN\n" +
                "⛔ THE WHOLE LINE, not just the argument at the call site: this is the door where " +
                "the reader typed a server herself, and trimming the domain here (`.substringAfterLast" +
                "('.')` — \"com\") would let the discovery document name ANY host under that suffix " +
                "as the one the refresh token is POSTed to, for the life of the account. The rule " +
                "itself is executed in OAuthClientTest.oauthEndpointAllowed_*. Body was:\n$body",
            body.lines().map { it.trim() }.contains(MANUAL_DOMAIN),
        )
        val offenders = body.lines().map { it.trim() }.filter { it.contains("mailOAuthCandidates") }
        assertEquals(
            "⛔ the manual button must NOT be narrowed to the mail hosts. Found:\n" +
                offenders.joinToString("\n"),
            emptyList<String>(), offenders,
        )
    }

    // -- reading the file --------------------------------------------------------------------------

    /** The body of a named function, as text. Braces counted raw, as the other source lints do. */
    private fun functionBody(name: String): String {
        val lines = VIEW_MODEL.readText().lines()
        val start = lines.indexOfFirst { Regex("""\bfun $name\b""").containsMatchIn(it) }
        check(start >= 0) {
            "$name() is gone from ${VIEW_MODEL.name}: this lint reads nothing, so rename it here " +
                "too rather than let the rules pass over an empty string."
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
        val VIEW_MODEL = repoFile("app/src/main/kotlin/app/sterna/ui/connect/ConnectViewModel.kt")

        const val ADDRESS_STEP = "val chosen = discoverOAuthAmong(mailOAuthCandidates(address)) {"

        /** The only autodiscoverHosts allowed in that body: the bound, not the candidate list. */
        const val ADDRESS_DOMAIN =
            "val addressDomain = Jmap.autodiscoverHosts(address).firstOrNull().orEmpty()"
        /** The manual button's own bound, pinned whole for the same reason as [ADDRESS_DOMAIN]. */
        const val MANUAL_DOMAIN =
            "val addressDomain = Jmap.autodiscoverHosts(emailTrim).firstOrNull().orEmpty()"
        const val MANUAL_BUTTON =
            "val candidates = if (server.isNotBlank()) listOf(server.trim()) else Jmap.autodiscoverHosts(emailTrim)"

        fun repoFile(path: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, path).isFile }
                ?.let { File(it, path) }
                ?: error(
                    "cannot locate $path from ${File("").absolutePath} — this test reads a source " +
                        "file as text and needs a working directory inside the checkout",
                )
    }
}
