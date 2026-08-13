package app.sterna.ui.connect

import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.autoconfig.MailAutoconfigResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two pure decisions behind prefilling the IMAP form from the autoconfig cascade — EXECUTED,
 * with pinned arguments, never re-read from the source:
 *
 *  - [shouldDiscoverImapSettings]: whether the cascade may set out at all. The rules that matter
 *    most are the refusals — an armed provider chip outranks the cascade, OAuth has no fields to
 *    fill, a single hand-typed host disarms it, offline it must not even start;
 *  - [presetFilledWith]: what the verdict may write. Per side, atomically, and NEVER over a
 *    hand-typed host — nor a discovered port/security slid under one.
 *
 * ⚠ Every discovered value in the fixture differs from [PresetForm]'s defaults ("993"/TLS,
 * "465"/TLS) on purpose: a mutation that forgets to write one field must be distinguishable from
 * a form that already held it.
 */
class ImapDiscoveryTest {

    private val email = "user@example.org"

    private val found = MailAutoconfigResult.Found(
        incoming = MailEndpoint("imap.example.org", 143, ConnectionSecurity.STARTTLS),
        outgoing = MailEndpoint("smtp.example.org", 587, ConnectionSecurity.STARTTLS),
        username = "user@example.org",
    )

    // ---- shouldDiscoverImapSettings: when the cascade may set out ----

    @Test fun `all conditions met - the cascade sets out`() {
        assertTrue(
            "IMAP password form, no chip, no OAuth, both hosts untouched, valid address, online: " +
                "this is the one case discovery must run in, or the feature does not exist.",
            shouldDiscoverImapSettings(ConnectRoute.IMAP_PASSWORD, PresetForm.NONE, email, online = true),
        )
    }

    @Test fun `offline - the cascade stays home`() {
        assertFalse(
            "offline the cascade must not set out at all — otherwise it fires requests and waits " +
                "in airplane mode, and the screen lies about what is happening.",
            shouldDiscoverImapSettings(ConnectRoute.IMAP_PASSWORD, PresetForm.NONE, email, online = false),
        )
    }

    @Test fun `an armed provider chip outranks the cascade`() {
        assertFalse(
            "preset.selected != null means the user picked a hard-coded provider; those come " +
                "BEFORE the cascade, which must not run under them.",
            shouldDiscoverImapSettings(
                ConnectRoute.IMAP_PASSWORD, PresetForm(selected = "Gmail"), email, online = true,
            ),
        )
    }

    @Test fun `an OAuth preset disarms the cascade`() {
        assertFalse(
            "an OAuth chip has no host fields on screen to fill: discovery under it would find " +
                "values nothing displays (WYSIWYG says no).",
            shouldDiscoverImapSettings(
                ConnectRoute.IMAP_PASSWORD, PresetForm(oauth = true), email, online = true,
            ),
        )
    }

    @Test fun `a hand-typed IMAP host alone disarms the cascade`() {
        assertFalse(
            "one host typed by hand means the user is entering their own settings; the cascade " +
                "must not set out to second-guess them.",
            shouldDiscoverImapSettings(
                ConnectRoute.IMAP_PASSWORD, PresetForm(imapHost = "x"), email, online = true,
            ),
        )
    }

    @Test fun `a hand-typed SMTP host alone disarms the cascade`() {
        assertFalse(
            "same rule on the outgoing side: smtpHost typed, no discovery.",
            shouldDiscoverImapSettings(
                ConnectRoute.IMAP_PASSWORD, PresetForm(smtpHost = "x"), email, online = true,
            ),
        )
    }

    @Test fun `a JMAP route never discovers IMAP settings`() {
        assertFalse(
            "the JMAP path has its own discovery (RFC 8620) and no IMAP fields on screen; this " +
                "cascade is for the IMAP password form only.",
            shouldDiscoverImapSettings(ConnectRoute.JMAP_AUTODISCOVER, PresetForm.NONE, email, online = true),
        )
    }

    @Test fun `an invalid address keeps the cascade home`() {
        assertFalse(
            "no @, no domain to derive candidate hosts from: nothing to probe, so nothing starts.",
            shouldDiscoverImapSettings(ConnectRoute.IMAP_PASSWORD, PresetForm.NONE, "pas-une-adresse", online = true),
        )
    }

    // ---- presetFilledWith: what the verdict may write ----

    @Test fun `both sides untouched - all six values land, nothing else moves`() {
        assertEquals(
            "with both host fields untouched the verdict fills BOTH sides — host, port (as text) " +
                "and security, together — and touches nothing else on the form.",
            PresetForm(
                selected = null,
                oauth = false,
                imapHost = "imap.example.org",
                imapPort = "143",
                imapSecurity = ConnectionSecurity.STARTTLS,
                smtpHost = "smtp.example.org",
                smtpPort = "587",
                smtpSecurity = ConnectionSecurity.STARTTLS,
                appPasswordUrl = null,
            ),
            presetFilledWith(PresetForm.NONE, found),
        )
    }

    @Test fun `a hand-typed IMAP host keeps its whole side - the SMTP side is still written`() {
        val typed = PresetForm(imapHost = "mien.tld", imapPort = "1993", imapSecurity = ConnectionSecurity.TLS)
        assertEquals(
            "the incoming side the user typed must stay STRICTLY intact — host, port AND security: " +
                "a discovered port or security under a hand-typed host is a configuration nobody " +
                "wrote. The untouched outgoing side is still filled.",
            PresetForm(
                selected = null,
                oauth = false,
                imapHost = "mien.tld",
                imapPort = "1993",
                imapSecurity = ConnectionSecurity.TLS,
                smtpHost = "smtp.example.org",
                smtpPort = "587",
                smtpSecurity = ConnectionSecurity.STARTTLS,
                appPasswordUrl = null,
            ),
            presetFilledWith(typed, found),
        )
    }

    @Test fun `a hand-typed SMTP host keeps its whole side - the IMAP side is still written`() {
        val typed = PresetForm(smtpHost = "envoi.mien.tld", smtpPort = "2525", smtpSecurity = ConnectionSecurity.TLS)
        assertEquals(
            "same rule mirrored: the outgoing side the user typed stays intact, the untouched " +
                "incoming side is filled.",
            PresetForm(
                selected = null,
                oauth = false,
                imapHost = "imap.example.org",
                imapPort = "143",
                imapSecurity = ConnectionSecurity.STARTTLS,
                smtpHost = "envoi.mien.tld",
                smtpPort = "2525",
                smtpSecurity = ConnectionSecurity.TLS,
                appPasswordUrl = null,
            ),
            presetFilledWith(typed, found),
        )
    }

    @Test fun `both sides typed - the form comes back identical`() {
        val typed = PresetForm(
            imapHost = "mien.tld", imapPort = "1993", imapSecurity = ConnectionSecurity.TLS,
            smtpHost = "envoi.mien.tld", smtpPort = "2525", smtpSecurity = ConnectionSecurity.STARTTLS,
        )
        assertEquals(
            "with both hosts typed the verdict has nowhere to land: the form must come back " +
                "IDENTICAL, not merely with its hosts preserved.",
            typed,
            presetFilledWith(typed, found),
        )
    }

    @Test fun `selected and appPasswordUrl are never the cascade's to touch`() {
        val armed = PresetForm(selected = "Gmail", appPasswordUrl = "https://myaccount.google.com/apppasswords")
        val filled = presetFilledWith(armed, found)
        assertEquals(
            "presetFilledWith must never touch the chip selection, even on a form it fills — " +
                "which chip is armed is the screen's decision, not the cascade's.",
            "Gmail", filled.selected,
        )
        assertEquals(
            "nor the app-password link that selection carries.",
            "https://myaccount.google.com/apppasswords", filled.appPasswordUrl,
        )
        assertFalse("nor the oauth flag.", filled.oauth)
    }
}

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and same disclaimer as
 * [ImapAuthRejectedWiringTest]: `ConnectViewModel` is an `AndroidViewModel` on the app container
 * and `ConnectScreen` is a composable, so this module (no Robolectric, no instrumented tests) can
 * run neither. The decisions are EXECUTED above ([ImapDiscoveryTest]); what is held here is that
 * the screen and the ViewModel actually run THOSE decisions and the REAL cascade, by reading the
 * source.
 *
 * ⛔ Every pin is a WHOLE LINE, trimmed, compared by strict equality, and required to occur
 * exactly once — never `contains` on a fragment, which is blind to any mutation that lengthens
 * the line (three prior bites in this repo, two on irreversible server writes).
 *
 * What this does NOT hold: that the lines run, in what order, or what the coroutine around them
 * does. The guard's own semantics and the fill's are what the executed tests above prove.
 */
class ImapDiscoveryWiringTest {

    @Test
    fun `the ViewModel gates discovery on the executed decision, with real connectivity`() {
        assertPinnedLine(
            VIEW_MODEL,
            "if (!shouldDiscoverImapSettings(route, preset, email, online = hasUsableNetwork(app))) return",
            "Pinned WITH its online argument: this is the seam between the screen's triggers and " +
                "the decision ImapDiscoveryTest executes. Hand it anything but " +
                "hasUsableNetwork(app) — `online = true` is one keystroke — and the cascade fires " +
                "requests in airplane mode while the tests above stay green.",
        )
    }

    @Test
    fun `the ViewModel launches the real cascade, not a stand-in`() {
        assertPinnedLine(
            VIEW_MODEL,
            "val found = discoverMailAutoconfig(address) as? MailAutoconfigResult.Found ?: return@launch",
            "Pinned because this is the one call to the real cascade (core:data " +
                "discoverMailAutoconfig — address only, shipped budget) and the one place " +
                "NotFound becomes silence. Replace it with a stand-in, or surface NotFound as a " +
                "state, and everything above stays green.",
        )
        val calls = Regex(Regex.escape("discoverMailAutoconfig(")).findAll(source(VIEW_MODEL)).count()
        assertEquals(
            "discoverMailAutoconfig( must occur exactly once in $VIEW_MODEL — the pinned line. A " +
                "second call site is one this lint does not read; zero means the cascade is no " +
                "longer wired at all. Found $calls.",
            1, calls,
        )
    }

    @Test
    fun `the screen writes the verdict through the executed fill, into the visible fields`() {
        assertPinnedLine(
            SCREEN,
            "preset = presetFilledWith(preset, it)",
            "Pinned because this is the ONLY way a discovered value may reach the user: written " +
                "into the visible fields' state (`preset`), through the fill ImapDiscoveryTest " +
                "executes. Any other assignment — fields bypassed, Connect reading the verdict " +
                "directly — uses a value the screen never showed (WYSIWYG).",
        )
        val calls = Regex(Regex.escape("presetFilledWith(")).findAll(source(SCREEN)).count()
        assertEquals(
            "presetFilledWith( must occur exactly once in $SCREEN — the pinned line. A second " +
                "call site could write a verdict somewhere this lint does not read. Found $calls.",
            1, calls,
        )
    }

    @Test
    fun `the screen consumes the verdict, and only after writing it`() {
        assertPinnedLine(
            SCREEN,
            "viewModel.consumeImapDiscovery()",
            "Pinned because this is what makes the verdict ONE-SHOT: without it the Found stays " +
                "in the StateFlow, every new collector (each rotation) replays it, and a field " +
                "the user cleared by hand refills itself. Nothing above can see that — the fill " +
                "itself is idempotent, so the suite stays green with the call gone.",
        )
        val lines = source(SCREEN).lines().map { it.trim() }
        val write = lines.indexOf("preset = presetFilledWith(preset, it)")
        val consume = lines.indexOf("viewModel.consumeImapDiscovery()")
        assertTrue(
            "in $SCREEN the write `preset = presetFilledWith(preset, it)` (line index $write) " +
                "must come BEFORE `viewModel.consumeImapDiscovery()` (line index $consume) — " +
                "both pinned unique, so indexOf is exact. Consumed first, the verdict is nulled " +
                "before it lands: a cancellation between the two drops it entirely, and the " +
                "adjacent pair reads as write-then-retire, not the reverse.",
            write in 0 until consume,
        )
    }

    @Test
    fun `core-data's UI entry point is the real cascade, undecorated`() {
        assertPinnedLine(
            CORE_AUTOCONFIG,
            "suspend fun discoverMailAutoconfig(emailAddress: String): MailAutoconfigResult =",
            "Pinned because this signature IS the contract the ViewModel lint above relies on: " +
                "address only, no injected client, no caller-supplied budget. Widen it and every " +
                "call site can hand the cascade something these lints never read.",
        )
        assertPinnedLine(
            CORE_AUTOCONFIG,
            "discoverMailSettings(emailAddress)",
            "Pinned because the delegation must reach the REAL cascade with the address as " +
                "typed: reroute it to a stand-in, or wrap a 60 s budget around it, and " +
                "ImapDiscoveryTest — which never runs this file — stays green.",
        )
        val lines = source(CORE_AUTOCONFIG).lines().map { it.trim() }
        val sig = lines.indexOf("suspend fun discoverMailAutoconfig(emailAddress: String): MailAutoconfigResult =")
        assertEquals(
            "in $CORE_AUTOCONFIG the line after the discoverMailAutoconfig signature must BE the " +
                "delegation — anything inserted between them (a wrapper opening, a budget, a " +
                "cache) would keep both pinned lines intact while changing what the entry point " +
                "does.",
            "discoverMailSettings(emailAddress)",
            lines[sig + 1],
        )
    }

    // -- reading the files -------------------------------------------------------------------------

    /** Exactly one line of [path] whose TRIMMED text equals [pinned] — 0 or 2+ both fail. */
    private fun assertPinnedLine(path: String, pinned: String, why: String) {
        val hits = source(path).lines().count { it.trim() == pinned }
        assertEquals(
            "expected exactly one line of $path whose trimmed text is:\n    $pinned\n" +
                "but found $hits. The line was rewritten, removed, split, or duplicated — this " +
                "lint compares the WHOLE line, so any change to it (even one that only lengthens " +
                "it) lands here. $why",
            1, hits,
        )
    }

    private fun source(path: String): String = locate(path).readText()

    private companion object {
        const val VIEW_MODEL = "app/src/main/kotlin/app/sterna/ui/connect/ConnectViewModel.kt"
        const val SCREEN = "app/src/main/kotlin/app/sterna/ui/connect/ConnectScreen.kt"
        const val CORE_AUTOCONFIG = "core/data/src/main/kotlin/app/sterna/core/data/autoconfig/MailAutoconfig.kt"

        /** Repo root, walked up from the module's working directory (as the other source lints do). */
        fun locate(path: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, path).isFile }
                ?.let { File(it, path) }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads a " +
                        "source file as text and needs a working directory inside the checkout",
                )
    }
}
