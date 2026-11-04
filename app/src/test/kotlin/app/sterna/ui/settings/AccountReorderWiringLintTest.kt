package app.sterna.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, and a last resort: what the accounts' reorder mode DECIDES is executed in
 * [AccountReorderRowsTest], and nothing in this module composes, nor builds an [AccountsViewModel].
 * What only this can see is that the screen asks those rules, and that each gesture reaches the
 * store's move with the id of the row it was made on.
 *
 * Holes 1, 2 and 4 of `CONTRIBUTING.md` apply: `accounts` and `rows` are pinned by name only,
 * nothing here sees an expression wrapped around a call, and the list the screen collects is never
 * observed. Hole 3 in part: the screen's lines are pinned as one run, from the mode's state to the
 * first line of the list it replaces, so their order is seen, not what runs when.
 *
 * Whole trimmed lines, by equality, never `contains`.
 */
class AccountReorderWiringLintTest {

    @Test fun `the screen offers the mode from two blocks and swaps the list for the reorder column`() {
        assertEquals(
            "the accounts screen's reorder mode changed shape. The button must be offered on " +
                "mayReorderAccounts alone (one login and its shared mailboxes is one block, nothing " +
                "to order), the mode must close with it, the back gesture must leave the mode before " +
                "the screen, and the mode must replace the whole list: kept, the list's rows still " +
                "switch the current account on a tap and the Add button sits among rows being dragged.",
            listOf(
                "var reorderAsked by rememberSaveable { mutableStateOf(false) }",
                "val reorderable = mayReorderAccounts(accounts)",
                "val reordering = reorderAsked && reorderable",
                "LaunchedEffect(reorderable) { if (!reorderable) reorderAsked = false }",
                "BackHandler(enabled = reordering) { reorderAsked = false }",
                "DetailScaffold(",
                "title = stringResource(R.string.settings_accounts_screen_title),",
                "onBack = onBack,",
                "actions = {",
                "if (reorderable) {",
                "TextButton(onClick = { reorderAsked = !reordering }) {",
                "Text(stringResource(if (reordering) R.string.folders_reorder_done else R.string.folders_reorder_start))",
                "}",
                "}",
                "},",
                ") { padding ->",
                "Box(Modifier.fillMaxSize().padding(padding)) {",
                "if (reordering) {",
                "AccountReorderColumn(accounts, viewModel, Modifier.fillMaxSize())",
                "} else LazyColumn(Modifier.fillMaxSize()) {",
                "if (pending.isNotEmpty()) {",
            ),
            block(SETTINGS, "var reorderAsked by", 21),
        )
    }

    @Test fun `a drop is judged by accountDrop and written as the rank it answers, for the row lifted`() {
        assertEquals(
            "the column no longer draws accountReorderRows with accountDrop as its judge, or no " +
                "longer hands moveAccountTo the rank accountDrop answers. Written with `to` itself, " +
                "a row index, an account with shared mailboxes above it lands a block too far; " +
                "without `span` a login is carried without its shared mailboxes.",
            listOf(
                "val rows = accountReorderRows(accounts)",
                "ReorderableColumn(",
                "items = rows,",
                "key = { it.account.id },",
                "canMove = { from, to -> accountDrop(rows, from, to) != null },",
                "onMove = { from, to -> accountDrop(rows, from, to)?.let { viewModel.moveAccountTo(rows[from].account.id, it) } },",
                "span = { accountBlockSpan(rows, it) },",
            ),
            block(COLUMN, "val rows = accountReorderRows(", 7),
        )
    }

    @Test fun `the arrows are drawn on their own flag and move the row they sit on`() {
        assertEquals(
            "an arrow tap no longer moves the account of its own row, in its own direction.",
            listOf("AccountReorderLine(row, handle) { up -> viewModel.moveAccount(row.account.id, up) }"),
            block(COLUMN, "AccountReorderLine(row, handle) {", 1),
        )
        assertEquals(
            "the Up and Down buttons are no longer drawn on their own flag, or no longer say which " +
                "way they move and which account, to a screen reader.",
            listOf(
                "MoveSlot(moves.mayMoveUp, Icons.Filled.KeyboardArrowUp, stringResource(R.string.folders_reorder_up, name)) { onMove(true) }",
                "MoveSlot(moves.mayMoveDown, Icons.Filled.KeyboardArrowDown, stringResource(R.string.folders_reorder_down, name)) { onMove(false) }",
            ),
            block(COLUMN, "MoveSlot(moves.mayMoveUp,", 2),
        )
        assertEquals(
            "the drag handle no longer carries the modifier the column hands it, or no longer says " +
                "what it is to a screen reader: without the modifier a long press starts nothing.",
            listOf(
                "Icons.Filled.DragHandle,",
                "contentDescription = stringResource(R.string.folders_reorder_drag),",
                "modifier = handle.size(48.dp).padding(12.dp),",
            ),
            block(COLUMN, "Icons.Filled.DragHandle,", 3),
        )
    }

    @Test fun `no row of the mode switches or opens an account`() {
        assertEquals(
            "a row of the reorder mode is a tap target again: a tap meant for an arrow that lands " +
                "beside it switches the current account, or does so on an account not signed in.",
            emptyList<String>(),
            codeLines(COLUMN).filter { "clickable" in it || "switchTo(" in it || "onOpenAccount" in it },
        )
    }

    @Test fun `both moves go to the store's own moves, which read the list under its lock`() {
        assertEquals(
            "the move no longer goes to AccountStore.moveAccount with the id and direction it was " +
                "given: a list computed here and saved would lose a share discovery adds meanwhile.",
            listOf(
                "fun moveAccount(id: String, up: Boolean) {",
                "store.moveAccount(id, up)",
            ),
            block(ACCOUNTS_VIEW_MODEL, "fun moveAccount(", 2),
        )
        assertEquals(
            "the drop no longer goes to AccountStore.moveAccountTo with the rank it was given.",
            listOf(
                "fun moveAccountTo(id: String, rank: Int) {",
                "store.moveAccountTo(id, rank)",
            ),
            block(ACCOUNTS_VIEW_MODEL, "fun moveAccountTo(", 2),
        )
    }

    // ── instrument (copied from FolderManagementWiringLintTest) ──────────────────────────────────

    private fun block(file: File, prefix: String, count: Int): List<String> {
        val lines = codeLines(file)
        val hits = lines.indices.filter { lines[it].startsWith(prefix) }
        val at = hits.singleOrNull()
            ?: error(
                "${hits.size} code lines start with `$prefix` in ${file.name} — this lint reads the " +
                    "shipped source and must be taught the new shape rather than left green over " +
                    "something it never read",
            )
        return lines.subList(at, minOf(at + count, lines.size))
    }

    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        private const val SETTINGS_PATH = "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SETTINGS_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }

        val SETTINGS: File by lazy { File(root, SETTINGS_PATH) }
        val COLUMN: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/AccountReorderColumn.kt")
        }
        val ACCOUNTS_VIEW_MODEL: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/AccountsViewModel.kt")
        }
    }
}
