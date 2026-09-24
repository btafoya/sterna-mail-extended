package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test: it reads `InboxViewModel.kt` as text. The decisions it pins the
 * call sites of are run by `EveryAccountUnreadTest` (#235); `InboxViewModel` cannot be built on this
 * bench. Whole lines, arguments included. What it does not cover:
 *  2. the expression around a pinned line, beyond the line itself;
 *  3. the position of a line in a control structure beyond the function it is read in, and the
 *     order in which the flows run;
 *  4. the value a composable collects.
 */
class EveryAccountUnreadWiringLintTest {

    @Test
    fun `the scopes a bulk action and the freshness door read go through unreadScopesFor`() {
        assertEquals(
            "currentScopes() must resolve the unread view with unreadScopesFor on the selection in " +
                "hand: 'accountUnreadScopes.value' makes a 'Mark all read' or 'Select all' in the " +
                "unread view of every account reach one account only, and the freshness door judge " +
                "the wrong scope.",
            listOf("is Sel.Unread -> unreadScopesFor(sel, accountUnreadScopes.value, everyAccountUnreadScopes.value)"),
            unreadArms("private fun currentScopes()"),
        )
    }

    @Test
    fun `the refresh of every account's unread view refreshes every account`() {
        assertEquals(
            "refresh() must send the unread view of every account to refreshEveryUnreadScope(): " +
                "refreshUnreadScope() there syncs the current account alone, and pulling to refresh " +
                "leaves the other accounts' rows stale.",
            listOf("is Sel.Unread -> if (sel.allAccounts) refreshEveryUnreadScope() else refreshUnreadScope()"),
            unreadArms("fun refresh()"),
        )
    }

    @Test
    fun `every account's folder lists are observed whenever every account is on screen`() {
        assertEquals(
            "The folder lists must be wanted on showsEveryAccount, 'All inboxes' included: the " +
                "drawer's 'Unread' count under 'All inboxes' is summed from them, and wanted on " +
                "unreadOfEveryAccount alone it reads 0 there until the view is opened.",
            listOf(
                "everyAccountFoldersFlow(store.accountsFlow, selection.map { showsEveryAccount(it) }) " +
                    "{ accountId -> repo.observeMailboxes(accountId) }",
            ),
            codeLines().filter { "everyAccountFoldersFlow(" in it },
        )
    }

    @Test
    fun `the search of every account's unread view searches every account`() {
        assertEquals(
            "searchAccounts() must ask showsEveryAccount: 'is Sel.Unified' sends the search of the " +
                "unread view of every account to the current account's server alone.",
            "private fun searchAccounts(): List<AccountCredentials> = " +
                "if (showsEveryAccount(selection.value)) store.allCredentials() else listOfNotNull(store.load())",
            codeLines().dropWhile { !it.startsWith("private fun searchAccounts()") }.take(2).joinToString(" "),
        )
    }

    /** The lines of [header]'s body that start an arm on `Sel.Unread`. */
    private fun unreadArms(header: String): List<String> {
        val lines = codeLines()
        val start = lines.indexOfFirst { it.startsWith(header) }
        check(start >= 0) { "InboxViewModel.kt declares no '$header' — renamed?" }
        var depth = 0
        var end = start
        for (i in start until lines.size) {
            depth += lines[i].count { it == '{' } - lines[i].count { it == '}' }
            end = i
            if (depth == 0 && i > start) break
        }
        return lines.subList(start, end + 1).filter { it.startsWith("is Sel.Unread ->") }
    }

    /** The ViewModel, trimmed, blank lines and comment lines dropped. */
    private fun codeLines(): List<String> = INBOX_VIEW_MODEL.readLines().map { it.trim() }
        .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    private companion object {
        const val INBOX_VIEW_MODEL_PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        val INBOX_VIEW_MODEL: File by lazy {
            val root = generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
            File(root, INBOX_VIEW_MODEL_PATH)
        }
    }
}
