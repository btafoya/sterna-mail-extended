package app.sterna.ui.inbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Source lint, because the two screens need an `Application` and Room. What the sheet shows is
 * executed in [MoveSuggestionsTest]; what is read here is that the list (its search results
 * included) and the reader open the one sheet, that the reader keeps no copy of it, and that the
 * list's open sheet survives a rotation.
 */
class MoveFolderSheetLintTest {

    /** The reader's sheet is not here: a rotation can swap its host, see `MoveSheetHostLintTest`. */
    @Test fun `an open sheet and its query survive a rotation, in the list and its search results`() {
        for ((screen, open) in listOf(
            INBOX_SCREEN to "var showMoveSheet by rememberSaveable { mutableStateOf(false) }",
        )) {
            val name = open.substringAfter("var ").substringBefore(" by")
            assertEquals(
                "${screen.name}: `$name` held in a plain remember closes the sheet on a rotation.",
                listOf(open),
                codeLines(screen).filter { it.startsWith("var $name by") },
            )
            assertEquals(
                "${screen.name}: the filter's text must be saveable, or a rotation empties it.",
                listOf("var moveQuery by rememberSaveable { mutableStateOf(\"\") }"),
                codeLines(screen).filter { it.startsWith("var moveQuery by") },
            )
        }
    }

    @Test fun `a sheet restored without its selection is closed, not drawn empty`() {
        for (screen in listOf(INBOX_SCREEN)) {
            val lines = codeLines(screen)
            val guard = "if (showMoveSheet && selectedKeys.isEmpty()) showMoveSheet = false"
            assertEquals(
                "${screen.name}: the open flag survives process death, the selection does not; " +
                    "without this guard the sheet opens over nothing to move.",
                listOf(guard),
                lines.filter { it.startsWith("if (showMoveSheet") && it != "if (showMoveSheet) {" },
            )
            assertEquals(
                "${screen.name}: the guard must run right before the sheet's `if`.",
                lines.indexOf(guard) + 1,
                lines.indexOf("if (showMoveSheet) {"),
            )
        }
    }

    @Test fun `the two screens open the one sheet, under their own flag`() {
        for ((screen, flag) in listOf(INBOX_SCREEN to "if (showMoveSheet) {", MESSAGE_SCREEN to "if (movePicker) {")) {
            val lines = codeLines(screen)
            val opens = lines.indices.filter { lines[it].startsWith("MoveFolderSheet(") }
            assertEquals("${screen.name} must call MoveFolderSheet exactly once", 1, opens.size)
            val flagAt = lines.indexOfFirst { it == flag }
            assertEquals("${screen.name}: the sheet is composed under `$flag`", true, flagAt in 0 until opens.single())
        }
        assertEquals(
            "no source may still call the dialog the sheet replaced",
            emptyList<String>(),
            sources().filter { file -> codeLines(file).any { "SelectionMoveDialog" in it } }.map { it.name },
        )
    }

    @Test fun `the reader keeps no copy of the picker`() {
        val code = codeLines(MESSAGE_SCREEN)
        for (piece in listOf(
            "FolderPickerRowItem(", "FolderPickerSectionTitle(", "filterFolderRows(", "suggestedRows(",
            "freezeSuggestions(", "MoveAccountRow(", "mailboxPathLabel(", "R.string.inbox_filter_folders",
            "R.string.inbox_move_suggestions", "R.string.inbox_no_folder_matches",
        )) {
            assertEquals("MessageScreen draws a picker of its own again (`$piece`)", emptyList<String>(), code.filter { piece in it })
        }
        assertEquals(
            "the move-to-folder title belongs to the overflow entry alone; a second use is a dialog copy.",
            listOf("text = { Text(stringResource(R.string.inbox_move_to_folder)) },"),
            code.filter { "R.string.inbox_move_to_folder" in it },
        )
        val start = code.indexOfFirst { it == "if (movePicker) {" }
        val end = code.indexOfFirst { it.startsWith("MoveFolderSheet(") }
        assertEquals(
            "an AlertDialog between `if (movePicker)` and the sheet is a picker copy",
            emptyList<String>(),
            code.subList(start, end).filter { "AlertDialog(" in it },
        )
    }

    @Test fun `the sheet is a bottom sheet with no Cancel, no dialog and no summoned keyboard`() {
        val code = codeLines(MOVE_SHEET)
        assertEquals(1, code.count { it.startsWith("ModalBottomSheet(") })
        assertEquals(1, code.count { it.startsWith("LazyColumn(") })
        for (banned in listOf("AlertDialog", "inbox_cancel", "FocusRequester", "focusRequester", "KeyboardOptions", "keyboardActions", "imeAction")) {
            assertFalse("MoveFolderSheet.kt uses `$banned`", code.any { banned in it })
        }
    }

    @Test fun `the sender's mark is an icon at the end of the row, described`() {
        val code = codeLines(PICKER_ROWS)
        assertEquals(
            listOf(
                "if (usualForSender) {",
                "Icon(",
                "Icons.Outlined.Person,",
                "contentDescription = stringResource(R.string.inbox_move_usual_for_sender),",
            ),
            code.subList(code.indexOf("if (usualForSender) {"), code.indexOf("if (usualForSender) {") + 4),
        )
        assertEquals(1, code.count { "size(16.dp)" in it })
    }

    // ── instrument ─────────────────────────────────────────────────────────────────────────────

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule is satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private fun sources(): List<File> = File(root, "app/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private companion object {
        private const val UI = "app/src/main/kotlin/app/sterna/ui"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "$UI/inbox/InboxScreen.kt").isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }
        val INBOX_SCREEN: File by lazy { File(root, "$UI/inbox/InboxScreen.kt") }
        val MESSAGE_SCREEN: File by lazy { File(root, "$UI/message/MessageScreen.kt") }
        val MOVE_SHEET: File by lazy { File(root, "$UI/inbox/MoveFolderSheet.kt") }
        val PICKER_ROWS: File by lazy { File(root, "$UI/inbox/FolderPickerRows.kt") }
    }
}
