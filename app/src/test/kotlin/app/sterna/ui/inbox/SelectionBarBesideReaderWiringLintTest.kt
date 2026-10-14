package app.sterna.ui.inbox

import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source lint, not a behaviour test: whether the selection bar folds read/unread and move into its
 * overflow menu is decided inside a composable, which nothing in this module composes. Pinned: the
 * one call hands it `listBesideReader`, whole; each read of `besideReader` in the bar, whole; that
 * the two row buttons sit inside the folded blocks and nowhere else in the row; and that the menu
 * opens on the same two entries under the opposite read. Holes (CONTRIBUTING.md, Tests):
 *  1. `besideReader = listBesideReader` binds a name: what `listBesideReader` is bound to is
 *     `ListBesideReaderWiringLintTest`'s.
 *  2. Reads are pinned as whole lines, so `!` and `||` are seen; what wraps `actions` or the
 *     `DropdownMenu` is not.
 *  3. Places are source offsets inside `actions`, not positions on screen; that the folded entries
 *     open the `else` of `if (selSnooze)` says nothing about what `DropdownMenu` draws first.
 *  4. Nothing here holds the value the composable collects, nor sees a menu opened or a bar drawn
 *     at 280 dp.
 *  The menu's other entries and their conditions (#82) are pinned only as the text that follows
 *  the folded block; the snooze presets are `PickInstantHostLintTest`'s.
 */
class SelectionBarBesideReaderWiringLintTest {

    @Test fun `the one call hands the bar the reader-beside condition, whole`() {
        val text = text(INBOX_SCREEN)
        val calls = Regex("""\bSelectionTopBar\(""").findAll(text).map { it.range.first }.toList()
        assertEquals("InboxScreen.kt must call SelectionTopBar once. Text was:\n$text", 1, calls.size)
        assertEquals(
            "The bar must be told `listBesideReader`, the same condition the browse bar folds on " +
                "(#258), and nothing derived from `panes` or the window's width.",
            EXPECTED_CALL,
            balanced(text, calls.single(), '(', ')'),
        )
    }

    @Test fun `every read of besideReader in the bar, whole and in source order`() {
        val reads = DeclarationSource.kotlinLines(DeclarationSource.file(SELECTION_BAR))
            .filter { Regex("""\bbesideReader\b""").containsMatchIn(it) }
        assertEquals(
            "One parameter without a default, so a new caller has to say which bar it draws; the " +
                "row drops read/unread and move under `!besideReader`, the menu adds them under " +
                "`besideReader`. A flipped `!` compiles and shows both or neither. Reads found were:\n" +
                reads.joinToString("\n"),
            EXPECTED_READS,
            reads,
        )
    }

    @Test fun `beside a reader the row keeps archive, delete and the overflow, in their order`() {
        val actions = actions()
        val read = actions.indexOf(FOLDED_READ)
        val archive = actions.indexOf(ARCHIVE_IF)
        val move = actions.indexOf(FOLDED_MOVE)
        val delete = actions.indexOf(DELETE_BUTTON)
        val box = actions.indexOf("Box {")
        assertTrue(
            "The row must read: folded read/unread, archive or unarchive, folded move, delete, the " +
                "boxed overflow (#74), in that order. Offsets were read=$read archive=$archive " +
                "move=$move delete=$delete box=$box in:\n$actions",
            listOf(read, archive, move, delete, box).all { it >= 0 } &&
                read < archive && archive < move && move < delete && delete < box,
        )
        val rest = actions.replace(FOLDED_READ, "").replace(FOLDED_MOVE, "").replace(FOLDED_MENU, "")
        assertEquals(
            "Read/unread and move must appear in the folded row blocks and the folded menu block " +
                "and nowhere else in the bar's actions. Rest was:\n$rest",
            listOf(false, false, false, false, false),
            listOf(
                "toggleSelectedRead" in rest,
                Regex("""\bonMove\b""").containsMatchIn(rest),
                "DriveFileMove" in rest,
                "inbox_mark_read" in rest,
                "inbox_move_to_folder" in rest,
            ),
        )
        assertEquals(
            "No `else` may follow a folded row block: it would draw a second button beside a reader.",
            listOf(false, false),
            listOf(
                actions.substring(read + FOLDED_READ.length).trimStart().startsWith("else"),
                actions.substring(move + FOLDED_MOVE.length).trimStart().startsWith("else"),
            ),
        )
    }

    @Test fun `beside a reader the menu opens on read or unread, then move, then its own entries`() {
        val actions = actions()
        val snooze = actions.indexOf(SNOOZE_IF)
        check(snooze >= 0) { "the bar's menu no longer swaps on `$SNOOZE_IF`:\n$actions" }
        val presets = balanced(actions, snooze, '{', '}')
        val after = actions.substring(snooze + presets.length).trimStart()
        check(after.startsWith("else {")) { "`$SNOOZE_IF` is no longer followed by `else {`:\n$after" }
        val menu = balanced(after, 0, '{', '}').substringAfter("else {").trimStart()
        assertEquals(
            "The menu level (not the snooze presets) must open on the folded entries, each closing " +
                "the menu then acting, followed by Select all and the entries after it unchanged " +
                "(#82). Content found was:\n$menu",
            "$FOLDED_MENU $FIRST_OWN_ENTRIES",
            menu.take(FOLDED_MENU.length + 1 + FIRST_OWN_ENTRIES.length),
        )
        assertEquals(
            "The folded entries must not leak into the snooze presets. Presets were:\n$presets",
            false,
            "besideReader" in presets,
        )
    }

    private fun actions(): String {
        val text = text(SELECTION_BAR)
        val at = text.indexOf("actions = {")
        check(at >= 0) { "SelectionTopBar.kt has no `actions = {` block" }
        return balanced(text, at, '{', '}')
    }

    /** [text] from [from] up to the [close] that balances the first [open] at or after it. */
    private fun balanced(text: String, from: Int, open: Char, close: Char): String {
        val start = text.indexOf(open, from)
        check(start >= 0) { "no `$open` after offset $from" }
        var depth = 0
        var i = start
        while (i < text.length) {
            when (text[i]) {
                open -> depth++
                close -> depth--
            }
            i++
            if (depth == 0) break
        }
        return text.substring(from, i)
    }

    private fun text(path: String): String =
        DeclarationSource.kotlinLines(DeclarationSource.file(path)).joinToString(" ").replace(Regex("""\s+"""), " ")

    private companion object {
        const val INBOX_SCREEN = "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"
        const val SELECTION_BAR = "app/src/main/kotlin/app/sterna/ui/inbox/SelectionTopBar.kt"

        const val EXPECTED_CALL =
            "SelectionTopBar( viewModel = viewModel, count = selectedKeys.size, " +
                "selectionAllRead = selectionAllRead, " +
                "currentRole = ui.mailboxes.firstOrNull { it.id == ui.selectedMailboxId }?.role, " +
                "inboxId = ui.mailboxes.firstOrNull { it.role == \"inbox\" }?.id, " +
                "trash = trash, besideReader = listBesideReader, " +
                "onMove = { showMoveSheet = true }, onSelectAll = { viewModel.selectAll() }, )"

        val EXPECTED_READS = listOf(
            "besideReader: Boolean,",
            "if (!besideReader) {",
            "if (!besideReader) {",
            "if (besideReader) {",
        )

        const val FOLDED_READ =
            "if (!besideReader) { IconButton(onClick = { viewModel.toggleSelectedRead() }) { Icon( " +
                "if (selectionAllRead) Icons.Filled.MarkEmailUnread else Icons.Filled.DoneAll, " +
                "contentDescription = stringResource(if (selectionAllRead) R.string.inbox_mark_unread else R.string.inbox_mark_read), " +
                ") } }"

        const val ARCHIVE_IF = "if (currentRole == \"archive\" || currentRole == \"all\") {"

        const val FOLDED_MOVE =
            "if (!besideReader) { IconButton(onClick = onMove) { " +
                "Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = stringResource(R.string.inbox_move_to_folder)) } }"

        const val DELETE_BUTTON = "IconButton(onClick = { viewModel.deleteSelected() }) {"

        const val SNOOZE_IF = "if (selSnooze) {"

        const val FOLDED_MENU =
            "if (besideReader) { DropdownMenuItem( " +
                "text = { Text(stringResource(if (selectionAllRead) R.string.inbox_mark_unread else R.string.inbox_mark_read)) }, " +
                "leadingIcon = { Icon(if (selectionAllRead) Icons.Filled.MarkEmailUnread else Icons.Filled.DoneAll, contentDescription = null) }, " +
                "onClick = { selMenu = false; viewModel.toggleSelectedRead() }, ) " +
                "DropdownMenuItem( text = { Text(stringResource(R.string.inbox_move_to_folder)) }, " +
                "leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null) }, " +
                "onClick = { selMenu = false; onMove() }, ) }"

        const val FIRST_OWN_ENTRIES =
            "val inJunk = currentRole == \"junk\" DropdownMenuItem( " +
                "text = { Text(stringResource(R.string.inbox_select_all)) },"
    }
}
