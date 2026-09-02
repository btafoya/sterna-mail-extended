package app.sterna.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and disclaimer as
 * [ReplyQuoteConsumedWiringTest]: `ComposeScreen` is a composable, there is no Robolectric and no
 * compose-ui-test here, so nothing can EXECUTE the body field's callbacks. The decisions they call
 * are executed elsewhere (`RichBodyTest` runs `remapAfterEdit`, `toggle`, `clear`, `stylesAt`;
 * [RichBodyStyleTest] runs `toAnnotatedString`); this file pins the WIRING as whole trimmed lines
 * (#131). A `contains` on a fragment is blind to a mutation that lengthens the line.
 *
 * ⛔ What it cannot see: that the callbacks fire, that the bar shows, that `pending` reaches the
 * next keystroke. That is the bench's.
 */
class ComposeBodyWiringTest {

    /** (a) The remap, its LIVE source and its last argument: `pending`, the armed style. */
    @Test fun `every keystroke remaps the styling from the live body, with the armed style`() {
        assertPinned(
            REMAP,
            "the body field's `onValueChange` must remap the styling with `remapAfterEdit`, from " +
                "`current` (built inside the callback — `rich` is captured at composition, and two " +
                "callbacks before a recomposition would remap from a stale body) and with `pending` " +
                "as its last argument: `null` there and a style armed at the caret never reaches the " +
                "text, the bar lights up and nothing typed is bold.",
        )
        assertPinned(
            "val current = RichBody(body.text, ranges)",
            "`onValueChange`, `onToggle` and `onClear` each rebuild the body from the live " +
                "`body.text` and `ranges` at the top of the callback, and nothing else does.",
            times = 3,
        )
    }

    /** (b) The armed style falls on a caret move WITHOUT a keystroke, and only there. */
    @Test fun `moving the caret without typing disarms the pending style`() {
        val lines = lines()
        val at = lines.indexOf("pending = null")
        assertTrue("$SCREEN must lower `pending` on exactly one line", at >= 0 && lines.count { it == "pending = null" } == 1)
        assertEquals(
            "`pending = null` must be the body of the branch where the text is unchanged and the " +
                "selection moved — lowered on a keystroke instead, Bold armed at the caret would " +
                "style one character and then fall; never lowered, a style armed at one place " +
                "would land wherever the caret is tapped next.",
            "} else if (next.selection != body.selection) {",
            lines.getOrNull(at - 1),
        )
    }

    /** (c) One bar, shown for the focused body and nothing else. */
    @Test fun `the formatting bar is composed once, guarded by the body's focus`() {
        val lines = lines()
        val calls = lines.withIndex().filter { it.value == "FormattingBar(" }.map { it.index }
        assertEquals("expected exactly ONE call of `FormattingBar(` in $SCREEN — one bar, under the body", 1, calls.size)
        assertEquals(
            "the bar must be guarded by `if (bodyFocused) {` and nothing else: unguarded it sits " +
                "under the recipients and the subject too, where its buttons style a body nobody " +
                "is looking at.",
            "if (bodyFocused) {",
            lines.getOrNull(calls.single() - 1),
        )
        assertPinned(
            ".onFocusChanged { bodyFocused = it.isFocused },",
            "`bodyFocused` must be fed by the body field's own `onFocusChanged`, or the guard above " +
                "is a constant.",
        )
    }

    /**
     * (d) The last link of every reopen (#131): the prefill's styling reaching the editor.
     *
     * ⛔ `ranges = emptyMap()` here — which is what these two lines said until #131 — throws away
     * everything the four reopen routes just went to the trouble of parsing (`draftFieldsOf`,
     * `localDraftPrefill`, `composeDraftOf`, the undo record), on all of them at once, with every
     * one of their own tests still green: the styling exists right up to the screen and dies on
     * the last statement. And the BASELINE is not decoration either: left empty, a reopened styled
     * draft counts as edited the moment it opens and the leave guard asks about changes nobody
     * made.
     */
    @Test fun `the prefill's styling lands on the editor and on its baseline`() {
        assertPinned(
            "ranges = it.bodyRanges",
            "the prefill's styling must be applied to the editor's live `ranges`, from the " +
                "prefill and nowhere else.",
        )
        assertPinned(
            "baselineRanges = it.bodyRanges",
            "…and the baseline must be the styling the prefill LANDED with, so opening a styled " +
                "draft is not an edit.",
        )
    }

    // -- reading the source ---------------------------------------------------------------------

    private fun assertPinned(pinned: String, why: String, times: Int = 1) {
        val hits = lines().count { it == pinned }
        assertEquals(
            "$why\nExpected exactly $times line(s) of $SCREEN whose trimmed text is:\n    $pinned" +
                "\nbut found $hits — this lint compares the WHOLE line, so a change that only " +
                "lengthens it lands here too.",
            times, hits,
        )
    }

    /** The screen's code as trimmed whole lines, comments cut: prose must never satisfy a rule. */
    private fun lines(): List<String> = File(root, SCREEN).readLines().mapNotNull { line ->
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

    private companion object {
        const val SCREEN = "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"
        const val REMAP =
            "ranges = remapAfterEdit(current, next.text, span(body.selection), span(next.selection), pending).ranges"

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
