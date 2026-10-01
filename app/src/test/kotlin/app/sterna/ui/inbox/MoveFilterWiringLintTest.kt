package app.sterna.ui.inbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, not a behaviour test, the instrument of [SubscribedFoldersSurfaceWiringTest],
 * pointed at the move-to-folder picker's filter field (#182).
 */
class MoveFilterWiringLintTest {

    @Test fun `the sheet draws the FILTERED rows, and each row carries its own mailbox`() {
        assertEquals(
            "the sheet no longer filters what it draws — the field would type and the list " +
                "would not move, which is #182 handed straight back.",
            listOf("val shownMoveRows = filterFolderRows(moveRows, query)"),
            block(MOVE_SHEET, "val shownMoveRows =", 1),
        )
        assertEquals(
            "the sheet must iterate the FILTERED rows.",
            listOf("items(shownMoveRows) { row ->"),
            block(MOVE_SHEET, "items(shownMoveRows)", 1),
        )
        assertEquals(
            "the tapped row must name ITS OWN folder. Anything indexed — a rank in either list — " +
                "files mail into a folder nobody pointed at.",
            listOf("onPick(row.folder.id)"),
            block(MOVE_SHEET, "onPick(", 1),
        )
        assertEquals(
            "the list picker must move the selection into the folder the sheet names.",
            listOf("onPick = { folderId -> viewModel.moveSelectedTo(folderId, moveAccountId, fromPicker = true) },"),
            block(MOVE_SHEET, "onPick = ", 1),
        )
    }

    @Test fun `the reader hands the sheet its query and its offered folders, and moves into the named folder`() {
        assertEquals(
            "the reader must hand the sheet its own offered folders, the account's whole list and its query.",
            listOf("targets = folders,", "allMailboxes = accountFolders,"),
            block(MESSAGE_SCREEN, "targets = ", 2),
        )
        assertEquals(
            listOf("query = moveQuery,", "onQueryChange = moveSheet::filter,"),
            block(MESSAGE_SCREEN, "query = ", 2),
        )
        assertEquals(
            "the reader's move must name the folder the sheet hands back, and still stamp the " +
                "message with the folder the VM resolved and its owning account.",
            listOf(
                "onPick = { folderId ->",
                "onMove(",
                "loaded.email.copy(",
                "mailboxId = resolvedMailbox ?: loaded.email.mailboxId,",
                "accountId = accountId ?: loaded.email.accountId,",
                "),",
                "folderId,",
                "moveAccountId,",
                ")",
            ),
            block(MESSAGE_SCREEN, "onPick = ", 9),
        )
    }

    @Test fun `the query is held INSIDE each screen's if, so it never survives a reopening`() {
        for ((screen, call) in listOf(
            INBOX_SCREEN to "MoveFolderSheet(viewModel, accounts, selectedKeys, currentMailboxId = ui.selectedMailboxId, query = moveQuery, onQueryChange = { moveQuery = it }, onDismiss = { showMoveSheet = false })",
            SEARCH_SCREEN to "MoveFolderSheet(inbox, accounts, selectedKeys, currentMailboxId = null, query = moveQuery, onQueryChange = { moveQuery = it }, onDismiss = { showMoveSheet = false })",
        )) {
            assertEquals(
                "${screen.name} must hold the query inside `if (showMoveSheet)`: hoisted above it, " +
                    "it outlives the sheet, which reopens already filtered.",
                listOf("if (showMoveSheet) {", "var moveQuery by rememberSaveable { mutableStateOf(\"\") }", call),
                block(screen, "if (showMoveSheet) {", 3),
            )
        }
        assertEquals(
            "the sheet holds no query of its own: the screens' survives a rotation, one in the sheet would be a second.",
            0,
            codeLines(MOVE_SHEET).count { it.startsWith("var ") && "uery" in it },
        )
    }

    @Test fun `the empty line is drawn only when something was actually typed`() {
        assertEquals(
            "'No folder matches' must need BOTH halves: no row left AND a non-empty query. On the " +
                "query alone it never shows; on the rows alone it claims a failed search in a " +
                "picker that simply has nothing to offer.",
            listOf("if (shownMoveRows.isEmpty() && query.isNotEmpty()) {"),
            block(MOVE_SHEET, "if (shownMoveRows.isEmpty()", 1),
        )
    }

    @Test fun `the rows are painted over the WHOLE offered list, with the paths resolved once`() {
        assertEquals(
            "the sheet must paint every OFFERED row and filter afterwards: painting the filtered " +
                "list makes the rows depend on what has been typed, and the paths are memoized " +
                "per target, by position.",
            listOf("for ((folder, path) in targets.zip(movePaths)) {"),
            block(MOVE_SHEET, "for ((folder, path) in", 1),
        )
        assertEquals(
            "the parent paths must be resolved against the account's WHOLE folder list (#109) and " +
                "OUTSIDE the painting loop: mailboxPathLabel walks a map rebuilt over that whole " +
                "list, and the filter field re-runs this block on every keystroke.",
            listOf(
                "val movePaths = remember(targets, allMailboxes) {",
                "targets.map { folder -> mailboxPathLabel(folder, allMailboxes) }",
                "}",
            ),
            block(MOVE_SHEET, "val movePaths = remember(", 3),
        )
        assertEquals(
            "the list picker must hand the sheet the selection account's whole folder list.",
            listOf("allMailboxes = moveTargetMailboxes,"),
            block(MOVE_SHEET, "allMailboxes = ", 1),
        )
    }

    // ── instrument (copied from SubscribedFoldersSurfaceWiringTest) ──────────────────────────────

    /** [count] consecutive code lines from the one line starting with [prefix]. */
    private fun block(file: File, prefix: String, count: Int): List<String> {
        val lines = codeLines(file)
        val at = only(lines, prefix)
        return lines.subList(at, minOf(at + count, lines.size))
    }

    /** The index of the single matching code line; fails loudly on none or several. */
    private fun only(lines: List<String>, prefix: String): Int {
        val hits = lines.indices.filter { lines[it].startsWith(prefix) }
        return hits.singleOrNull()
            ?: error(
                "${hits.size} code lines match `$prefix` — this lint reads the shipped source and " +
                    "must be taught the new shape rather than left green over something it never read",
            )
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule is satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        private const val INBOX_SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_SCREEN_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }

        val INBOX_SCREEN: File by lazy { File(root, INBOX_SCREEN_PATH) }
        val MOVE_SHEET: File by lazy { File(root, "app/src/main/kotlin/app/sterna/ui/inbox/MoveFolderSheet.kt") }
        val SEARCH_SCREEN: File by lazy { File(root, "app/src/main/kotlin/app/sterna/ui/search/SearchScreen.kt") }
        val MESSAGE_SCREEN: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")
        }
    }
}
