package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test: it reads `InboxScreen.kt` and `InboxViewModel.kt` as text and
 * proves nothing about what a tap does. It exists for the Starred entry of the folder drawer
 * (#255), which neither a composable nor this ViewModel lets a JVM test reach.
 *
 * What the entry opens is the list's own search, and that is run elsewhere: `BarSearchTest`
 * executes `ask`, `SearchScopeTest` the scope a view opens it on. The switch that reveals the
 * entry is pinned by `StarredInDrawerWiringTest`.
 *
 * Holes (CONTRIBUTING.md, Tests):
 *  1. `starredInDrawer` is a name on the guard; the read that binds it to the CompositionLocal is
 *     pinned here, what the CompositionLocal is given is pinned by `StarredInDrawerWiringTest`.
 *  2. Whole trimmed lines throughout, so the expression around a call is held on its line.
 *  3. The entry's place is pinned as one run of lines, from the close of the "All inboxes" entry to
 *     the first line of the "Unread" one. That the brace above it closes the multi-account guard
 *     is checked a second way, by reading every `accounts.size > 1` block for the opener. Nothing
 *     says the run sits in `DrawerContent` rather than in another composable of the file.
 *  4. Nothing here holds the selection `setSearchActive` reads when the tap arrives, so the scope
 *     the search opens on is not seen; only that no scope is written at this call site.
 *
 * What it does not see: a brace inside a string literal of a multi-account block would throw the
 * block reader off; whether the drawer closes; what the search returns.
 */
class StarredDrawerEntryWiringLintTest {

    // -- the drawer --------------------------------------------------------------------------------

    @Test fun `the entry sits under its switch, between All inboxes and Unread, and opens the search`() {
        val lines = codeLines(INBOX_SCREEN)
        val at = lines.indexOfFirst { it == GUARD }
        val from = at - BEFORE.size
        val expected = BEFORE + ENTRY + AFTER
        assertEquals(
            "the Starred entry of the drawer is pinned as one run of lines, with its place: after " +
                "the block that holds \"All inboxes\" has closed, so that a single account gets " +
                "it too, and before \"Unread\". Under any other condition than its own switch " +
                "it shows for readers who never asked for it; without `openStarredSearch()` it " +
                "closes the drawer and does nothing.",
            expected,
            if (from < 0) emptyList() else lines.subList(from, minOf(from + expected.size, lines.size)),
        )
    }

    @Test fun `the drawer reads the switch from the CompositionLocal, once`() {
        assertEquals(
            "InboxScreen.kt must read the setting as '$READ', once. A parameter on DrawerContent " +
                "instead would have to be threaded through both envelopes.",
            listOf(READ),
            codeLines(INBOX_SCREEN).filter { "LocalStarredInDrawer" in it && !it.startsWith("import ") },
        )
        assertEquals(
            "`starredInDrawer` must be named on two lines of InboxScreen.kt only: where it is read " +
                "and where it guards the entry. A third line is a reassignment or a shadow.",
            listOf(READ, GUARD),
            codeLines(INBOX_SCREEN).filter { Regex("""\bstarredInDrawer\b""").containsMatchIn(it) },
        )
    }

    @Test fun `the entry is the only caller, and no second account is needed to see it`() {
        val text = codeLines(INBOX_SCREEN).joinToString(" ")
        assertEquals(
            "exactly one place in InboxScreen.kt may call openStarredSearch(): the drawer entry",
            1,
            Regex("""\bopenStarredSearch\s*\(""").findAll(text).count(),
        )
        val guarded = Regex("""\bif\s*\(\s*accounts\.size\s*>\s*1\s*\)""").findAll(text)
            .map { balancedFrom(text, it.range.last) }
            .toList()
        assertEquals(
            "this rule reads the multi-account blocks of InboxScreen.kt and found none holding " +
                "selectUnified(): it is guarding nothing",
            1,
            guarded.count { "selectUnified(" in it },
        )
        assertEquals(
            "the Starred entry must stay out of every 'accounts.size > 1' block: a search for " +
                "starred mail means as much on a single-account install. Blocks were:\n" +
                guarded.joinToString("\n"),
            0,
            guarded.count { "openStarredSearch(" in it },
        )
    }

    // -- the opener --------------------------------------------------------------------------------

    @Test fun `the opener asks the list's own search for starred mail, with no words and no scope of its own`() {
        val lines = codeLines(INBOX_VIEW_MODEL)
        val at = lines.indexOfFirst { it == OPENER.first() }
        assertEquals(
            "`openStarredSearch` is pinned whole. `setSearchActive(true)` is what opens the bar on " +
                "the scope of the view in hand; `openSearch(true, …)` here would write a scope of " +
                "its own. The query carries `flagged = true` and nothing else: a word, or another " +
                "criterion, is a search the reader did not ask for. `clearSelection()` first, the " +
                "results replace the rows a selection was made on.",
            OPENER,
            if (at < 0) emptyList() else lines.subList(at, minOf(at + OPENER.size, lines.size)),
        )
    }

    // -- reading the sources -----------------------------------------------------------------------

    /** The `{ … }` block opening at or after [from], braces included. */
    private fun balancedFrom(text: String, from: Int): String {
        val open = text.indexOf('{', from)
        check(open >= 0) { "no block opens after offset $from" }
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) { '{' -> depth++; '}' -> depth-- }
            if (depth == 0) return text.substring(open, i + 1)
        }
        error("unclosed block after offset $from")
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule can be satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        const val READ = "val starredInDrawer = LocalStarredInDrawer.current"
        const val GUARD = "if (starredInDrawer) {"

        /** The close of the "All inboxes" entry, then of the multi-account block around it. */
        val BEFORE = listOf(
            "viewModel.selectUnified()",
            "scope.launch { drawerState.close() }",
            "},",
            "modifier = Modifier.padding(horizontal = 12.dp),",
            ")",
            "}",
        )

        val ENTRY = listOf(
            GUARD,
            "NavigationDrawerItem(",
            "icon = { Icon(Icons.Filled.Star, contentDescription = null) },",
            "label = { Text(stringResource(R.string.filter_starred)) },",
            "selected = false,",
            "onClick = {",
            "viewModel.openStarredSearch()",
            "scope.launch { drawerState.close() }",
            "},",
            "modifier = Modifier.padding(horizontal = 12.dp),",
            ")",
            "}",
        )

        /** The first line of the "Unread" entry. */
        val AFTER = listOf("val unreadLabel = if (ui.unreadViewCount > 0) {")

        val OPENER = listOf(
            "fun openStarredSearch() {",
            "clearSelection()",
            "setSearchActive(true)",
            "barSearch.ask(SearchQuery(flagged = true), AddressNames())",
            "}",
        )

        private const val INBOX = "app/src/main/kotlin/app/sterna/ui/inbox"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "$INBOX/InboxScreen.kt").isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }

        val INBOX_SCREEN: File by lazy { File(root, "$INBOX/InboxScreen.kt") }
        val INBOX_VIEW_MODEL: File by lazy { File(root, "$INBOX/InboxViewModel.kt") }
    }
}
