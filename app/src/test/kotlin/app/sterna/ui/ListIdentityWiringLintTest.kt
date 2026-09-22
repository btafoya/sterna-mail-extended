package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test. The decision, [app.sterna.ui.inbox.ReadingPaneRule.forList],
 * is executed by `AnchorBeforeListTest`; what no JVM test can reach is whether the `message/…`
 * destination asks it before paging, whether a tap writes the identity it compares, and whether
 * `InboxViewModel` keeps that identity out of its saved state. Whole trimmed lines, selected by a
 * fragment and compared whole: a fragment rule is blind to what is appended to a line.
 *
 * Holes (`CONTRIBUTING.md` § Tests) that apply here:
 *  1. `inboxViewModel` is a name: that it is the "inbox" entry's ViewModel is pinned by
 *     `TwoPaneHostLintTest` for the conversion arm and by the reader's own lines, not here.
 *  3. `val arrived` is pinned, not that it is computed after `inboxViewModel` is resolved, nor
 *     that the conversion arm's call sits inside its `withResumed`.
 *  4. Nothing here proves that a ViewModel rebuilt after process death draws another value; the
 *     declaration pinned below is the whole of that argument.
 */
class ListIdentityWiringLintTest {

    @Test fun `the reader asks the rule before choosing what to page over`() {
        val code = codeLines(STERNA_APP)
        assertEquals(
            "the message destination must read the identity the tap wrote into the route, and " +
                "build the one anchor both of its arms judge",
            listOf(
                "val listArg = entry.arguments?.getString(\"list\")?.let { Uri.decode(it) }?.ifBlank { null }",
                "val routeAnchor = MessageAnchor.fromRoute(emailId, accountId, index, src, threadArg, listArg)",
            ),
            code.filter { it.startsWith("val listArg") || it.startsWith("val routeAnchor") },
        )
        assertEquals(
            "the full-screen reader must page over what ReadingPaneRule.forList leaves of the " +
                "route, never over the raw `src` / `index`: after a process death the list behind " +
                "is a fresh InboxViewModel on the 'Open on' view, and the kept index opens, marks " +
                "read and offers a receipt for another message",
            listOf(
                "val arrived = ReadingPaneRule.forList(routeAnchor, inboxViewModel.listIdentity)",
                "val listSource = if (arrived.src == \"list\") inboxViewModel.pagedEmails else null",
                "val searchResults = if (arrived.src == \"search\") {",
                "val threadEntries = if (arrived.src == \"thread\" && threadKey != null) {",
                "initialIndex = arrived.index,",
            ),
            code.filter { it.startsWith("val arrived") || "src ==" in it || it.startsWith("initialIndex") },
        )
    }

    @Test fun `the conversion to the pane asks the same rule`() {
        assertEquals(
            "every openInPane of SternaApp.kt, whole: the two taps hand an anchor built in this " +
                "ViewModel; the reader -> pane conversion hands a route that may have outlived " +
                "its list, and must go through ReadingPaneRule.forList like the reader does, or " +
                "the pane opens the new list's message at the old index",
            listOf(
                "if (readingPane) inboxViewModel.openInPane(anchor)",
                "if (readingPane) inboxViewModel.openInPane(anchor)",
                "inboxViewModel.openInPane(ReadingPaneRule.forList(routeAnchor, inboxViewModel.listIdentity))",
            ),
            codeLines(STERNA_APP).filter { "openInPane(" in it },
        )
    }

    @Test fun `a list or search tap writes the identity of the list it was made in`() {
        assertEquals(
            "the list/search tap must carry the ViewModel's identity: without it every route " +
                "counts as another list and the reader stops paging after an ordinary tap. The " +
                "conversation tap carries none, its route pages by its key",
            listOf(
                "val anchor = MessageAnchor(id, accountId, if (fromSearch) \"search\" else \"list\", index, null, inboxViewModel.listIdentity)",
                "val anchor = MessageAnchor(id, accountId, \"thread\", index, threadKey.encode())",
            ),
            codeLines(STERNA_APP).filter { it.startsWith("val anchor = MessageAnchor(") },
        )
    }

    @Test fun `the identity is drawn at construction and never saved`() {
        assertEquals(
            "every line of InboxViewModel.kt naming listIdentity, whole. The identity is what " +
                "tells a process death from a rotation: saved in the SavedStateHandle (or " +
                "persisted with the pane's anchor) it comes back after the death, the reader " +
                "trusts the index again and the defect is back whole",
            listOf("val listIdentity: String = UUID.randomUUID().toString()"),
            codeLines(INBOX_VIEW_MODEL).filter { "listIdentity" in it },
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
        const val INBOX_VIEW_MODEL = "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"
    }
}
