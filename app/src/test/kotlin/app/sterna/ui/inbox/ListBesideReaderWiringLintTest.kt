package app.sterna.ui.inbox

import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, not a behaviour test: whether the browse bar folds its filter and sort into the
 * overflow menu is decided inside a composable, which nothing in this module composes. Pinned: the
 * condition is named once, from the reader beside the list; each of its reads, whole; what the bar's
 * row then leaves out; and what the overflow menu then opens on. Holes (CONTRIBUTING.md, Tests):
 *  1. `detail` is a name: nothing here proves the host hands it non-null exactly when a reader is
 *     drawn beside the list, nor that `ListDetailPanes` then narrows the list.
 *  2. Reads are pinned as whole lines, so `!` and `||` are seen; what wraps the `actions` lambda
 *     or the overflow's `DropdownMenu` is not.
 *  3. Places are source offsets inside the browse bar's `actions`, not positions on screen; that the
 *     folded block is the first thing of the overflow menu says nothing about what `DropdownMenu`
 *     draws first.
 *  4. Nothing here holds the value the composable collects, nor sees a menu opened, a dot drawn or
 *     a label read out.
 *  The selection bar's folding is `SelectionBarBesideReaderWiringLintTest`'s.
 *  The filter entries' own wiring is `FilterEntryWiringLintTest`'s, the sort entries'
 *  `CurrentMenuChoiceWiringTest`'s, the overflow button's content `OutboxCountWiringTest`'s.
 */
class ListBesideReaderWiringLintTest {

    @Test fun `the condition is named once, from the reader beside the list`() {
        val declarations = lines().filter { Regex("""\blistBesideReader\s*=(?!=)""").containsMatchIn(it) }
        assertEquals(
            "One declaration, read from `detail`: `panes` alone is set when the reading pane " +
                "setting refused the reader (#228), and the list, the whole window then, would lose " +
                "its phone bar; an orientation or a width threshold would fold the bar of a list " +
                "that is not narrow. Declarations found were:\n${declarations.joinToString("\n")}",
            listOf("val listBesideReader = detail != null"),
            declarations,
        )
    }

    @Test fun `every read of the condition, whole and in source order`() {
        val reads = lines().filter { Regex("""\blistBesideReader\b""").containsMatchIn(it) }
            .filterNot { it.startsWith("val listBesideReader") }
        assertEquals(
            "The selection bar is handed the condition once (its folding is " +
                "`SelectionBarBesideReaderWiringLintTest`'s), and the browse bar reads it four times: " +
                "the row drops filter and sort, the overflow " +
                "button carries the filters' state and dot (menu.active, never the raw toggles), and " +
                "the menu opens on the folded sections. A flipped `!` or a widened `||` compiles and " +
                "draws the wrong bar on one of the two layouts. Reads found were:\n${reads.joinToString("\n")}",
            EXPECTED_READS,
            reads,
        )
    }

    @Test fun `beside a reader the row holds no funnel and no sort, and keeps search and the overflow`() {
        val actions = browseBarActions(text())
        val at = actions.indexOf("if (!listBesideReader) {")
        check(at >= 0) { "the browse bar's actions no longer fold on `if (!listBesideReader) {`:\n$actions" }
        val folded = balancedFrom(actions, at)
        val rest = actions.removeRange(at, at + folded.length)
        assertEquals(
            "Funnel and sort, their buttons and their menus, must sit inside the folded block and " +
                "nowhere else in the row; search and the overflow outside it; and no `else` after it, " +
                "which would draw a second row beside a reader. Folded block was:\n$folded\nRest was:\n$rest",
            listOf(true, true, false, false, false, false, false, true, true, false),
            listOf(
                "Icons.Filled.FilterList" in folded,
                "Icons.AutoMirrored.Filled.Sort" in folded,
                "Icons.Filled.Search" in folded,
                "Icons.Filled.MoreVert" in folded,
                "Icons.Filled.FilterList" in rest,
                "Icons.AutoMirrored.Filled.Sort" in rest,
                Regex("""\b(filterOpen|sortOpen)\b""").containsMatchIn(rest),
                "Icons.Filled.Search" in rest,
                "Icons.Filled.MoreVert" in rest,
                actions.substring(at + folded.length).trimStart().startsWith("else"),
            ),
        )
    }

    @Test fun `beside a reader the overflow opens on the filters, then the sort orders, then its own entries`() {
        val text = text()
        val at = text.indexOf(OVERFLOW_MENU)
        check(at >= 0) { "InboxScreen.kt no longer opens the overflow menu with `$OVERFLOW_MENU`" }
        val content = balancedFrom(text, at + OVERFLOW_MENU.length - 1).drop(1).trimStart()
        assertEquals(
            "The menu's content must open on the folded sections, whole: filters under 'Filters', " +
                "a divider, sort orders under 'Sort', a divider, then Select all and the entries " +
                "after it, unchanged (#48, #82). Content found was:\n$content",
            "$EXPECTED_FOLDED_SECTIONS $FIRST_OWN_ENTRY",
            content.take(EXPECTED_FOLDED_SECTIONS.length + 1 + FIRST_OWN_ENTRY.length),
        )
    }

    @Test fun `a section label is no entry`() {
        val text = text()
        val at = text.indexOf("private fun MenuSectionLabel(")
        check(at >= 0) { "InboxScreen.kt no longer declares MenuSectionLabel" }
        assertEquals(
            "A section label takes no click and reads as a heading: drawn as a DropdownMenuItem it " +
                "is a tappable line that does nothing, read out as one more entry.",
            EXPECTED_LABEL_DECLARATION,
            balancedFrom(text, at),
        )
    }

    /** The `actions = { … }` lambda of the browse bar: the `TopAppBar` after `titleSwitchTargets`. */
    private fun browseBarActions(text: String): String {
        val anchor = text.indexOf("val titleSwitchTargets")
        check(anchor >= 0) { "InboxScreen.kt no longer declares titleSwitchTargets above the browse bar" }
        val at = text.indexOf("actions =", text.indexOf("TopAppBar(", anchor))
        check(at >= 0) { "the browse bar has no actions block" }
        return balancedFrom(text, at)
    }

    /** [text] from [from] up to the `}` that balances the first `{` at or after it. */
    private fun balancedFrom(text: String, from: Int): String {
        val open = text.indexOf('{', from)
        check(open >= 0) { "no block opens after offset $from" }
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
            if (depth == 0) break
        }
        return text.substring(from, i)
    }

    private fun lines(): List<String> = DeclarationSource.kotlinLines(DeclarationSource.file(INBOX_SCREEN))

    private fun text(): String = lines().joinToString(" ").replace(Regex("""\s+"""), " ")

    private companion object {
        const val INBOX_SCREEN = "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"

        val EXPECTED_READS = listOf(
            "besideReader = listBesideReader,",
            "if (!listBesideReader) {",
            "modifier = if (listBesideReader && menu.active) Modifier.semantics { stateDescription = filterOnLabel } else Modifier,",
            "BadgedBox(badge = { if (listBesideReader && menu.active) Badge(containerColor = dotColor) }) {",
            "if (listBesideReader) {",
        )

        const val OVERFLOW_MENU =
            "DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }, shape = MaterialTheme.shapes.medium) {"

        const val EXPECTED_FOLDED_SECTIONS =
            "if (listBesideReader) { " +
                "MenuSectionLabel(stringResource(R.string.search_filters)) " +
                "FilterMenuEntries(menu = menu, viewModel = viewModel) " +
                "HorizontalDivider() " +
                "MenuSectionLabel(stringResource(R.string.inbox_sort)) " +
                "SortMenuEntries(current = ui.sortOrder, onPick = { viewModel.setSortOrder(it); overflowOpen = false }) " +
                "HorizontalDivider() }"

        const val FIRST_OWN_ENTRY =
            "DropdownMenuItem( text = { Text(stringResource(R.string.inbox_select_all)) },"

        const val EXPECTED_LABEL_DECLARATION =
            "private fun MenuSectionLabel(text: String) { Text( text, " +
                "style = MaterialTheme.typography.labelMedium, " +
                "color = MaterialTheme.colorScheme.onSurfaceVariant, " +
                "modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).semantics { heading() }, ) }"
    }
}
