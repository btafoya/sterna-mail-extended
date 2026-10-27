package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test: nothing in this module can measure a layout (no Robolectric,
 * no compose-ui-test). The behaviour itself is executed by `FromMenuFitTest`; this file only pins
 * that the composer's From menu is fed by it, with measurements taken outside the popup.
 *
 * Hole 1 of CONTRIBUTING.md is closed: each named argument of `fromMenuMaxHeight` is matched to
 * the expression that measures it, and the visible frame to the composer's own `LocalView`. Hole 2
 * is closed for the menu's `modifier`, pinned whole. Hole 3 applies: nothing here says the anchor
 * is laid out before the menu opens, nor that the frame is read again when only the keyboard
 * changes it. Hole 4 applies too: nothing holds the frame or the position the composer actually
 * reads, nor that they match the ones the popup measures itself against.
 */
class FromMenuFitWiringTest {

    @Test fun `the From menu is capped by the computed height`() {
        val block = fromBlock()
        val name = boundName(block.substring(0, menuAt(block)), CALL)
        assertTrue(
            "the From DropdownMenu must receive `$MODIFIER_PREFIX$name)`, the height computed by " +
                "$CALL. Its arguments were: ${menuArgs(block)}",
            menuArgs(block).any { it == "$MODIFIER_PREFIX$name)" },
        )
    }

    @Test fun `each measurement reaches the parameter it belongs to`() {
        val block = fromBlock()
        val before = block.substring(0, menuAt(block))
        val callAt = before.indexOf(CALL)
        check(callAt >= 0) { "no $CALL before the From DropdownMenu" }
        val callArgs = splitTopLevel(balanced(before, callAt)).associate {
            val parts = it.split("=", limit = 2).map(String::trim)
            check(parts.size == 2) { "$CALL must be called with named arguments, found '$it'" }
            parts[0] to parts[1]
        }
        val positioned = braces(before, before.indexOf('{', before.indexOf(POSITIONED)))
        val assigned = Regex("""(\w+) = (.+)""").findAll(positioned)
            .associate { it.groupValues[2].trim() to it.groupValues[1] }
        assertEquals(
            "fromMenuMaxHeight takes exactly the visible frame's height, the anchor's bottom and the density",
            setOf("visibleFrameHeightPx", "anchorBottomPx", "density"), callArgs.keys,
        )
        val frame = boundName(before, FRAME_NEW)
        val view = boundName(before, VIEW_READ)
        assertTrue(
            "the From anchor's onGloballyPositioned must fill `$frame` from the composer's view `$view`, " +
                "the frame the popup measures itself against. It read: $positioned",
            "$view.getWindowVisibleDisplayFrame($frame)" in positioned,
        )
        assertEquals(
            "visibleFrameHeightPx must be the height of that visible frame",
            assigned["$frame.height()"], callArgs["visibleFrameHeightPx"],
        )
        assertEquals(
            "anchorBottomPx must be the From anchor's own bottom edge, in window coordinates like the popup's",
            assigned[ANCHOR_BOTTOM_RHS], callArgs["anchorBottomPx"],
        )
        assertTrue("the anchor must measure itself before the menu", POSITIONED in before)
    }

    @Test fun `nothing inside the menu's popup measures a window`() {
        val block = fromBlock()
        val argsEnd = block.indexOf(menuArgsText(block), menuAt(block)) + menuArgsText(block).length
        val content = braces(block, block.indexOf('{', argsEnd))
        val reads = listOf("navigationBars", "windowInsetsPadding", "LocalView", "getWindowVisibleDisplayFrame")
            .filter { it in content }
        assertFalse(
            "the From menu's content lives in the popup's window: $reads read there measures the " +
                "popup and caps nothing",
            reads.isNotEmpty(),
        )
    }

    private fun fromBlock(): String {
        val code = code(COMPOSE_SCREEN)
        val at = code.indexOf(FROM_IF)
        check(at >= 0) { "no '$FROM_IF' in ComposeScreen.kt — the From row moved, teach this lint" }
        return braces(code, code.indexOf('{', at))
    }

    private fun menuAt(block: String): Int = block.indexOf(MENU_CALL).also {
        check(it >= 0) { "no '$MENU_CALL' in the From block" }
        check("expanded = fromMenu" in balanced(block, it)) {
            "the first DropdownMenu in the From block is no longer the From menu, teach this lint"
        }
    }

    private fun menuArgsText(block: String): String = balanced(block, menuAt(block))

    private fun menuArgs(block: String): List<String> = splitTopLevel(menuArgsText(block))

    private fun boundName(text: String, rhsStart: String): String {
        val m = Regex("""val (\w+) =\s*""" + Regex.escape(rhsStart)).find(text)
        check(m != null) { "no `val <name> = $rhsStart…` before the From DropdownMenu" }
        return m.groupValues[1]
    }

    private fun braces(text: String, open: Int): String {
        check(open >= 0) { "no '{' to balance" }
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i + 1)
            }
        }
        error("unbalanced braces from $open")
    }

    private fun balanced(text: String, from: Int): String {
        val start = text.indexOf('(', from) + 1
        check(start > 0) { "no '(' at or after $from" }
        var depth = 1
        var i = start
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return text.substring(start, i - 1).trim()
    }

    private fun splitTopLevel(args: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var last = 0
        args.forEachIndexed { i, c ->
            when (c) {
                '(', '{' -> depth++
                ')', '}' -> depth--
                ',' -> if (depth == 0) {
                    out += args.substring(last, i)
                    last = i + 1
                }
            }
        }
        out += args.substring(last)
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Comment lines dropped so no rule here can be satisfied by prose. */
    private fun code(file: File): String = file.readLines().joinToString("\n") { line ->
        val t = line.trimStart()
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) "" else line
    }

    private companion object {
        const val FROM_IF = "if (fromOptions.size > 1) {"
        const val MENU_CALL = "DropdownMenu("
        const val CALL = "fromMenuMaxHeight("
        const val MODIFIER_PREFIX = "modifier = Modifier.heightIn(max = "
        const val VIEW_READ = "LocalView.current"
        const val FRAME_NEW = "remember { Rect() }"
        const val POSITIONED = ".onGloballyPositioned"
        const val ANCHOR_BOTTOM_RHS = "(it.positionInWindow().y + it.size.height).roundToInt()"

        val COMPOSE_SCREEN: File by lazy {
            val root = generateSequence(File("").absoluteFile) { it.parentFile }
                .first { File(it, "app/src/main/res/values/strings.xml").isFile }
            File(root, "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt")
        }
    }
}
