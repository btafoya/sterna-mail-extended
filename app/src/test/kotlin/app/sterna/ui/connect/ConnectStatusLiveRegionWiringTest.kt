package app.sterna.ui.connect

import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, and a last resort: nothing in this module composes, so the foot of the sign-in
 * column is never drawn here and no screen reader was listened to. What is pinned is that the
 * container the status is drawn in, the one that is scrolled into view, is also a polite live
 * region, and that nothing conditional stands between it and the screen.
 *
 * Holes of `CONTRIBUTING.md` that apply: 3 (the blocks around the container are pinned, so a
 * branch drawn around it is seen, but nothing says in which order the region and its text reach
 * the tree) and 4 (what the node ends up exposing is never observed: that a reader speaks the
 * sentence when it replaces the ring, and says nothing useful of the ring itself, is the
 * library's and the reader's, read and not run).
 */
class ConnectStatusLiveRegionWiringTest {

    private val lines = DeclarationSource.kotlinLines(
        DeclarationSource.file("app/src/main/kotlin/app/sterna/ui/connect/ConnectScreen.kt"),
    )

    @Test fun `the container of the status is a polite live region`() {
        assertEquals(
            "the error appears under the fields and is scrolled into view for the eye, and " +
                "without a live region on the node that holds it nothing says it aloud. The " +
                "region is the container, not the text: a region born with its sentence has " +
                "nothing to announce a change against",
            listOf(STATUS_CONTAINER),
            lines.filter { "bringIntoViewRequester(statusReveal)" in it },
        )
    }

    @Test fun `the region is composed whatever the status is`() {
        assertEquals(
            "a live region speaks when its content changes, so it has to be in the tree before " +
                "the sentence is. Each entry is the line opening a block around the container, " +
                "innermost first: one more, an `if (status != null) {` say, and the region " +
                "arrives together with what it should have announced",
            listOf(") {", ") { padding ->", ") {", SCREEN),
            enclosingOpeners(lines.indexOf(STATUS_CONTAINER)),
        )
    }

    private fun enclosingOpeners(at: Int): List<String> {
        val top = lines.indexOf(SCREEN)
        check(at > top && top >= 0) { "`$SCREEN` or the status container is gone from ConnectScreen.kt" }
        val openers = mutableListOf<String>()
        var depth = 0
        for (i in at - 1 downTo top + 1) {
            depth += lines[i].count { it == '{' } - lines[i].count { it == '}' }
            if (depth > 0) {
                openers += lines[i]
                depth = 0
            }
        }
        return openers + SCREEN
    }

    private companion object {
        const val SCREEN = "fun ConnectScreen("
        const val STATUS_CONTAINER =
            "Column(modifier = Modifier.bringIntoViewRequester(statusReveal).semantics { liveRegion = LiveRegionMode.Polite }) {"
    }
}
