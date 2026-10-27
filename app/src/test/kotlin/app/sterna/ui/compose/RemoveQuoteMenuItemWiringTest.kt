package app.sterna.ui.compose

import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source lint, and a last resort: nothing in this module composes, so the overflow menu is never
 * opened here. What the cut does is run by [RemoveQuotedTextTest]; this pins that the "Remove quoted
 * text" entry is drawn only when `hasQuotedText(body.text)` holds, that its tap hands the whole rich
 * body to `removeQuotedText` through `rewriteRichBody`, and that it never moves `initialBody` or a
 * baseline: moved, the composer would read as untouched after the removal and close without asking.
 *
 * Holes of `CONTRIBUTING.md` that apply: 1 (`body`, `ranges`, `blocks`, `links` are names: nothing
 * proves they are the state the screen draws and saves), 3 (the guard is checked to be the line
 * just above the item, not to be the only branch around it), and 4 (whether the menu shows the entry
 * on a real reply is never observed).
 */
class RemoveQuoteMenuItemWiringTest {

    private fun lines() = DeclarationSource.codeLines(
        DeclarationSource.file("app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"),
    )

    private fun item(): List<String> {
        val lines = lines()
        assertEquals("`$LABEL` labels one menu entry", 1, lines.count { it == LABEL })
        val start = lines.indexOf(LABEL) - 1
        val item = mutableListOf<String>()
        var depth = 0
        for (line in lines.drop(start)) {
            item += line
            depth += line.count { it == '(' } - line.count { it == ')' }
            if (depth == 0) break
        }
        return item
    }

    @Test fun `the entry is offered only on a body that carries a quote`() {
        val lines = lines()
        val at = lines.indexOf(LABEL)
        assertTrue("no `$LABEL` in the composer", at >= 2)
        assertEquals("if (hasQuotedText(body.text)) {", lines[at - 2])
    }

    @Test fun `the tap cuts the rich body and leaves the reference body alone`() {
        assertEquals(
            listOf(
                "DropdownMenuItem(",
                LABEL,
                "onClick = {",
                "moreMenu = false",
                "rewriteRichBody(RichBody(body.text, ranges, blocks, links), body.selection.start, ::removeQuotedText)?.let { rewritten ->",
                "body = TextFieldValue(",
                "rewritten.text,",
                "TextRange(body.selection.start.coerceAtMost(rewritten.text.length)),",
                ")",
                "ranges = rewritten.ranges",
                "blocks = rewritten.blocks",
                "links = rewritten.links",
                "}",
                "},",
                "enabled = !sending,",
                ")",
            ),
            item(),
        )
    }

    @Test fun `the tap writes neither initialBody nor a baseline`() {
        val written = item().filter { "initialBody" in it || "baseline" in it || "lower()" in it }
        assertEquals(
            "a removed quote is an edit: the composer must stay modified, and the decrypted-quote " +
                "guard stays raised",
            emptyList<String>(),
            written,
        )
    }

    private companion object {
        const val LABEL = "text = { Text(stringResource(R.string.compose_remove_quote)) },"
    }
}
