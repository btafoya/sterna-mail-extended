package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * ⚠ SOURCE LINT, NOT A BEHAVIOUR TEST. It reads `ComposeViewModel.kt` and `ComposeScreen.kt` as
 * text and compares whole trimmed lines. It proves nothing about what runs.
 *
 * It exists because neither file can be built here: `ComposeViewModel` is an `AndroidViewModel`
 * and `ComposeScreen` is a composable, there is no Robolectric and no instrumented test in this
 * module. The DECISIONS both of them make are pure functions with tests that EXECUTE them
 * ([PgpEncryptArgsTest], [DraftSaveAllowedTest], and `PgpModeEncryptsTest` in core:data); what
 * nothing can see is whether these two files still ASK them. That is all this file adds — and it
 * is not enough on its own, exactly as `pgpEncryptArgs` being perfect is not enough if the send
 * site builds its own key array beside it.
 *
 * Whole lines and CONTIGUOUS blocks, never `contains`: a `contains` check is blind to every
 * mutation that lengthens the line it looks at (bitten twice on 2026-08-04), and a per-line check
 * is blind to a statement slipped in between two.
 */
class PgpModeWiringTest {

    @Test fun `the send site passes the arguments the pure function decided, and builds none itself`() {
        assertEquals(
            "buildPgpEntity no longer hands signAndEncrypt exactly what pgpEncryptArgs decided. " +
                "Two ways this breaks, both silent: pass a key where args.signKeyId is, and the " +
                "'encrypt without signing' mode signs anyway — the composer showed one thing and " +
                "the message says another. Rebuild the recipient array here instead of using " +
                "args.recipientKeyIds, and the sender's own key can be dropped from it: every " +
                "unsigned encrypted message in Sent becomes unreadable, on the sender's own " +
                "phone, definitively.",
            listOf(
                "val args = pgpEncryptArgs(mode, selfKeyId, recipientKeys.toList())",
                "when (val r = pgp.signAndEncrypt(payload.toByteArray(Charsets.UTF_8), " +
                    "args.signKeyId, args.recipientKeyIds, interactionResult)) {",
            ),
            block(VIEW_MODEL, "val args = pgpEncryptArgs(", 2),
        )
    }

    @Test fun `the send reads the mode once and hands that one value to the entity builder`() {
        // Choosing the branch and choosing the signer are separated by pgp.findKeys, a suspension
        // that round-trips to the provider — and the mode moves under it: deriveAutoMode drops an
        // untouched encrypt-by-default lock to OFF as soon as a recipient turns out to have no
        // key, and the address fields stay editable while a send is in flight. Read a second time
        // inside buildPgpEntity, that read can answer OFF, and the message leaves UNSIGNED while
        // the composer had said "encrypted and signed". One read, passed down as an argument, is
        // what makes that impossible rather than unlikely.
        assertEquals(
            "the send no longer captures the mode once for the whole message.",
            listOf("val mode = _pgpMode.value", "val pgpEntity = if (mode != PgpMode.OFF) {"),
            block(VIEW_MODEL, "val mode = _pgpMode.value", 2),
        )
        assertEquals(
            "buildPgpEntity stopped taking the mode as a parameter — it is reading _pgpMode.value " +
                "again, after the provider round-trip, and the signature the message carries now " +
                "depends on which coroutine resumed first rather than on what the composer showed.",
            listOf(
                "private suspend fun buildPgpEntity(",
                "credentials: AccountCredentials,",
                "allRecipients: List<String>,",
                "textBody: String,",
                "htmlBody: String?,",
                "attachments: List<EmailBodyPart>,",
                "mode: PgpMode,",
                "interactionResult: Intent?,",
            ),
            block(VIEW_MODEL, "private suspend fun buildPgpEntity(", 8),
        )
    }

    @Test fun `the short tap is still the three-stop cycle, and cannot walk into the unsigned mode`() {
        // nextPgpMode is pure and DraftSaveAllowedTest executes it — but nothing executes the line
        // that calls it. Rewrite this one line to hand setPgpMode ENCRYPT_UNSIGNED after SIGN and
        // every test in the repo stays green, while two taps from plain now send mail that proves
        // nothing about who wrote it, which nobody asked for. The long-press menu is the only way
        // into that mode; this line is what keeps it that way.
        assertEquals(
            "the lock's short tap no longer walks nextPgpMode's cycle verbatim.",
            listOf("fun cyclePgpMode() = setPgpMode(nextPgpMode(_pgpMode.value))"),
            block(VIEW_MODEL, "fun cyclePgpMode()", 1),
        )
    }

    @Test fun `each mode is bound to the face it was given, and no other`() {
        // The COMPLEMENT of PgpModeIconFacesTest, which executes pgpModeIcon and measures that the
        // four faces it returns are four distinct drawings. That measurement is blind to WHICH mode
        // wears WHICH face: swap the faces of OFF and ENCRYPT and the four drawings are still
        // pairwise distinct, so it stays green while the composer draws a closed padlock on a
        // plaintext message. Only the four pairings below say who wears what, and they can only be
        // pinned as text — a test that recomputed the expected face would be a copy of this file.
        assertEquals(
            "pgpModeIcon changed. If two modes now draw the same icon, the composer shows the " +
                "same padlock for a signed and an unsigned message and there is no other " +
                "permanent sign of which one is in force; if the pairings were merely swapped, " +
                "the padlock lies about the message.",
            listOf(
                "internal fun pgpModeIcon(mode: PgpMode): ImageVector = when (mode) {",
                "PgpMode.OFF -> Icons.Filled.LockOpen",
                "PgpMode.SIGN -> Icons.Filled.Draw",
                "PgpMode.ENCRYPT -> Icons.Filled.Lock",
                "PgpMode.ENCRYPT_UNSIGNED -> Icons.Filled.VpnKey",
            ),
            block(TEXT, "internal fun pgpModeIcon(", 5),
        )
    }

    @Test fun `no key array is assembled by hand anywhere in the composer's view model`() {
        // The absence half. The line that was here — `(recipientKeys.toList() + signKeyId)
        // .distinct().toLongArray()` — is the one that must live in pgpEncryptArgs and nowhere
        // else, because it is the encrypt-to-self, and a JVM test can only run it there.
        assertEquals(
            "a key array is being built inside ComposeViewModel again. Whatever it computes is " +
                "invisible to every test in this repo: nothing here can instantiate this class.",
            emptyList<String>(),
            code(VIEW_MODEL).filter { it.contains(".distinct().toLongArray()") },
        )
    }

    @Test fun `recipient keys are looked up for every encrypting mode, not for one of them`() {
        assertEquals(
            "updateRecipientKeys stopped asking `does this mode encrypt`. Written as a comparison " +
                "with one mode, the unsigned mode looks up NO recipient key at all: the composer " +
                "shows no missing-key warning, the Send button stays enabled, and the send fails " +
                "at the provider (or encrypts to nobody but the sender).",
            listOf(
                "val care = _pgpAvailable.value &&",
                "(account?.pgpEncryptByDefault == true || _pgpMode.value.encrypts)",
            ),
            block(VIEW_MODEL, "val care = _pgpAvailable.value", 2),
        )
    }

    @Test fun `the compose screen asks whether the mode encrypts, never which mode it is`() {
        // Three sites in that file read it: the Send gate (keysReady), the per-recipient red flag
        // (missingKeyFor) and the "the subject is not encrypted" note. Each one written as
        // `== PgpMode.ENCRYPT` again silently drops the unsigned mode: the note disappears, the
        // recipients with no key stop being flagged, and Send is offered on a message that cannot
        // be encrypted to them.
        //
        // ⚠ This is an ABSENCE over the whole file, so it says nothing about a site that stops
        // reading the mode at all. It is the cheapest guard that survives a lengthened line.
        assertEquals(
            "ComposeScreen compares against a single encrypting mode again.",
            emptyList<String>(),
            code(SCREEN).filter { it.contains("== PgpMode.ENCRYPT") },
        )
    }

    @Test fun `the bar draws the mode in force and each menu row draws its own`() {
        // pgpModeIcon moved out of ComposeScreen, and with it the only pin that read this file's
        // two call sites. What they PASS is the whole point and no other test can see it: write
        // `pgpModeIcon(pgpMode)` in the menu row and all four entries wear the icon of the current
        // mode; write `pgpModeIcon(entry)` in the bar and it never changes. Either way the four
        // faces stay four distinct drawings, so PgpModeIconFacesTest measures them and stays green
        // while the composer shows one face for another message's promise — which is the defect
        // the bench reported, only worse.
        //
        // The bar reads `pgpMode` (what is in force), the menu row reads `entry` (the row's own
        // mode), in that order down the file. ⛔ Two calls, no more: a third would be a face drawn
        // somewhere nothing here describes.
        assertEquals(
            "the composer's two pgpModeIcon call sites changed. If the bar stopped passing " +
                "pgpMode it shows a mode that is not in force; if a menu row stopped passing " +
                "entry, every row draws the same face and the menu says nothing.",
            listOf("pgpMode", "entry"),
            callArguments(File(root, SCREEN).readText(), "pgpModeIcon"),
        )
    }

    /** Code lines of [path], trimmed; comment lines and blanks dropped. */
    private fun code(path: String): List<String> = File(root, path).readLines()
        .map { it.trim() }
        .filter {
            it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*")
        }

    /**
     * [count] CONSECUTIVE code lines of [path] from the single one starting with [prefix]. A prefix
     * matching anything but exactly one line returns a message instead, so an assertion can never
     * pass by matching nothing.
     */
    private fun block(path: String, prefix: String, count: Int): List<String> {
        val lines = code(path)
        val starts = lines.indices.filter { lines[it].startsWith(prefix) }
        val start = starts.singleOrNull()
            ?: return listOf("«${starts.size} code lines of $path start with `$prefix`»")
        return lines.subList(start, minOf(start + count, lines.size))
    }

    /** The argument text of every `name(...)` call in [text], in source order. */
    private fun callArguments(text: String, name: String): List<String> =
        Regex("""\b${Regex.escape(name)}\(""").findAll(text)
            .map { balanced(text, it.range.last, '(', ')').trim() }
            .toList()

    /** [text] from the first [open] at or after [from], up to the [close] that balances it. */
    private fun balanced(text: String, from: Int, open: Char, close: Char): String {
        val start = text.indexOf(open, from).let { if (it < 0) from else it + 1 }
        var depth = 1
        var i = start
        while (i < text.length && depth > 0) {
            when (text[i]) {
                open -> depth++
                close -> depth--
            }
            i++
        }
        return text.substring(start, if (depth == 0) i - 1 else text.length)
    }

    private companion object {
        const val VIEW_MODEL = "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"
        const val SCREEN = "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"
        const val TEXT = "app/src/main/kotlin/app/sterna/ui/compose/ComposeText.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, VIEW_MODEL).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }
    }
}
