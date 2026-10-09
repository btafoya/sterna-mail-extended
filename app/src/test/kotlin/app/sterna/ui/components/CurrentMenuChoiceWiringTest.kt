package app.sterna.ui.components

import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, and a last resort: nothing in this module composes, so neither menu is ever opened
 * here and no screen reader was listened to. What is pinned is that, in the two menus whose entries
 * exclude one another (the list's sort order, the composer's protection mode), every entry sets
 * `selected` on its own node, true on the one in force and false on the others, while the tick
 * stays a decoration.
 *
 * Unlike a mail row, the boolean is set on every entry: in a one-of-many menu "Not selected" on
 * the others is the information, and no entry carries a `stateDescription` that would mute it.
 *
 * Holes of `CONTRIBUTING.md` that apply: 1 (`ui.sortOrder` and `pgpMode` are only names: nothing
 * here proves they hold the value in force, nor that a lambda parameter does not rebind them), 3
 * (each loop is pinned whole, so the item cannot leave its `forEach`, but nothing sees a branch
 * drawn around the menu, and the loop is found by its first line, counted once), and 4 (what the
 * item's node ends up exposing is never observed: that `DropdownMenuItem` puts its `modifier` on
 * the node its click makes is the library's, read and not run).
 */
class CurrentMenuChoiceWiringTest {

    @Test fun `every sort order says whether it is the one in force`() {
        assertEquals(
            listOf(
                SORT_LOOP,
                "DropdownMenuItem(",
                "text = { Text(stringResource(sortLabel(order))) },",
                "modifier = Modifier.semantics { selected = order == ui.sortOrder },",
                "leadingIcon = {",
                "if (order == ui.sortOrder) Icon(Icons.Filled.Check, contentDescription = null)",
                "},",
                "onClick = { viewModel.setSortOrder(order); sortOpen = false },",
                ")",
                "}",
            ),
            loop("app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt", SORT_LOOP),
        )
    }

    @Test fun `every protection mode says whether it is the one in force`() {
        assertEquals(
            listOf(
                LOCK_LOOP,
                "DropdownMenuItem(",
                "text = { Text(stringResource(pgpModeLabel(entry))) },",
                "modifier = Modifier.semantics { selected = entry == pgpMode },",
                "leadingIcon = {",
                "Icon(pgpModeIcon(entry), contentDescription = null)",
                "},",
                "trailingIcon = {",
                "if (entry == pgpMode) {",
                "Icon(Icons.Filled.Check, contentDescription = null)",
                "}",
                "},",
                "onClick = {",
                "pgpMenu = false",
                "viewModel.setPgpMode(entry)",
                "},",
                ")",
                "}",
            ),
            loop("app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt", LOCK_LOOP),
        )
    }

    private fun loop(path: String, opener: String): List<String> {
        val file: File = DeclarationSource.file(path)
        assertEquals(
            "`$opener` opens one loop in ${file.name}: a second one would be a menu this lint never reads",
            1,
            DeclarationSource.codeLines(file).count { it == opener },
        )
        return DeclarationSource.blockContaining(file, marker = opener, opener = opener)
    }

    private companion object {
        const val SORT_LOOP = "SortOrder.entries.forEach { order ->"
        const val LOCK_LOOP = "lockMenuModes(openPgp = pgpAvailable, smime = smimeAvailable).forEach { entry ->"
    }
}
