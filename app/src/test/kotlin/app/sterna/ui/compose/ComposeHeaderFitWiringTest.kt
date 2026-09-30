package app.sterna.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source lint, not a behaviour test: it reads the source as text and proves nothing about layout.
 * The decisions it wires are executed by [ComposeHeaderFitTest]; this only pins that the screen
 * hands them the right arguments, in the right place, in the right order. Every rule compares whole
 * trimmed lines, comments cut, so a lengthened line fails too.
 */
class ComposeHeaderFitWiringTest {

    @Test fun `the header is the editor's first child, capped by yieldToBody, then scrolled`() {
        val lines = lines()
        val at = lines.indexOf("DraftReopenView.Editor ->")
        assertTrue("$SCREEN has no `DraftReopenView.Editor ->` line: re-read the editor", at >= 0)
        assertEquals(
            "the editor must open on the root Column with its union inset (#26), then the body's " +
                "minimum read off the style the body field is drawn with, then the header, whose " +
                "modifier caps it with `yieldToBody` BEFORE `verticalScroll`. Reversed, the scroll " +
                "is measured without a bounded height and throws; without the cap, the header takes " +
                "every pixel above the keyboard and the body is left 0 px (#219). `barShown` must be " +
                "`bodyFocused`, the very guard of the bar, or the bar eats the body's third line; `blockBelow` " +
                "reserves the forwarded block the same way, folded by that same focus.",
            EDITOR_HEAD,
            lines.subList(at, minOf(lines.size, at + EDITOR_HEAD.size)),
        )
    }

    @Test fun `the header takes no weight`() {
        val lines = lines()
        val at = lines.indexOf(".yieldToBody(bodyMinHeight, barShown = bodyFocused, blockBelow = forwardedBlockReserve(forwardedPreview, noteFocused = bodyFocused))")
        assertTrue("$SCREEN no longer calls `yieldToBody` on its own line", at >= 0)
        val start = lines.subList(0, at).lastIndexOf("Column(")
        val end = lines.subList(at, lines.size).indexOf(") {") + at
        val chain = lines.subList(start, end + 1)
        assertEquals(
            "no `weight` on the header's modifier. `weight(1f, fill = false)` there shares the " +
                "column out by weight and does not hand back what the header left: a two-line " +
                "header would leave the body only half the screen. Chain was:\n$chain",
            emptyList<String>(),
            chain.filter { WEIGHT.containsMatchIn(it) },
        )
    }

    @Test fun `the body keeps its weight, so it fills what the header leaves`() {
        val lines = lines()
        val at = lines.indexOf(".focusRequester(bodyFocus)")
        assertTrue("$SCREEN has no `.focusRequester(bodyFocus)` line: re-read the body field", at >= 0)
        assertEquals(
            "the body field must keep `.weight(1f)`: its height bounded by what is left, it scrolls " +
                "its own content and keeps the caret in view, and the whole area under the header " +
                "is its tap target.",
            ".weight(1f)",
            lines.getOrNull(at - 1),
        )
    }

    @Test fun `the margins counted in the body's minimum are the ones it is drawn with`() {
        assertEquals(
            "the body's decoration box must be padded by `BODY_PADDING_TOP` and " +
                "`BODY_PADDING_BOTTOM`, the constants `composeBodyMinHeight` adds: a literal here " +
                "drifts from the minimum and the third line is cut again.",
            1,
            lines().count { it == ".padding(top = BODY_PADDING_TOP, bottom = BODY_PADDING_BOTTOM)," },
        )
    }

    @Test fun `the formatting bar is as tall as the room reserved for it`() {
        val lines = lines()
        val at = lines.indexOf("private fun FormattingBar(")
        assertTrue("$SCREEN has no `private fun FormattingBar(`", at >= 0)
        val row = lines.subList(at, lines.size).indexOf("Row(") + at
        assertEquals(
            "the bar's Row must be set to `FORMATTING_BAR_HEIGHT`, the height `yieldToBody` " +
                "reserves under the body: one number, or the reserve and the bar drift apart.",
            BAR_HEAD,
            lines.subList(row, minOf(lines.size, row + BAR_HEAD.size)),
        )
    }

    // -- reading the source ---------------------------------------------------------------------

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

        val WEIGHT = Regex("""\bweight\(""")

        val EDITOR_HEAD = listOf(
            "DraftReopenView.Editor ->",
            "Column(",
            "modifier = Modifier",
            ".fillMaxSize()",
            ".windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))",
            ".graphicsLayer {",
            "translationY = -fly * 64.dp.toPx()",
            "alpha = 1f - fly",
            "},",
            ") {",
            "val bodyMinHeight = composeBodyMinHeight(LocalTextStyle.current.lineHeight, LocalDensity.current)",
            "Column(",
            "Modifier",
            ".yieldToBody(bodyMinHeight, barShown = bodyFocused, blockBelow = forwardedBlockReserve(forwardedPreview, noteFocused = bodyFocused))",
            ".background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f))",
            ".verticalScroll(rememberScrollState()),",
            ") {",
        )

        val BAR_HEAD = listOf(
            "Row(",
            "Modifier",
            ".fillMaxWidth()",
            ".height(FORMATTING_BAR_HEIGHT)",
            ".horizontalScroll(rememberScrollState())",
            ".padding(horizontal = 4.dp),",
            ") {",
        )

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SCREEN).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }
    }
}
