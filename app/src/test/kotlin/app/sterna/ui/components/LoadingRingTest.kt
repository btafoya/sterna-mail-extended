package app.sterna.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The one decision behind the centred loading ring (#63), RUN — plus a source lint holding the two
 * indicators the reader shows on a single tap, which no test in this module can render.
 *
 * ⛔ The expected values below are written out as literals on purpose. A test that recomputes
 * `if (motionOn) …` to decide what it expects is a copy of the rule: invert the shipped condition
 * and it stays green.
 */
class LoadingRingTest {

    // -- 1. the decision, executed ---------------------------------------------------------------

    @Test fun `with animations on, Material 3 keeps its own indeterminate arc`() {
        assertNull(
            "with motion allowed the ring must be left indeterminate (null = Material 3 animates " +
                "it). Answering a fixed turn here freezes every loading screen in the app into a " +
                "static circle that can no longer be told apart from a stuck one.",
            loadingRingProgress(motionOn = true),
        )
    }

    @Test fun `with animations off, the ring rests at a full turn`() {
        assertEquals(
            "with motion off the ring must be drawn determinate at 1f — a WHOLE circle. This is " +
                "the defect itself: with the system animations off, Material 3's indeterminate " +
                "arc is left on screen as a few pixels of stroke on the circumference and no " +
                "longer reads as a loading indicator (#63, filmed by the reporter). Any value " +
                "below 1f is a fragment, and 0f draws nothing at all — the screen would stop " +
                "saying it loads.",
            1f,
            loadingRingProgress(motionOn = false),
        )
    }

    // -- 2. the composable asks the decision, and animates in ONE of its two branches -------------

    /**
     * ⚠ SOURCE LINT. `LoadingRing` cannot be rendered here — no Robolectric and no
     * `compose-ui-test` in this module — so what is held is its SIGNATURE (the fourteen callers that
     * pass no stroke get Material 3's own default), that it reads the setting, hands it to the
     * decision above, composes the animated Material 3 indicator ONLY on the `null` answer (the
     * pattern of `TernRefreshIndicator`), and wraps the RESTING one in the Box that repairs what it
     * announces. Restating the condition inside the composable would leave the decision tests above
     * proving nothing about the app.
     *
     * Whole trimmed lines, in order, comments stripped: the semantics live in the arguments, so a
     * `contains` on the function name would be met by any of the wrong forms.
     */
    @Test fun `the composable routes through the decision, and animates only on null`() {
        assertBlock(
            LOADING_RING,
            "fun LoadingRing(",
            listOf(
                "fun LoadingRing(",
                "modifier: Modifier = Modifier,",
                "strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,",
                ") {",
                "val rest = loadingRingProgress(rememberMotionEnabled())",
                "if (rest == null) {",
                "CircularProgressIndicator(modifier = modifier, strokeWidth = strokeWidth)",
                "} else {",
                "Box(",
                "modifier.clearAndSetSemantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },",
                "contentAlignment = Alignment.Center,",
                ") {",
                "CircularProgressIndicator(",
                "progress = { rest },",
                "strokeWidth = strokeWidth,",
                "trackColor = Color.Transparent,",
                "strokeCap = StrokeCap.Butt,",
                "gapSize = 0.dp,",
                ")",
                "}",
            ),
            "LoadingRing changed shape. Five things are pinned here, and the first two hurt " +
                "silently — nothing crashes, the ring is just wrong on all fifteen screens:\n" +
                "  (a) the SIGNATURE and its default values. `strokeWidth` defaults to " +
                "ProgressIndicatorDefaults.CircularStrokeWidth, which is what the fourteen callers " +
                "that pass no stroke of their own get, in BOTH branches, animations on or off. " +
                "Lower it (say to 1.dp) and every one of those rings thins from a 4 dp ring to a " +
                "hairline, on every device;\n" +
                "  (b) it reads the setting and routes through loadingRingProgress;\n" +
                "  (c) Material 3's animated indicator is composed on the null answer ALONE;\n" +
                "  (d) the RESTING ring sits inside a Box that carries the caller's modifier and " +
                "clearAndSetSemantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }. " +
                "Without it, the determinate overload publishes its own ProgressBarRangeInfo(1f, " +
                "0f..1f) and a screen reader announces \"100 %\" over a load that has not finished " +
                "— on all fifteen screens, and only to the people who turned animations off, which " +
                "is an accessibility recommendation, TalkBack included. The indicator must stay a " +
                "DESCENDANT of that Box and must not be handed a modifier of its own: which of a " +
                "sibling semantics{} and Material 3's internal progressSemantics() wins cannot be " +
                "run, nor proven, in this module;\n" +
                "  (e) the resting ring keeps no track, no 4 dp gap and no round caps, or it is a " +
                "circle with a notch bitten out of it.",
        )
    }

    // -- 3. the reader's TWO indicators ------------------------------------------------------------

    /**
     * One tap on a message shows these two in turn: the whole-screen one while the message is
     * fetched, then the 24 dp one over the header while the body lays out. Either one left as a
     * raw `CircularProgressIndicator` puts the unreadable fragment back on the very screen the
     * reporter photographed, so both are pinned WHOLE — never with `in`, which is blind to
     * anything appended to the line.
     */
    @Test fun `the reader's fetch indicator is the app's ring`() {
        assertBlock(
            MESSAGE_SCREEN,
            "is MessageState.Loading ->",
            listOf(
                "is MessageState.Loading -> LoadingRing(Modifier.align(Alignment.Center))",
                "is MessageState.Error -> Column(",
            ),
            "the reader's fetch spinner is not LoadingRing any more: with the system animations " +
                "off it is back to a fragment of arc that no longer reads as loading (#63).",
        )
    }

    @Test fun `the reader's body-layout indicator is the app's ring, at its own size`() {
        assertBlock(
            MESSAGE_SCREEN,
            "if (full != null && !bodyReady && spinnerDue) {",
            listOf(
                "if (full != null && !bodyReady && spinnerDue) {",
                "Box(",
                "Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(24.dp).align(Alignment.Center),",
                "contentAlignment = Alignment.Center,",
                ") {",
                "LoadingRing(Modifier.size(24.dp), strokeWidth = 2.dp)",
                "}",
            ),
            "the body-layout spinner changed. It must be LoadingRing at 24 dp with a 2 dp stroke " +
                "(its size and stroke are its own, not the default 40 dp), and it must still be " +
                "gated on `full != null && !bodyReady && spinnerDue`: that 500 ms delay is what " +
                "keeps cached mail from flashing a spinner it does not need.",
        )
    }

    // -- 4. the nine other converted sites ----------------------------------------------------------

    /**
     * ⚠ SOURCE LINT, and the widest one: fifteen screens were converted from a raw
     * `CircularProgressIndicator` to [LoadingRing], and six of them are held by the tests above and
     * by the wiring tests (`RootRefreshWiringTest`, `NotificationsGateWiringTest`,
     * `MailBySenderWiringTest`). These are the other nine. None can be rendered in this module, so
     * each is pinned by its WHOLE trimmed source line — never `in`/`contains`, which is blind to
     * anything appended — and by the line having to appear EXACTLY ONCE in its file.
     *
     * Line numbers are deliberately not part of the key: they move, the text is what identifies the
     * site. A site whose surrounding code legitimately changed updates its entry here; a site that
     * silently went back to Material 3's own indicator cannot.
     */
    @Test fun `every converted loading indicator is still the app's ring`() {
        val damaged = RING_SITES.mapNotNull { site ->
            when (val n = codeLines(site.path).count { it == site.line }) {
                1 -> null
                0 -> "${site.what} — the line is GONE from ${site.path}: `${site.line}`"
                else -> "${site.what} — the line appears $n times in ${site.path}, so this entry no " +
                    "longer identifies one site: `${site.line}`"
            }
        }
        assertEquals(
            "a converted loading indicator no longer reads as LoadingRing. Each entry below names " +
                "the screen that went back to Material 3's raw indeterminate indicator: with the " +
                "system animations off, that indicator is left as a few pixels of arc on the " +
                "circumference and stops reading as \"loading\" at all — the very thing the " +
                "reporter filmed on the inbox refresh and the search screen (#63). Fixing the " +
                "screen is what closes this, not editing the expected line.",
            emptyList<String>(),
            damaged,
        )
    }

    // -- helpers ------------------------------------------------------------------------------------

    /**
     * Compares [expected] against the same number of consecutive CODE lines of [path], starting at
     * the single line beginning with [prefix]. Whole trimmed lines, in order: a `contains` check
     * cannot see anything appended to a line, and a per-line check cannot see a statement moved.
     */
    private fun assertBlock(path: String, prefix: String, expected: List<String>, why: String) {
        val code = codeLines(path)
        val starts = code.indices.filter { code[it].startsWith(prefix) }
        assertEquals("$why\n(expected exactly one code line of $path to start with `$prefix`)", 1, starts.size)
        val found = code.subList(starts[0], minOf(starts[0] + expected.size, code.size))
        assertEquals(why, expected, found)
    }

    /** The file's lines, trimmed, without comments or blanks — a rule must not be met by prose. */
    private fun codeLines(path: String): List<String> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, path).isFile }
            ?: error("cannot locate the repo root from ${File("").absolutePath}")
        return File(root, path).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
    }

    /** One converted spinner: the screen it is on, and the whole source line that draws it. */
    private class RingSite(val path: String, val what: String, val line: String)

    private companion object {
        const val LOADING_RING = "app/src/main/kotlin/app/sterna/ui/components/LoadingRing.kt"
        const val MESSAGE_SCREEN = "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt"
        const val INBOX_SCREEN = "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"
        const val SEARCH_SCREEN = "app/src/main/kotlin/app/sterna/ui/search/SearchScreen.kt"
        const val FILTERS_SCREEN = "app/src/main/kotlin/app/sterna/ui/settings/FiltersScreen.kt"
        const val SETTINGS_SCREEN = "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt"

        /**
         * The nine converted sites nothing else holds. The six left out are held elsewhere and are
         * deliberately NOT repeated here: MessageScreen's fetch and body-layout rings (the two
         * tests above), SternaApp's two root rings (RootRefreshWiringTest), the notifications gate
         * (NotificationsGateWiringTest) and the by-sender list (MailBySenderWiringTest).
         */
        val RING_SITES = listOf(
            RingSite(
                INBOX_SCREEN,
                "the inbox's in-list search, while the query runs",
                "SearchDisplay.SPINNER -> LoadingRing(Modifier.align(Alignment.Center))",
            ),
            RingSite(
                INBOX_SCREEN,
                "the inbox's centred refresh indicator (a paging refresh, the one on the " +
                    "reporter's screenshot)",
                "refreshLoading -> LoadingRing(Modifier.align(Alignment.Center))",
            ),
            RingSite(
                SEARCH_SCREEN,
                "the search screen, while the search runs",
                "is SearchState.Searching -> LoadingRing(Modifier.align(Alignment.Center))",
            ),
            RingSite(
                SEARCH_SCREEN,
                "the search screen's SPINNER result display",
                "SearchDisplay.SPINNER -> LoadingRing(Modifier.align(Alignment.Center))",
            ),
            RingSite(
                FILTERS_SCREEN,
                "the server-filters screen, while the rules load",
                "state.loading -> LoadingRing(Modifier.align(Alignment.Center))",
            ),
            RingSite(
                SETTINGS_SCREEN,
                "the vacation-responder screen, while the setting loads",
                "state.loading -> LoadingRing(Modifier.align(Alignment.Center))",
            ),
            RingSite(
                MESSAGE_SCREEN,
                "the reader's pager, on a page whose message is not paged in yet",
                "Box(Modifier.fillMaxSize(), Alignment.Center) { LoadingRing() }",
            ),
            RingSite(
                MESSAGE_SCREEN,
                "the reader's whole-screen placeholder (message not resolved yet)",
                "LoadingRing()",
            ),
            RingSite(
                MESSAGE_SCREEN,
                "the reader's \"all headers\" sheet, while the headers load",
                ") { LoadingRing() }",
            ),
        )
    }
}
