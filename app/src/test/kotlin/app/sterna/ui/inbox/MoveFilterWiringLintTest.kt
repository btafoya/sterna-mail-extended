package app.sterna.ui.inbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — the instrument of [SubscribedFoldersSurfaceWiringTest],
 * pointed at the move-to-folder picker's filter field (#182).
 *
 * The rule itself is EXECUTED next door ([FolderFilterTest]). What no JVM test in this module can
 * run is the Compose code that CALLS it, and the whole feature lives there: `filterFolderRows`
 * can be perfect and the picker still show every folder. There is no `androidTest` source set
 * here, so a source lint is not the second-best instrument, it is the only one.
 *
 * Whole lines are compared, never `contains`: a `contains` check is blind to every mutation that
 * LENGTHENS the line, and three regressions have walked through that hole in this repo.
 *
 * What it is here to catch, in BOTH copies of the dialog — the list's selection bar and the
 * reader's overflow entry, which have to stay the same picker (#73):
 *  1. the rows DRAWN being the unfiltered list, which silently gives #182 back in full while
 *     every test stays green — the field types, and nothing narrows;
 *  2. the tapped row being addressed by RANK instead of carrying its own mailbox: filter, tap the
 *     second row left, and the mail lands in whatever sits second in the UNFILTERED list. On the
 *     thousand-folder account that reported #182 that is mail filed where nobody chose — the one
 *     failure in this dialog that costs data, not pixels;
 *  3. the filter query surviving the dialog: held anywhere but INSIDE the `if`, the field reopens
 *     with the last search still in it and a list already cut down, which reads as folders lost;
 *  4. "No folder matches" losing the `query.isNotEmpty()` half, so the picker claims a failed
 *     search on an account that simply has no target to offer (a lie, and there was one there
 *     before this change);
 *  5. the painting loop walking the FILTERED list, which would make the number of composable
 *     calls in the loop depend on what has been typed;
 *  6. the parent-path resolution falling back INTO the loop, where it re-walks a map built over
 *     the account's whole folder list on every keystroke — the O(n^2) the memo exists to stop.
 */
class MoveFilterWiringLintTest {

    @Test fun `the list picker draws the FILTERED rows, and each row carries its own mailbox`() {
        assertEquals(
            "the list picker no longer filters what it draws — the field would type and the list " +
                "would not move, which is #182 handed straight back.",
            listOf("val shownMoveRows = filterFolderRows(moveRows, moveQuery)"),
            block(INBOX_SCREEN, "val shownMoveRows =", 1),
        )
        assertEquals(
            "the list picker must iterate the FILTERED rows.",
            listOf("shownMoveRows.forEach { row ->"),
            block(INBOX_SCREEN, "shownMoveRows.forEach", 1),
        )
        assertEquals(
            "the tapped row must move the selection into ITS OWN folder. Anything indexed — a " +
                "rank in either list — files mail into a folder nobody pointed at.",
            listOf("viewModel.moveSelectedTo(row.folder.id)"),
            block(INBOX_SCREEN, "viewModel.moveSelectedTo(row.folder.id)", 1),
        )
    }

    @Test fun `the reader's picker draws the FILTERED rows, and each row carries its own mailbox`() {
        assertEquals(
            "the reader's picker no longer filters what it draws (#182).",
            listOf("val shownFolderRows = filterFolderRows(folderRows, folderQuery)"),
            block(MESSAGE_SCREEN, "val shownFolderRows =", 1),
        )
        assertEquals(
            "the reader's picker must iterate the FILTERED rows.",
            listOf("shownFolderRows.forEach { row ->"),
            block(MESSAGE_SCREEN, "shownFolderRows.forEach", 1),
        )
        assertEquals(
            "the reader's move must name the tapped row's OWN folder, and still stamp the message " +
                "with the folder the VM resolved and its owning account.",
            listOf(
                "onMove(",
                "loaded.email.copy(",
                "mailboxId = resolvedMailbox ?: loaded.email.mailboxId,",
                "accountId = accountId ?: loaded.email.accountId,",
                "),",
                "row.folder.id,",
                ")",
            ),
            block(MESSAGE_SCREEN, "onMove(", 7),
        )
    }

    @Test fun `the query is held INSIDE the dialog, so it never survives a reopening`() {
        assertEquals(
            "the list picker's query must be remembered inside `if (showMoveSheet)`. Hoisted to " +
                "the screen it outlives the dialog, which reopens already filtered.",
            listOf("var moveQuery by remember { mutableStateOf(\"\") }"),
            block(INBOX_SCREEN, "var moveQuery by", 1),
        )
        assertEquals(
            "the reader's query must be remembered inside `if (movePicker)`.",
            listOf("var folderQuery by remember { mutableStateOf(\"\") }"),
            block(MESSAGE_SCREEN, "var folderQuery by", 1),
        )
        // Held inside the `if` means: declared AFTER the line that opens it, and before the dialog.
        assertEquals("the list picker's query is declared outside `if (showMoveSheet)`", 1, opensBefore(INBOX_SCREEN, "if (showMoveSheet) {", "var moveQuery by"))
        assertEquals("the reader's query is declared outside `if (movePicker)`", 1, opensBefore(MESSAGE_SCREEN, "if (movePicker) {", "var folderQuery by"))
    }

    @Test fun `the empty line is drawn only when something was actually typed`() {
        assertEquals(
            "'No folder matches' must need BOTH halves: no row left AND a non-empty query. On the " +
                "query alone it never shows; on the rows alone it claims a failed search in a " +
                "picker that simply has nothing to offer.",
            listOf("if (shownMoveRows.isEmpty() && moveQuery.isNotEmpty()) {"),
            block(INBOX_SCREEN, "if (shownMoveRows.isEmpty()", 1),
        )
        assertEquals(
            "the reader's empty line must need both halves too.",
            listOf("if (shownFolderRows.isEmpty() && folderQuery.isNotEmpty()) {"),
            block(MESSAGE_SCREEN, "if (shownFolderRows.isEmpty()", 1),
        )
    }

    @Test fun `the rows are painted over the WHOLE offered list, with the paths resolved once`() {
        assertEquals(
            "the list picker must paint every OFFERED row and filter afterwards: painting the " +
                "filtered list makes the number of composable calls in the loop depend on what " +
                "has been typed, and the paths are memoized per target, by position.",
            listOf("for ((folder, path) in targets.zip(movePaths)) {"),
            block(INBOX_SCREEN, "for ((folder, path) in", 1),
        )
        assertEquals(
            "the reader's picker must paint every offered row and filter afterwards.",
            listOf("for ((folder, path) in folders.zip(folderPaths)) {"),
            block(MESSAGE_SCREEN, "for ((folder, path) in", 1),
        )
        assertEquals(
            "the reader's parent paths must be resolved against the account's WHOLE folder list " +
                "(#109) and OUTSIDE the painting loop: mailboxPathLabel walks a map rebuilt over " +
                "that whole list, and the filter field re-runs this block on every keystroke.",
            listOf(
                "val folderPaths = remember(folders, accountFolders) {",
                "folders.map { folder -> mailboxPathLabel(folder, accountFolders) }",
                "}",
            ),
            block(MESSAGE_SCREEN, "val folderPaths = remember(", 3),
        )
        // The list picker's own copy of that block is pinned by SubscribedFoldersSurfaceWiringTest,
        // where the #109 guard it carries is already spelled out.
    }

    // ── instrument (copied from SubscribedFoldersSurfaceWiringTest) ──────────────────────────────

    /** [count] consecutive code lines from the ONE line starting with [prefix]. */
    private fun block(file: File, prefix: String, count: Int): List<String> {
        val lines = codeLines(file)
        val at = only(lines, prefix)
        return lines.subList(at, minOf(at + count, lines.size))
    }

    /** 1 when the ONE line starting with [outer] comes before the ONE starting with [inner]. */
    private fun opensBefore(file: File, outer: String, inner: String): Int {
        val lines = codeLines(file)
        return if (only(lines, outer) < only(lines, inner)) 1 else 0
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
        val MESSAGE_SCREEN: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")
        }
    }
}
