package app.sterna.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A source lint, not a behaviour test. It reads `LockScreen.kt` and `SternaApp.kt` as text, because
 * nothing in this module composes or renders. What it pins is the two facts that together keep the
 * mail composed under the app lock out of the accessibility tree: the lock's full-screen `Surface`
 * is a node accessibility counts as important, and it is composed after the routes it hides.
 *
 * Whole trimmed lines, never `contains` of a fragment: a fragment rule is blind to anything
 * appended to a line.
 *
 * What it does not cover, and no rule here closes:
 *  - whether the nodes below are actually pruned. That is decided by Compose from the bounds of
 *    the nodes it holds important, and is only measured on a device, by dumping the accessibility
 *    tree while locked.
 *  - whether the `Surface` really fills the window. A parent that constrains it leaves the rest of
 *    the screen uncovered while every line here still reads the same.
 *  - what a screen reader says when the lock appears.
 *  - anything shown in another window, a dialog or a popup, which this overlay never covers.
 */
class LockScreenCoversAccessibilityLintTest {

    /**
     * The line is pinned by its position in the modifier chain of the `Surface(`, not by its
     * presence in the file: moved onto the inner `Column` it would cover the padded content area
     * only, while a whole-file rule stayed green.
     */
    @Test fun `the lock surface is a full-screen pane for accessibility`() {
        val code = codeLines(LOCK_SCREEN)
        val surface = code.indexOf("Surface(")
        assertTrue("LockScreen.kt no longer opens its overlay on a line `Surface(`", surface >= 0)
        assertEquals(
            "LockScreen.kt has more than one `Surface(`: this rule can no longer tell which one " +
                "is the full-screen overlay.",
            surface,
            code.lastIndexOf("Surface("),
        )
        assertEquals(
            "the `Surface(` of LockScreen.kt must open its modifier chain with `modifier = " +
                "Modifier`, `.fillMaxSize()`, then the pane title, each as a whole line. Without " +
                "a semantics property on the full-screen node, accessibility does not count the " +
                "lock as covering anything: the message list, the reader and the composer stay " +
                "in the accessibility tree behind it, where a screen reader reads them aloud and " +
                "can click them while the app is locked.",
            listOf("modifier = Modifier", ".fillMaxSize()", ".semantics { paneTitle = lockTitle }"),
            code.subList(surface + 1, minOf(surface + 4, code.size)),
        )
        assertEquals(
            "`lockTitle` in LockScreen.kt must be the lock's own title, as the whole line `val " +
                "lockTitle = stringResource(R.string.lock_title)`. An empty or unrelated pane " +
                "title names the lock wrongly to the person who cannot see it.",
            1,
            code.count { it == "val lockTitle = stringResource(R.string.lock_title)" },
        )
    }

    /**
     * Covering is a matter of composition order: a node only covers what is composed before it.
     * Composed above the route `when`, the lock would sit under the mail and hide nothing from
     * accessibility, whatever its own semantics say.
     */
    @Test fun `the lock is composed once, after every route`() {
        val code = codeLines(STERNA_APP)
        val lock = "if (locked) LockScreen(onUnlocked = appLock::unlock)"
        assertEquals(
            "SternaApp.kt must compose the lock exactly once, as the whole line `$lock`. Absent, " +
                "nothing covers the mail; twice, this rule can no longer tell which one is last.",
            1,
            code.count { it == lock },
        )
        val routing = code.indexOf("when (val s = state) {")
        val navHost = code.lastIndexOf("else -> MainNavHost(")
        assertTrue("SternaApp.kt no longer routes on a line `when (val s = state) {`", routing >= 0)
        assertTrue("SternaApp.kt no longer has a line `else -> MainNavHost(`", navHost > routing)
        assertTrue(
            "SternaApp.kt must compose `$lock` after `else -> MainNavHost(`. Composed before the " +
                "routes, the lock is below them: the mail is drawn over it and stays readable " +
                "and clickable by a screen reader while the app is locked.",
            code.indexOf(lock) > navHost,
        )
    }

    /** The file's lines, trimmed, without comments or blanks. */
    private fun codeLines(path: String): List<String> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, path).isFile }
            ?: error("cannot locate the repo root from ${File("").absolutePath}")
        return File(root, path).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
    }

    private companion object {
        const val LOCK_SCREEN = "app/src/main/kotlin/app/sterna/security/LockScreen.kt"
        const val STERNA_APP = "app/src/main/kotlin/app/sterna/ui/SternaApp.kt"
    }
}
