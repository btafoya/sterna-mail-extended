package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A source lint, not a behaviour test. It reads `SternaApp.kt` as text, because nothing in this
 * module composes: the root is a `@Composable` over an `AndroidViewModel`, and no test here can
 * look at a pixel. What it pins is that the Compose tree paints the app background itself, at the
 * root, from the resolved Material scheme.
 *
 * Whole trimmed lines, never `contains` of a fragment: a fragment rule is blind to anything
 * appended to a line, and a rule looking for "background" is satisfied by
 * `.background(Color.Transparent)`.
 *
 * What it does not cover, and no rule here closes:
 *  - whether the Box actually draws below everything else. Nothing in this repository renders.
 *  - whether `SternaApp` is composed inside `SternaTheme` at all. Composed outside it, the line
 *    still reads `MaterialTheme.colorScheme.background` and resolves to Compose's own default.
 *  - the window background resource and the two splash colours. They stay reachable before the
 *    first frame, and this rule never looks at `res/`.
 */
class AppBackgroundLintTest {

    /**
     * The root of the app owns the app background. Every surface that no `Scaffold` covers reads
     * whatever is behind the Compose tree, and behind it is `android:windowBackground`: a pair of
     * fixed colours the system uiMode qualifier picks between, which answers to neither the app's
     * own theme setting, nor Material You, nor pure black. The colour has to come from
     * the resolved scheme: a constant or a `colorResource` compiles, keeps the split painted, and
     * pins the wrong half of the window to one palette again.
     *
     * The line is pinned by its position, immediately above the route `when`, not by its presence
     * in the file: moved onto a nested Box it would paint one branch and leave the others bare,
     * while a whole-file rule stayed green.
     */
    @Test fun `the app root paints the app background from the resolved colour scheme`() {
        val code = codeLines(STERNA_APP)
        val routing = code.indexOf("when (val s = state) {")
        assertTrue("SternaApp.kt no longer routes on a line `when (val s = state) {`", routing > 0)
        assertEquals(
            "the root Box of SternaApp.kt, the line directly above `when (val s = state) {`, must " +
                "carry the app background as a whole line. Bare, the window background shows " +
                "through everywhere no Scaffold reaches: the empty right half of the two-pane " +
                "window keeps the resource colour while the list beside it is Material You, light " +
                "or pure black (#201), and so do the Loading and NeedAccount states, the pane " +
                "conversion arm and the message route's fades.",
            "Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {",
            code[routing - 1],
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
        const val STERNA_APP = "app/src/main/kotlin/app/sterna/ui/SternaApp.kt"
    }
}
