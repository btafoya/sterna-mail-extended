package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads `ComposeScreen.kt` and `ComposeViewModel.kt` as text
 * and proves nothing about what appears on screen. Last resort, and for the reason already written
 * in `ReaderStateResetOnLoadTest`: `ComposeScreen` is a composable, `ComposeViewModel` is an
 * `AndroidViewModel` reaching into `application.container`, and this module has neither Robolectric
 * nor instrumented tests.
 *
 * Measured on the bench, 2026-08-15: composer open on a draft, trash tapped, `Delete this draft?`
 * on screen, `settings put system user_rotation 1` — the dialog VANISHES, back to the composer,
 * without a word. No `configChanges` in the manifest, so a rotation recreates `MainActivity`, the
 * composition restarts, and a bare `remember` falls back to `false`. All FOUR of the composer's
 * dialogs were declared that way in the same file, so all four are covered here: the draft-delete
 * confirmation, the leave dialog, and the two chained pre-send guards.
 *
 * ⛔ And the fix is NOT `rememberSaveable`. That one also survives process death, where the thing
 * each dialog acts on does NOT: the ViewModel's `_editingDraft` is gone, the typed body is being
 * restored, the attachment list is not resolved yet. A restored flag would be redrawn over a
 * loading or dead-end screen, with the wrong body — and the delete dialog's Delete would land in
 * the branch where `takeEditingDraft()` returns null and nothing at all happens, in silence.
 *
 * ⚠ Each flag's lifetime is the composer's NAVIGATION ENTRY, not the process: it survives a
 * configuration change (the fix) and dies with the entry. ⛔ Do not restate it as "dies with the
 * process" — that reads as a shorter interval than the real one, and the difference is observable.
 *
 * The rule: a flag lives where the thing it acts on lives. What the user TYPED stays
 * `rememberSaveable`; a gate in front of ViewModel state goes into the ViewModel.
 */
class DialogFlagsSurviveRotationTest {

    @Test fun `every dialog flag is a ViewModel flow, and each starts closed`() {
        DIALOGS.forEach { d ->
            assertPinned(
                VIEW_MODEL,
                "private val ${d.backing} = MutableStateFlow(false)",
                "${d.what}: this is the line that carries the INITIAL value, and it must be " +
                    "`false`. A dialog that comes up on its own is a question — or a delete door — " +
                    "offered the moment the composer opens.",
            )
            assertPinned(
                VIEW_MODEL,
                "val ${d.flow}: StateFlow<Boolean> = ${d.backing}.asStateFlow()",
                "${d.what}: the flag has to be READABLE from the screen as a flow, in the same " +
                    "shape as the ~20 flows beside it, or the screen keeps a copy of its own and " +
                    "the rotation defect is back.",
            )
        }
    }

    @Test fun `the screen reads those flows instead of owning the flags`() {
        DIALOGS.forEach { d ->
            assertPinned(
                SCREEN,
                "val ${d.local} by viewModel.${d.flow}.collectAsStateWithLifecycle()",
                "${d.what}: the composer must READ the flag from the ViewModel, which survives the " +
                    "rotation that recreates MainActivity. Owning it here is the measured defect: " +
                    "the dialog disappears and the gesture has to be made again. The local name " +
                    "`${d.local}` is kept deliberately — other lints pin lines that name it.",
            )
        }
    }

    @Test fun `each door does what its name says, and they are not swapped`() {
        DIALOGS.forEach { d ->
            assertBody(
                VIEW_MODEL, "fun ${d.ask}() {", "${d.backing}.value = true",
                "${d.what}: the ASKING door must raise the flag. If it lowered it — or if the two " +
                    "bodies were swapped — the gesture that opens this dialog would do nothing at " +
                    "all, and either the question would never be asked or the dialog could never " +
                    "be dismissed.",
            )
            assertBody(
                VIEW_MODEL, "fun ${d.clear}() {", "${d.backing}.value = false",
                "${d.what}: the CLOSING door must lower the flag. If it raised it, the dialog " +
                    "could not be dismissed: Cancel, a tap outside and the confirmed action would " +
                    "all leave it redrawn on itself, with no way back to the message.",
            )
        }
    }

    @Test fun `only those two doors ever write each flag`() {
        DIALOGS.forEach { d ->
            // Every line NAMING the backing flow, not only `.value =`: an `update { false }`, a
            // `tryEmit(false)` or an alias would all be writes this rule must see too.
            val writers = codeLines(VIEW_MODEL).filter { d.backing in it }
            assertEquals(
                "${d.what}: ⛔ exactly FOUR lines of $VIEW_MODEL may so much as NAME " +
                    "`${d.backing}` — its declaration, its public flow, and the bodies of the two " +
                    "doors, in this order. Any third writer is a way for the " +
                    "flag to fall back behind the screen's back, and the measured defect returns " +
                    "with this suite still green. The one that nearly happened: a reset placed in " +
                    "`prepare()`, which `ComposeScreen` calls from a LaunchedEffect on EVERY " +
                    "activity recreation — put before `if (prepared) return` it runs on every " +
                    "rotation, and the dialog vanishes without a word, exactly as it did on " +
                    "2026-08-15. Writers found:",
                listOf(
                    "private val ${d.backing} = MutableStateFlow(false)",
                    "val ${d.flow}: StateFlow<Boolean> = ${d.backing}.asStateFlow()",
                    "${d.backing}.value = true",
                    "${d.backing}.value = false",
                ),
                writers,
            )
        }
    }

    @Test fun `nothing may own a dialog flag in remember or rememberSaveable again`() {
        val names = DIALOGS.flatMap { listOf(it.local, it.flow) }.toSet()
        val offenders = codeLines(SCREEN).filter { line ->
            names.any { it in line } && REMEMBER.containsMatchIn(line)
        }
        assertEquals(
            "⛔ no line of $SCREEN may declare a dialog flag with `remember` OR with " +
                "`rememberSaveable`.\n" +
                "  - `remember` is the measured defect: a rotation recreates MainActivity (no " +
                "`configChanges` in the manifest), the composition restarts, and the flag falls " +
                "back to false — the dialog vanishes without a word.\n" +
                "  - `rememberSaveable` is REFUSED too, and it is the trap of this fix: it also " +
                "survives PROCESS DEATH, which the target of these dialogs does not. After a kill " +
                "the ViewModel's editingDraft/editingLocalDraftId are gone and the body is still " +
                "being restored, so a restored flag would redraw the dialog over a loading or " +
                "dead-end screen with the wrong body, and the delete dialog's Delete would reach " +
                "the branch where takeEditingDraft() returns null: nothing happens, and nothing " +
                "is said.\n" +
                "A flag belongs where what it acts on lives — the ViewModel, which survives a " +
                "configuration change and dies with its navigation entry. What the user TYPED is " +
                "the other case and stays `rememberSaveable`. Offending lines:",
            emptyList<String>(),
            offenders,
        )
    }

    @Test fun `the screen never writes a flag itself, and exactly one gesture asks`() {
        DIALOGS.forEach { d ->
            val code = codeLines(SCREEN)
            val assigns = code.filter { Regex("""\b${d.local}\s*=[^=]""").containsMatchIn(it) }
            assertEquals(
                "${d.what}: ⛔ no line of $SCREEN may ASSIGN `${d.local}` — it is now a value read " +
                    "from the ViewModel, and a local write is either dead code or the rotation " +
                    "defect put back one line at a time. Lines found:",
                emptyList<String>(), assigns,
            )
            val asks = code.filter { "viewModel.${d.ask}()" in it }
            assertEquals(
                "${d.what}: exactly ONE place may raise this dialog (`viewModel.${d.ask}()`). " +
                    "Zero means the question is never asked — the leave dialog gone means edits " +
                    "dropped in silence, the pre-send guards gone means a send with no guard at " +
                    "all. A second site is a raise no test here decides. Call sites found:",
                1, asks.size,
            )
        }
    }

    @Test fun `a tap outside, and the system Back, still close every dialog`() {
        DIALOGS.forEach { d ->
            assertPinned(
                SCREEN,
                "onDismissRequest = { viewModel.${d.clear}() },",
                "${d.what}: `onDismissRequest` is what a tap outside the dialog and the system " +
                    "Back gesture land on. Emptied to `{ }` — which is what a careless move of the " +
                    "flag leaves behind, since there is no longer a local to assign — the dialog " +
                    "would stop closing that way and nothing else in the suite would see it. It " +
                    "must call the ViewModel's closing door.",
            )
        }
    }

    @Test fun `the gesture that opens each dialog is the whole condition, not just the call`() {
        assertPinned(
            SCREEN, "val attemptClose = { if (dirty && !sending) viewModel.askDiscard() else cancel() }",
            "the leave dialog is only owed when there is unsaved editing and nothing is being sent. " +
                "Counting the call site is not enough: widening this condition would put \"discard " +
                "without saving?\" in front of a composer with nothing in it, on the way out.",
        )
        assertPinned(
            SCREEN, "if (recipientCount >= MANY_RECIPIENTS) viewModel.askManyRecipients() else sendNow()",
            "the many-recipients warning is owed above the threshold and NOT below it. Loosened, it " +
                "asks on every send; tightened, a wide send goes out with no warning at all.",
        )
        assertPinned(
            SCREEN, "BackHandler(enabled = !showDiscard) { attemptClose() }",
            "while the leave dialog is up it must own the system Back — that is what this `!` says. " +
                "Now that the dialog SURVIVES a rotation, dropping the negation would let Back " +
                "re-ask the same question behind the dialog that is already asking it.",
        )
    }

    @Test fun `each send guard lowers its flag BEFORE it lets the send through`() {
        listOf(
            "viewModel.clearForgotAttachment()" to "proceedAfterAttachment()",
            "viewModel.clearManyRecipients()" to "sendNow()",
        ).forEach { (lower, proceed) ->
            val code = codeLines(SCREEN)
            val sites = code.indices.filter { code[it] == lower }
            assertTrue(
                "⛔ `$lower` must appear on a line of its own, followed IMMEDIATELY by `$proceed`. " +
                    "The two pre-send guards are chained, never simultaneous: lowered AFTER the " +
                    "send is let through, the flag would still be raised while the message goes " +
                    "out, and its dialog would sit over a composer that is already sending. " +
                    "Lines carrying it: $sites, each followed by " +
                    "${sites.map { code.getOrNull(it + 1) }}",
                sites.any { code.getOrNull(it + 1) == proceed },
            )
        }
    }

    @Test fun `every dialog is drawn behind its own flag`() {
        DIALOGS.forEach { d ->
            assertPinned(
                SCREEN, "if (${d.local}) {",
                "${d.what}: the dialog must be drawn only while its flag is raised. Remove this " +
                    "guard and the dialog is on screen permanently; invert it and it never comes " +
                    "up at all.",
            )
        }
    }

    @Test fun `only the dialog's own ways out lower its flag`() {
        DIALOGS.forEach { d ->
            val code = codeLines(SCREEN)
            val found = code.filter { "viewModel.${d.clear}()" in it }
            assertEquals(
                "${d.what}: ⛔ these are the ONLY lines of $SCREEN allowed to call " +
                    "`viewModel.${d.clear}()` — the ways OUT of the dialog itself. Counting the " +
                    "asking gesture is not enough, and this is the asymmetry that let the measured " +
                    "defect back in: a stray `${d.clear}()` dropped into the LaunchedEffect that " +
                    "calls `prepare()` replays on EVERY activity recreation, so the dialog would " +
                    "vanish on every rotation exactly as it did on 2026-08-15 — with the flag rule " +
                    "green, because the screen never names the backing flow. Calls found:",
                d.closes,
                found,
            )
        }
    }

    @Test fun `the trash icon raises the flag through the ViewModel`() {
        assertPinned(
            SCREEN,
            "onClick = { viewModel.askDraftDelete() },",
            "the trash icon must ask the ViewModel to open the door (#127). Assigning a local " +
                "flag here is what the rotation wiped; and it must still only ASK — deleting from " +
                "the icon drops the editing with no question and no way back.",
        )
    }

    // -- reading the source ---------------------------------------------------------------------

    /** Asserts exactly one code line of [path], trimmed, is [pinned] — WHOLE line, never a fragment. */
    private fun assertPinned(path: String, pinned: String, why: String) {
        val hits = codeLines(path).count { it == pinned }
        assertEquals(
            "$why\nExpected exactly one line of $path whose trimmed text is:\n    $pinned\nbut " +
                "found $hits. The line was rewritten, removed, split or duplicated — this lint " +
                "compares the WHOLE line, so a change that only LENGTHENS it lands here too.",
            1, hits,
        )
    }

    /**
     * Asserts [signature] is present once in [path] and that the very next code line is [body].
     * Pinning the two lines separately would NOT catch the bodies being swapped: both texts would
     * still be there, one apiece, and both pins would pass.
     */
    private fun assertBody(path: String, signature: String, body: String, why: String) {
        val code = codeLines(path)
        val at = code.indexOf(signature)
        assertEquals(
            "$why\nExpected exactly one line of $path whose trimmed text is:\n    $signature",
            1, code.count { it == signature },
        )
        assertEquals(
            "$why\nIn $path the line after `$signature` must be exactly:\n    $body",
            body, code.getOrNull(at + 1),
        )
    }

    /** The lines of [path] that are code, trimmed, comments taken off (they name the very words banned above). */
    private fun codeLines(path: String): List<String> = source(path).lines().mapNotNull { line ->
        val code = line.trim()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(code).trim().takeIf { it.isNotBlank() }
    }

    /** [line] up to its first `//` outside a double-quoted string; `\` escapes the next character. */
    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private fun source(path: String): String {
        val file = File(root, path)
        assertTrue("$path is not a file under ${root.absolutePath}", file.isFile)
        return file.readText()
    }

    /**
     * One of the composer's dialogs: the ViewModel flow that holds its question, the name the
     * screen reads it under (kept as it was — other lints pin lines naming it), and its two doors.
     */
    private data class Dialog(
        val what: String,
        val flow: String,
        val local: String,
        val ask: String,
        val clear: String,
        /** Every line of the SCREEN allowed to call `clear`, whole and in order. */
        val closes: List<String>,
    ) {
        val backing = "_$flow"

        /** An ASSIGNMENT to the backing flow — `=` not `==`, so a read is never mistaken for a write. */
        val write = Regex("""${backing}\s*\.\s*value\s*=[^=]""")
    }

    private companion object {
        const val SCREEN = "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"
        const val VIEW_MODEL = "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"

        /** `remember` and `rememberSaveable` both, and `\b` keeps the first from matching the second. */
        val REMEMBER = Regex("""\bremember(Saveable)?\b""")

        val DIALOGS = listOf(
            Dialog(
                what = "the trash icon's \"Delete this draft?\" (#127)",
                flow = "pendingDraftDelete", local = "pendingDraftDelete",
                ask = "askDraftDelete", clear = "clearDraftDelete",
                closes = listOf(
                    "onDismissRequest = { viewModel.clearDraftDelete() },",
                    "viewModel.clearDraftDelete()",
                    "TextButton(onClick = { viewModel.clearDraftDelete() }) {",
                ),
            ),
            Dialog(
                what = "the leave dialog, \"You haven't saved your changes\" (#35, #127)",
                flow = "pendingDiscard", local = "showDiscard",
                ask = "askDiscard", clear = "clearDiscard",
                closes = listOf(
                    "onDismissRequest = { viewModel.clearDiscard() },",
                    "DiscardChoice.CANCEL -> TextButton(onClick = { viewModel.clearDiscard() }, enabled = enabled) {",
                    "viewModel.clearDiscard()",
                    "viewModel.clearDiscard()",
                ),
            ),
            Dialog(
                what = "the pre-send \"forgot an attachment?\" guard",
                flow = "pendingForgotAttachment", local = "showForgotAttachment",
                ask = "askForgotAttachment", clear = "clearForgotAttachment",
                closes = listOf(
                    "onDismissRequest = { viewModel.clearForgotAttachment() },",
                    "viewModel.clearForgotAttachment()",
                    "TextButton(onClick = { viewModel.clearForgotAttachment() }) {",
                ),
            ),
            Dialog(
                what = "the pre-send \"many recipients\" guard",
                flow = "pendingManyRecipients", local = "showManyRecipients",
                ask = "askManyRecipients", clear = "clearManyRecipients",
                closes = listOf(
                    "onDismissRequest = { viewModel.clearManyRecipients() },",
                    "viewModel.clearManyRecipients()",
                    "TextButton(onClick = { viewModel.clearManyRecipients() }) {",
                ),
            ),
        )

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SCREEN).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }
    }
}
