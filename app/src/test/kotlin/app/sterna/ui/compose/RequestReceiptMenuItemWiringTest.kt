package app.sterna.ui.compose

import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, and a last resort: nothing in this module composes, so the composer's overflow menu
 * is never opened here and no screen reader was listened to. What is pinned is that the "Request
 * read receipt" entry says on its own node that it is a checkbox and whether it is ticked, while
 * the drawn `Checkbox` stays inert, `onCheckedChange = null`, and so adds no second node to land
 * on.
 *
 * Holes of `CONTRIBUTING.md` that apply: 1 (`requestReceipt` is only a name: nothing here proves
 * it holds what the draft will be sent with, nor that a lambda parameter does not rebind it), 3
 * (the item is pinned whole, from its opening parenthesis to the closing one, but nothing sees a
 * branch drawn around it), and 4 (what the item's node ends up exposing is never observed: that
 * `DropdownMenuItem` puts its `modifier` on the node its click makes is the library's, read and
 * not run).
 */
class RequestReceiptMenuItemWiringTest {

    @Test fun `the read receipt entry says it is a checkbox and whether it is ticked`() {
        val lines = DeclarationSource.codeLines(
            DeclarationSource.file("app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"),
        )
        assertEquals("`$LABEL` labels one menu entry", 1, lines.count { it == LABEL })
        val start = lines.indexOf(LABEL) - 1
        val item = mutableListOf<String>()
        var depth = 0
        for (line in lines.drop(start)) {
            item += line
            depth += line.count { it == '(' } - line.count { it == ')' }
            if (depth == 0) break
        }
        assertEquals(
            listOf(
                "DropdownMenuItem(",
                LABEL,
                "modifier = Modifier.semantics {",
                "role = Role.Checkbox",
                "toggleableState = ToggleableState(requestReceipt)",
                "},",
                "trailingIcon = { Checkbox(checked = requestReceipt, onCheckedChange = null) },",
                "onClick = { moreMenu = false; requestReceipt = !requestReceipt },",
                ")",
            ),
            item,
        )
    }

    private companion object {
        const val LABEL = "text = { Text(stringResource(R.string.compose_request_receipt)) },"
    }
}
