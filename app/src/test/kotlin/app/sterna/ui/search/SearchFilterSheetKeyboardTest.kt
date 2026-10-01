package app.sterna.ui.search

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The decision is executed: [reopensUnderKeyboard] for rising, falling and still keyboards and each
 * sheet value. The guard that applies it is a composable, so its wiring is a source lint, the last
 * resort. Holes of that lint: 1, `beforePx` and `imePx` are pinned by name, not by what the flow
 * actually hands them; 3, the body is pinned line for line but nothing here runs it, so neither the
 * frame wait nor the expand is seen to happen; 4, no inset value the guard collects is held here. Only the bench at 360 x 640 dp
 * shows the sheet above the keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
class SearchFilterSheetKeyboardTest {

    @Test fun `a sheet left hidden by a rising keyboard is reopened`() {
        assertTrue(
            "the keyboard rose from 300 to 500 px and left the sheet Hidden under it: it must be reopened",
            reopensUnderKeyboard(beforePx = 300, imePx = 500, target = SheetValue.Hidden),
        )
        assertTrue(
            "the first frame of the keyboard's rise counts too",
            reopensUnderKeyboard(beforePx = 0, imePx = 1, target = SheetValue.Hidden),
        )
    }

    @Test fun `a falling or still keyboard never reopens a hidden sheet`() {
        assertFalse(
            "the keyboard fell: a Hidden target is then a hide() the user started, reopening undoes it",
            reopensUnderKeyboard(beforePx = 500, imePx = 300, target = SheetValue.Hidden),
        )
        assertFalse(
            "the keyboard did not move: a Hidden target is a tap outside or a drag, not the keyboard",
            reopensUnderKeyboard(beforePx = 500, imePx = 500, target = SheetValue.Hidden),
        )
    }

    @Test fun `an open sheet is left where it is`() {
        assertFalse(
            "an expanded sheet must not be animated again",
            reopensUnderKeyboard(beforePx = 0, imePx = 500, target = SheetValue.Expanded),
        )
        assertFalse(
            "a partially expanded sheet is the user's choice, not the keyboard's",
            reopensUnderKeyboard(beforePx = 0, imePx = 500, target = SheetValue.PartiallyExpanded),
        )
    }

    private val code: List<String> by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
            ?: error("cannot locate the checkout from ${File("").absolutePath}")
        File(root, "app/src/main/kotlin/app/sterna/ui/search/SearchFilterSheet.kt").readLines()
            .map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") || it.isEmpty() }
    }

    /** The lines from the one equal to [start] to where its braces close. */
    private fun block(start: String): List<String> {
        val at = code.indexOf(start)
        check(at >= 0) { "no line '$start' in SearchFilterSheet.kt: did it get renamed?" }
        var depth = 0
        var opened = false
        for (i in at until code.size) {
            depth += code[i].count { it == '{' } - code[i].count { it == '}' }
            opened = opened || depth > 0
            if (opened && depth == 0) return code.subList(at, i + 1)
        }
        error("'$start' never closes")
    }

    @Test fun `the guard runs inside the sheet, on the state the sheet is given`() {
        val sheet = block("fun SearchFilterSheet(")
        val state = "val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)"
        assertEquals("the sheet's state must be built exactly once: $sheet", 1, sheet.count { it == state })
        val opened = sheet.indexOf("ModalBottomSheet(")
        assertTrue("ModalBottomSheet( not found in SearchFilterSheet", opened >= 0)
        assertEquals(
            "the sheet must be handed that same state, or the guard reopens a sheet nobody draws",
            1,
            sheet.count { it == "sheetState = sheetState," },
        )
        val content = opened + sheet.subList(opened, sheet.size).indexOf(") {")
        val guard = sheet.indexOf("ReopenWhenTheKeyboardMoves(sheetState)")
        assertTrue(
            "ReopenWhenTheKeyboardMoves(sheetState) must be the sheet content's first call (line $guard, " +
                "content opens at $content). Outside the sheet it reads the activity's insets, which never " +
                "carry this keyboard.",
            content > opened && guard == content + 1,
        )
    }

    @Test fun `the guard is exactly the frame wait, the decision and a refusal it survives`() {
        // The whole body, line for line: a line slipped in between (an early return, a narrower
        // catch, a rethrow) would silence the guard for the life of the sheet and nothing would say so.
        assertEquals(
            listOf(
                "private fun ReopenWhenTheKeyboardMoves(sheetState: SheetState) {",
                "val ime = WindowInsets.ime",
                "val density = LocalDensity.current",
                "LaunchedEffect(sheetState, ime, density) {",
                "var previousPx = ime.getBottom(density)",
                "snapshotFlow { ime.getBottom(density) }",
                ".collectLatest { imePx ->",
                "val beforePx = previousPx",
                "previousPx = imePx",
                "withFrameNanos { }",
                "if (reopensUnderKeyboard(beforePx, imePx, sheetState.targetValue)) {",
                "try {",
                "sheetState.expand()",
                "} catch (refused: CancellationException) {",
                "currentCoroutineContext().ensureActive()",
                "}",
                "}",
                "}",
                "}",
                "}",
            ),
            block("private fun ReopenWhenTheKeyboardMoves(sheetState: SheetState) {"),
        )
    }
}
