package app.sterna.ui.inbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, not a behaviour test: `InboxViewModel`, `MessageViewModel` and the composables that
 * hand the folder order to [mailboxTree] and [moveTargets] cannot run on the JVM. What the order
 * does once handed over is `FolderOrderRenderTest`'s, executed; which order each surface is handed
 * is this file's, and every assertion pins the ARGUMENTS, since a call handed `emptyList()` still
 * compiles and draws a list, in another order than the drawer's.
 */
class FolderOrderWiringTest {

    @Test fun `every tree of folders is built with the account's order`() {
        assertEquals(
            "a surface builds the folder tree without the account's folder order, or from another " +
                "order than the one it was handed: it then draws its folders in another order than " +
                "the drawer. The three calls are the drawer, the folder screen and the " +
                "special-folder picker; a fourth caller must be taught here.",
            listOf(
                "mailboxTree(ui.visibleMailboxes, collapsedIds, ui.folderOrder).forEach { node ->",
                "return mailboxTree(drawn, collapsed = emptySet(), order = order).map { node ->",
                "val targets = mailboxTree(listed, collapsed = emptySet(), order = order)",
            ),
            MAIN_SOURCES.sortedBy { it.name }.flatMap { file ->
                codeLines(file).filter { "mailboxTree(" in it && !it.startsWith("internal fun mailboxTree(") }
            }.sortedBy { line -> CALL_ORDER.indexOfFirst { prefix -> line.startsWith(prefix) } },
        )
    }

    @Test fun `the drawer's order is the current account's, and only meets that account's list`() {
        assertEquals(
            "the drawer's folder order must be read off the CURRENT account's record, as the hide " +
                "registry is: any other record orders one account's folders by another's.",
            listOf(
                "private val currentFolderOrder: StateFlow<FolderOrdering> =",
                "combine(currentAccountId, store.accountsFlow) { id, accounts -> folderOrderingOf(id, accounts) }",
                ".stateIn(viewModelScope, SharingStarted.Eagerly, FolderOrdering(null, emptyList()))",
            ),
            block(INBOX_VIEW_MODEL, "private val currentFolderOrder:", 3),
        )
        assertEquals(
            "the order must be applied only to the list of the account it was read from, the one " +
                "the recorded snapshot names: on an account switch the new record's order arrives " +
                "before the new list, and on IMAP folder ids are paths shared by two accounts of " +
                "one server. Executed by FolderOrderRenderTest; that this site passes the LISTED " +
                "account is this test's.",
            listOf(
                "private val orderedState = combine(badgedState, currentFolderOrder) { badged, order ->",
                "badged.copy(folderOrder = order.forList(folderSnapshot.value.first).ids)",
            ),
            block(INBOX_VIEW_MODEL, "private val orderedState =", 2),
        )
        assertEquals(
            "MailUi.folderOrder must be fed from the folded state, once.",
            1,
            codeLines(INBOX_VIEW_MODEL).count { it == "folderOrder = base.folderOrder," },
        )
    }

    @Test fun `the list's move picker sorts with the order of the account its folders came from`() {
        assertEquals(
            "the list's move picker must read its order off the account its folder list was " +
                "observed on, the pair selectionListing carries, never off the picker's account id " +
                "alone, which moves before the list it names has arrived.",
            listOf(
                "val selectionFolderOrder: StateFlow<List<String>> =",
                "combine(selectionListing, store.accountsFlow) { (accountId, _), accounts -> folderOrderingOf(accountId, accounts).ids }",
                ".stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())",
            ),
            block(INBOX_VIEW_MODEL, "val selectionFolderOrder:", 3),
        )
        assertEquals(
            "the list's picker must be handed that order and no other.",
            listOf("val moveTargetsOrder by viewModel.selectionFolderOrder.collectAsStateWithLifecycle()"),
            block(INBOX_SCREEN, "val moveTargetsOrder by", 1),
        )
    }

    // ── instrument (copied from SubscribedFoldersSurfaceWiringTest) ──────────────────────────────

    /** [count] consecutive code lines from the one line starting with [prefix]. */
    private fun block(file: File, prefix: String, count: Int): List<String> {
        val lines = codeLines(file)
        val hits = lines.indices.filter { lines[it].startsWith(prefix) }
        val at = hits.singleOrNull()
            ?: error("${hits.size} code lines start with `$prefix`: teach this lint the new shape")
        return lines.subList(at, minOf(at + count, lines.size))
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule is satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        private val CALL_ORDER = listOf("mailboxTree(ui", "return mailboxTree(", "val targets = mailboxTree(")

        private const val INBOX_SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_SCREEN_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }

        val INBOX_SCREEN: File by lazy { File(root, INBOX_SCREEN_PATH) }
        val INBOX_VIEW_MODEL: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt")
        }
        val MAIN_SOURCES: List<File> by lazy {
            File(root, "app/src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        }
    }
}
