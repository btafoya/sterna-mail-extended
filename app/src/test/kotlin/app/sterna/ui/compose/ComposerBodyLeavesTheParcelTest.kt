package app.sterna.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and same disclaimer as
 * [DialogFlagsSurviveRotationTest] next door: `ComposeScreen` is a composable and `MainActivity` is
 * an activity, this module has neither Robolectric nor instrumented tests, so nothing here can be
 * EXECUTED. The decisions themselves are executed in [ComposerResumeSlotTest]; what is left is the
 * WIRING, and this file reads it as text.
 *
 * What it stands for, measured on the bench: reply to a message whose body is ~324 kB, let the
 * quote land in the composer, press HOME. The activity dies on `TransactionTooLargeException` — a
 * 1 340 240 byte parcel under `androidx.lifecycle.BundlableSavedStateRegistry.key` — and the draft
 * is gone without a word. `body` and `initialBody` were each a `rememberSaveable`, so each carried
 * the WHOLE body into the parcel, and `Parcel.writeString` writes UTF-16: 4 bytes of parcel per
 * character of body. Everything else on that screen is ~12 kB.
 *
 * ⛔ Every rule compares WHOLE trimmed lines, never `contains`: a fragment match is blind to any
 * mutation that LENGTHENS the line, which is how three defects got through on this repo.
 */
class ComposerBodyLeavesTheParcelTest {

    // -- the body and its baseline no longer travel in the parcel --------------------------------

    @Test
    fun `the body is held in the resume state, not in a saveable`() {
        assertPinned(
            SCREEN, "var body by resume.bodyState",
            "the body must be delegated to the resume state, whose saver puts a short TOKEN in the " +
                "parcel and the text in an app-private file. Put back as a `rememberSaveable`, a " +
                "long reply kills the activity on HOME and the user loses everything they typed.",
        )
    }

    @Test
    fun `the baseline is held in the resume state, not in a saveable`() {
        assertPinned(
            SCREEN, "var initialBody by resume.baselineState",
            "the baseline is a SECOND full copy of the body in the parcel, and it comes back as " +
                "TEXT because `insertSignatureBlock(quoted = initialBody)` and `rewrite(initialBody)` " +
                "are handed it verbatim — a digest would break the signature rules instead.",
        )
    }

    /**
     * ⛔ THE MUTATION THAT SURVIVED THE FIRST ROUND. Turned into a plain `remember`, this line
     * switches the whole change off: the body is no longer parcelled at all, so nothing is
     * restored after a process death and the draft is lost — while the negative rule below is
     * MORE satisfied than before. A rule that rewards deleting what it protects is not a rule,
     * so the saveable is pinned positively, whole line, right here.
     */
    @Test
    fun `the resume state is parcelled through its own saver`() {
        assertPinned(
            SCREEN, "val resume = rememberSaveable(saver = composerResumeSaver(resumeSlot)) {",
            "`rememberSaveable(saver = …)` is what puts the token in the parcel and reads it back. " +
                "A plain `remember` compiles, passes every other rule here, and silently drops the " +
                "user's text on every process death.",
        )
    }

    /**
     * ⛔ ONE FILE PER NAVIGATION ENTRY, and two entries ARE reachable: `MainActivity` is singleTask,
     * `onNewIntent` feeds `pendingMailto`, and the host navigates to `compose` unguarded, so a
     * mailto: link or a share arriving with the composer open stacks a SECOND composer on the first.
     * Sharing one file, closing the second erased the first's text and the long reply underneath
     * came back blank — a loss this change would have CREATED.
     */
    @Test
    fun `each composer entry gets a slot of its own`() {
        assertPinned(
            SCREEN, "val slotId = rememberSaveable { newComposerSlotId() }",
            "the id must be `rememberSaveable` — a few bytes of parcel — or the composer coming " +
                "back from a process death would look for a file it can no longer name.",
        )
        assertPinned(
            SCREEN, "val resumeSlot = remember(context, slotId) { ComposerResumeSlot(composerResumeFile(context, slotId)) }",
            "and the slot must be keyed on that id, so each composer erases only its own file.",
        )
    }

    /** Where the cleartext lives, pinned: app-private storage, through the application context. */
    @Test
    fun `the slot lives in app-private storage`() {
        assertPinned(
            SLOT, "composerResumeSlotFile(context.applicationContext.filesDir, slotId)",
            "`filesDir` is app-private. Moved to `externalCacheDir` or any other shared location, " +
                "the body of every draft — including one being ENCRYPTED — becomes readable by " +
                "anything on the device. `applicationContext`, not the activity's, so a remembered " +
                "slot cannot hold a destroyed activity alive.",
        )
    }

    @Test
    fun `no line of the screen puts the body or its baseline back into the parcel`() {
        val offenders = codeLines(SCREEN).filter { line ->
            SAVEABLE.containsMatchIn(line) && (BODY.containsMatchIn(line) || BASELINE.containsMatchIn(line))
        }
        assertEquals(
            "no line of $SCREEN may name `rememberSaveable` together with `body` or `initialBody`: " +
                "that is the parcel the 324 kB reply overflows, and it kills the activity on HOME " +
                "with the draft in it. Found:\n" + offenders.joinToString("\n") { "    $it" },
            emptyList<String>(), offenders,
        )
    }

    // -- and the file goes away when the composer is done with it --------------------------------

    @Test
    fun `all four ways out of the composer close the slot`() {
        assertPinned(
            SCREEN, "resume.close(resumeSlot)",
            "three of the composer's FOUR exits close the slot on a line of their own — the Done " +
                "state (send, save-as-draft, schedule), the deleted local draft, and `cancel` " +
                "(abandon, and a plain back with nothing changed). Without the close, the body " +
                "stays in cleartext under `filesDir` after the message is gone, including one that " +
                "was being ENCRYPTED.",
            times = 3,
        )
    }

    /**
     * ⛔ THE FOURTH EXIT, and it was missed. The trash on a SERVER draft — `editingLocalDraftId`
     * null, i.e. the ordinary case — leaves through the host's `popBackStack()`, not through any
     * of the three above, so nothing closed the slot: the entry's teardown then wrote the file
     * back out with `closed` still false, and the body of the draft just DELETED stayed on disk,
     * consumable by nobody. The encrypted case takes the same branch.
     *
     * Closed inside the TAKER, so it happens exactly when the deletion really departs: the taker
     * is only called inside the host's navigation guard, and a tap the guard refuses must neither
     * consume the draft nor throw the text away.
     */
    @Test
    fun `deleting a server draft closes the slot too`() {
        assertPinned(
            SCREEN, "onDeleteDraft { viewModel.takeEditingDraft()?.also { resume.close(resumeSlot) } }",
            "the close belongs on the taker's result: outside it, a tap the navigation guard drops " +
                "would erase the draft's text while deleting nothing at all; missing altogether, " +
                "the deleted draft's body stays in cleartext under `filesDir`.",
        )
    }

    @Test
    fun `a launch with no saved state sweeps an orphaned slot`() {
        assertPinned(
            ACTIVITY, SWEEP,
            "with no saved state there is no `rememberSaveable` left to consume ANY slot, so every " +
                "file still there is the orphan of a crash — this is the only moment that can be " +
                "said without guessing. It sweeps them ALL, which is safer and not less so: there " +
                "is one file per composer entry now, and a sweep of a single name would leave the " +
                "others' cleartext under `filesDir` forever.",
        )
        val lines = codeLines(ACTIVITY)
        val opener = lines.indexOf("if (savedInstanceState == null) {")
        val sweep = lines.indexOf(SWEEP)
        val after = lines.indexOf("val settings = application.container.settingsRepository")
        assertTrue(
            "in $ACTIVITY the sweep (line index $sweep) must sit INSIDE the " +
                "`if (savedInstanceState == null) {` block (opens at $opener, and the block is over " +
                "by $after). Swept on every recreation — a rotation, a theme flip, a language " +
                "change — it would delete the very file the recomposition is about to read, and the " +
                "draft would be lost by the fix meant to save it.",
            opener in 0 until sweep && sweep < after,
        )
    }

    // -- nothing is said to the user, so nothing is translated ------------------------------------

    /**
     * ⛔ The property is LOCAL to this change, not a freeze of `strings.xml`: a count pinned there
     * would turn red on the first unrelated branch that adds a label, under a test named "the body
     * leaves the parcel", and nobody would know why.
     */
    @Test
    fun `the resume slot says nothing to the user`() {
        val speaks = codeLines(SLOT).filter { "R.string" in it || "stringResource" in it }
        assertEquals(
            "$SLOT must name no string resource at all. This change is invisible on purpose: no " +
                "\"draft recovered\" banner, no resume error. One new string reopens a bench pass in " +
                "nine languages for a mechanism the user is not supposed to notice. Found:\n" +
                speaks.joinToString("\n") { "    $it" },
            emptyList<String>(), speaks,
        )
    }

    // -- reading the sources ---------------------------------------------------------------------

    /** Asserts exactly [times] code lines of [path], trimmed, ARE [pinned] — whole line, never a fragment. */
    private fun assertPinned(path: String, pinned: String, why: String, times: Int = 1) {
        val hits = codeLines(path).count { it == pinned }
        assertEquals(
            "$why\nExpected exactly $times line(s) of $path whose trimmed text is:\n    $pinned" +
                "\nbut found $hits. The line was rewritten, removed, split or duplicated — this " +
                "lint compares the WHOLE line, so a change that only LENGTHENS it lands here too.",
            times, hits,
        )
    }

    /** [path]'s code as trimmed whole lines, comments cut: prose must never satisfy a rule. */
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

    private companion object {
        const val SCREEN = "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"
        const val ACTIVITY = "app/src/main/kotlin/app/sterna/MainActivity.kt"
        const val SLOT = "app/src/main/kotlin/app/sterna/ui/compose/ComposerResumeSlot.kt"

        const val SWEEP = "ComposerResumeSlot.sweep(filesDir)"

        val SAVEABLE = Regex("""\brememberSaveable\b""")
        val BODY = Regex("""\bbody\b""")
        val BASELINE = Regex("""\binitialBody\b""")

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
