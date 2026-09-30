package app.sterna.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source lint, same instrument and disclaimer as [ForwardAttachmentWiringTest]: `ComposeViewModel`
 * cannot be instantiated in a JVM test, so where the forwarded-original flow is written is pinned as
 * whole trimmed lines. The decision each write hands over is executed by [ForwardedPreviewTest].
 */
class ForwardedPreviewWiringTest {

    @Test fun `the flow is written in exactly these places, each through the one decision`() {
        assertEquals(
            "every write of `_forwardedPreview` in ComposeViewModel.kt, in source order: the " +
                "reopened queued row, the wait raised once the opening is parsed, the two failure " +
                "writes, and the built original. A sixth write is a state this lint cannot " +
                "place; one missing leaves the block waiting, or blank, for good.",
            listOf(
                "_forwardedPreview.value = forwardedPreview(opening = null, forwarded, failed = false)",
                "_forwardedPreview.value = forwardedPreview(opening, forwarded = null, failed = false)",
                "_forwardedPreview.value = forwardedPreview(opening, forwarded, failed = true)",
                "_forwardedPreview.value = forwardedPreview(opening, forwarded, failed = true)",
                ".onSuccess { _forwardedPreview.value = forwardedPreview(opening, forwarded, failed = false) }",
            ),
            lines(COMPOSE_VIEW_MODEL).filter { "_forwardedPreview.value" in it },
        )
    }

    @Test fun `the flow is derived from forwarded, which is still set in exactly two places`() {
        assertEquals(
            "`forwarded` is the source and the flow its projection: a third write of `forwarded` " +
                "would be one the preview does not follow.",
            listOf(
                "forwarded = ForwardedBlocks(d.forwardedText, d.forwardedHtml)",
                "runCatching { forwarded = buildForwarded(credentials, original) }",
            ),
            lines(COMPOSE_VIEW_MODEL).filter { Regex("""(^|\{ )forwarded = """).containsMatchIn(it) },
        )
    }

    @Test fun `the reopened queued row writes the flow after it restores the original`() {
        val all = lines(COMPOSE_VIEW_MODEL)
        val start = all.indexOf("private fun bindQueuedRow(d: SendOutbox.ComposeDraft, options: List<FromOption>) {")
        assertTrue("bindQueuedRow must exist", start >= 0)
        val end = all.withIndex().drop(start + 1).first { it.value.startsWith("private fun ") || it.value.startsWith("fun ") }.index
        val body = all.subList(start, end)
        val restored = body.indexOf("forwarded = ForwardedBlocks(d.forwardedText, d.forwardedHtml)")
        val written = body.indexOf("_forwardedPreview.value = forwardedPreview(opening = null, forwarded, failed = false)")
        assertTrue(
            "inside bindQueuedRow, the flow must be written AFTER `forwarded` is restored: before " +
                "it, an undone forward reopens with no block. restored=$restored written=$written",
            restored >= 0 && written > restored,
        )
    }

    @Test fun `the wait is raised as soon as the opening is parsed`() {
        assertLineAfter(
            "val opening = composeOpening(mode)",
            "_forwardedPreview.value = forwardedPreview(opening, forwarded = null, failed = false)",
            "the pending block must be up from the first frame of a forward, before the cache read",
        )
    }

    @Test fun `every failure of the prefill clears the block`() {
        assertLineAfter(
            "fun prefillFailed() {",
            "_forwardedPreview.value = forwardedPreview(opening, forwarded, failed = true)",
            "prefillFailed() is called for a build that threw; without this line the block waits forever",
        )
        val all = lines(COMPOSE_VIEW_MODEL)
        val at = all.indexOf("if (!quoteAlreadyOnScreen) prefillFailed()")
        assertTrue("the no-original branch must exist", at >= 0)
        assertEquals(
            "the no-original branch skips prefillFailed() when a quote is on screen, so it clears " +
                "the block on its own line, right before",
            "_forwardedPreview.value = forwardedPreview(opening, forwarded, failed = true)",
            all.getOrNull(at - 1),
        )
    }

    @Test fun `the built original is published on success, and only there`() {
        val all = lines(COMPOSE_VIEW_MODEL)
        val at = all.indexOf("runCatching { forwarded = buildForwarded(credentials, original) }")
        assertTrue("the build of the forwarded original must exist", at >= 0)
        assertEquals(
            listOf(
                ".onSuccess { _forwardedPreview.value = forwardedPreview(opening, forwarded, failed = false) }",
                ".onFailure { prefillFailed() }",
            ),
            all.subList(at + 1, at + 3),
        )
    }

    @Test fun `the ViewModel exposes the flow read-only, starting on nothing`() {
        assertPinnedLine(
            COMPOSE_VIEW_MODEL,
            "private val _forwardedPreview = MutableStateFlow<ForwardedPreview>(ForwardedPreview.None)",
            "Started on anything else, every composer that is not a forward draws a block.",
        )
        assertPinnedLine(
            COMPOSE_VIEW_MODEL,
            "val forwardedPreview: StateFlow<ForwardedPreview> = _forwardedPreview.asStateFlow()",
            "The screen reads; it never writes.",
        )
    }

    @Test fun `the screen draws the block under the note, from the flow, folded while the note has the focus`() {
        assertPinnedLine(
            COMPOSE_SCREEN,
            "val forwardedPreview by viewModel.forwardedPreview.collectAsStateWithLifecycle()",
            "The block reads the ViewModel's flow and nothing else.",
        )
        val all = lines(COMPOSE_SCREEN)
        val call = all.indexOf("ForwardedOriginalBlock(")
        assertTrue("the screen must compose `ForwardedOriginalBlock(`", call >= 0)
        assertEquals(1, all.count { it == "ForwardedOriginalBlock(" })
        assertEquals(
            "the block's arguments, whole lines: the flow's value, and folded to its title while " +
                "the note has the focus so the writing area keeps its height over the keyboard",
            listOf("preview = forwardedPreview,", "collapsed = bodyFocused,"),
            all.subList(call + 1, call + 3),
        )
        val field = all.indexOf(".focusRequester(bodyFocus)")
        val bar = all.indexOf("FormattingBar(")
        assertTrue(
            "the block sits under the note field and above the formatting bar",
            field in 0 until call && call < bar,
        )
    }

    @Test fun `the block shows the preview's text itself, folded and in full`() {
        assertEquals(
            "both the folded block and the full view must draw `preview.text` as is",
            2,
            lines(BLOCK).count { it == "text = preview.text," },
        )
        assertPinnedLine(
            BLOCK,
            ".then(if (collapsed) Modifier.height(FORWARDED_BLOCK_FOLDED_HEIGHT) else Modifier.heightIn(max = FORWARDED_BLOCK_MAX_HEIGHT))",
            "The block is held to the very heights `forwardedBlockReserve` reserves above the " +
                "keyboard: one number for both, or it takes the note's three lines (#26).",
        )
        assertPinnedLine(
            BLOCK,
            "maxLines = FORWARDED_FOLDED_LINES,",
            "Unfolded, a long original pushes the note off the screen.",
        )
    }

    // -- reading the sources --------------------------------------------------------------------

    private fun assertLineAfter(anchor: String, expected: String, why: String) {
        val all = lines(COMPOSE_VIEW_MODEL)
        val at = all.indexOf(anchor)
        assertTrue("`$anchor` must exist", at >= 0)
        assertEquals(why, expected, all.getOrNull(at + 1))
    }

    private fun assertPinnedLine(file: File, pinned: String, why: String) {
        val hits = lines(file).count { it == pinned }
        assertEquals(
            "expected exactly one CODE line of ${file.name} whose trimmed text is:\n    $pinned\n" +
                "but found $hits. $why",
            1, hits,
        )
    }

    /** [file]'s code as trimmed whole lines, comments cut, so prose can never satisfy a rule. */
    private fun lines(file: File): List<String> {
        assertTrue("${file.path} must exist", file.isFile)
        return file.readLines().mapNotNull { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) null
            else withoutTrailingComment(line).trim().takeIf { it.isNotEmpty() }
        }
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
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    private companion object {
        private const val DIR = "app/src/main/kotlin/app/sterna/ui/compose/"
        private const val COMPOSE_VIEW_MODEL_PATH = DIR + "ComposeViewModel.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, COMPOSE_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        val COMPOSE_VIEW_MODEL: File by lazy { File(root, COMPOSE_VIEW_MODEL_PATH) }
        val COMPOSE_SCREEN: File by lazy { File(root, DIR + "ComposeScreen.kt") }
        val BLOCK: File by lazy { File(root, DIR + "ForwardedOriginalBlock.kt") }
    }
}
