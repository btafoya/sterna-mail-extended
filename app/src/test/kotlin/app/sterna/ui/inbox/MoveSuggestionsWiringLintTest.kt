package app.sterna.ui.inbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source lint, and only because `InboxViewModel` and `MessageViewModel` need an `Application` and
 * Room. The decisions are executed in [MoveSuggestionsTest]; what is read here is where each is
 * called from and with what: the account a picker's memory is read for, the moment a move is
 * remembered, the Undo that takes it back, and the section on the two pickers.
 */
class MoveSuggestionsWiringLintTest {

    // ── what is read ───────────────────────────────────────────────────────────────────────────

    @Test fun `the list picker reads the memory of the account it lists`() {
        assertEquals(
            "the list picker's memory must follow selectionListing, the account its folders were " +
                "observed on: read for the owner, a picker switched to another account proposes " +
                "the owner's folders, or a homonym (#92).",
            listOf(
                "val selectionMoveHistory: StateFlow<MoveHistory> =",
                "selectionListing.flatMapLatest { (accountId, _) -> moveMemory.history(accountId) }",
                ".stateIn(viewModelScope, SharingStarted.Eagerly, MoveHistory.NONE)",
            ),
            block(INBOX_VIEW_MODEL, "val selectionMoveHistory:", 3),
        )
        assertEquals(
            "the reader's memory must follow movePickerAccountId, the account the picker lists.",
            listOf(
                "val moveHistory: StateFlow<MoveHistory> =",
                "movePickerAccountId.flatMapLatest { id -> moveMemory.history(id) }",
                ".stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MoveHistory.NONE)",
            ),
            block(MESSAGE_VIEW_MODEL, "val moveHistory:", 3),
        )
        assertEquals(
            "the selection's sender must be the executed decision, over the cached rows of the whole selection.",
            listOf("_selectedKeys.mapLatest { keys -> if (keys.isEmpty()) null else singleSender(repo.cachedEmailsByIds(keys), keys.size) }"),
            block(INBOX_VIEW_MODEL, "_selectedKeys.mapLatest { keys -> if (keys.isEmpty()) null else singleSender(", 1),
        )
    }

    // ── what is written, and when ──────────────────────────────────────────────────────────────

    @Test fun `the reader's move is remembered on the write's own verdict`() {
        val one = body(INBOX_VIEW_MODEL, "moveTo")
        assertEquals(
            "moveTo() must name the picked folder to swipeRemove, the only path that remembers it. Body was:\n$one",
            listOf("swipeRemove(email, getApplication<Application>().getString(R.string.status_message_moved), pickedInto = targetMailboxId) { c, id ->"),
            codeLinesNaming(one, "swipeRemove("),
        )
        val swipe = codeLinesOf(body(INBOX_VIEW_MODEL, "swipeRemove"))
        assertEquals(
            "swipeRemove must remember the picked move from what the write returned, never at the " +
                "finger: a move refused offline would lead the next picker.",
            listOf(
                "val remembered = pickedInto?.let { rememberPickedMove(PickedMove(credentials.id, it), landedByTheSwipe(written, email)) }",
                "posted?.remembered = remembered",
            ),
            swipe.filter { "remembered" in it },
        )
        assertLineOrder("swipeRemove", swipe, "val written = hidingThreadMembers(setOf(email.emailKey())) { runCatching { op(credentials, email.id) } }", swipe.first { "rememberPickedMove(" in it })
        assertEquals(
            "the other swipes name no picked folder: archive, delete, spam are not chosen in a picker.",
            1,
            codeLines(INBOX_VIEW_MODEL).count { "pickedInto = targetMailboxId" in it },
        )
    }

    @Test fun `the selection's move is remembered over the rows that really moved`() {
        val batched = codeLinesOf(body(INBOX_VIEW_MODEL, "bulkBatched"))
        assertEquals(
            "bulkBatched must remember the picked move over `moved`, the rows the server took, and " +
                "hand it to both bars the gesture may stand behind.",
            listOf(
                "val remembered = pickedInto?.let { rememberPickedMove(it, moved.map { candidate -> candidate.target.email }) }",
                "posted?.remembered = remembered",
                "landedOffer?.remembered = remembered",
            ),
            batched.filter { "remembered" in it },
        )
        assertLineOrder("bulkBatched", batched, "val moved = movedKeys(flown).mapNotNull { (key, dest) -> byKey[key]?.let { UndoCandidate(it, dest) } }", batched.first { "rememberPickedMove(" in it })
        val many = body(INBOX_VIEW_MODEL, "moveSelectedTo")
        assertEquals(
            "moveSelectedTo must hand bulkBatched the picked move through the executed decision, " +
                "which answers null for the selection bar's Inbox button. Body was:\n$many",
            listOf("pickedInto = pickedInto(fromPicker, moveAccountId, targetMailboxId),"),
            codeLinesNaming(many, "pickedInto"),
        )
        assertEquals(
            "moveSelectedTo takes where it was called from with no default: a new caller must say it.",
            listOf("fun moveSelectedTo(targetMailboxId: String, targetAccountId: String?, fromPicker: Boolean) {"),
            block(INBOX_VIEW_MODEL, "fun moveSelectedTo(", 1),
        )
    }

    @Test fun `the selection bar's Inbox button says it is not the picker`() {
        assertEquals(
            "the Inbox button of the selection bar moves without a folder being chosen, it must " +
                "not be remembered.",
            listOf("onClick = { inboxId?.let { viewModel.moveSelectedTo(it, targetAccountId = null, fromPicker = false) } },"),
            block(SELECTION_TOP_BAR, "onClick = { inboxId?.let { viewModel.moveSelectedTo(", 1),
        )
        assertEquals(
            "and the picker says it is.",
            listOf("onPick = { folderId -> viewModel.moveSelectedTo(folderId, moveAccountId, fromPicker = true) },"),
            codeLines(MOVE_SHEET).filter { "moveSelectedTo(" in it },
        )
    }

    @Test fun `a move between accounts is remembered in the target account, on Moved alone`() {
        assertEquals(
            "moveToAccount must remember the move in the TARGET account's memory, over the executed verdict.",
            listOf("rememberPickedMove(PickedMove(target.id, targetMailboxId), landedAcrossAccounts(mapOf(key to result), mapOf(key to email)))"),
            codeLinesNaming(body(INBOX_VIEW_MODEL, "moveToAccount"), "rememberPickedMove("),
        )
        assertEquals(
            "moveSelectedToAccount must remember the move over the keyed outcomes of this batch.",
            listOf("rememberPickedMove(PickedMove(target.id, targetMailboxId), landedAcrossAccounts(outcomes, emails))"),
            codeLinesNaming(body(INBOX_VIEW_MODEL, "moveSelectedToAccount"), "rememberPickedMove("),
        )
    }

    @Test fun `one place writes the memory, and the Undo takes back what its gesture wrote`() {
        val code = codeLines(INBOX_VIEW_MODEL)
        assertEquals(
            "the memory is written by rememberPickedMove alone and retracted by undo() alone.",
            listOf(
                "moveMemory.record(into.accountId, event)",
                "action.remembered?.let { moveMemory.retract(it.accountId, it.event) }",
            ),
            code.filter { "moveMemory.record(" in it || "moveMemory.retract(" in it },
        )
        assertEquals(
            listOf("val event = pickedMoveEvent(landed, into.mailboxId, System.currentTimeMillis()) ?: return null"),
            code.filter { "pickedMoveEvent(" in it },
        )
        val undo = codeLinesOf(body(INBOX_VIEW_MODEL, "undo"))
        assertLineOrder(
            "undo() must retract after the write it reverses has come back: the event is written by that write's own coroutine",
            undo, "action.flight?.join()", "action.remembered?.let { moveMemory.retract(it.accountId, it.event) }",
        )
    }

    // ── the section on screen ──────────────────────────────────────────────────────────────────

    @Test fun `both pickers draw the section from the executed decision, over the whole painted list`() {
        assertEquals(
            listOf("val suggestedMoveRows = suggestedRows(moveSuggestions, moveListedAccountId, moveRows, query)"),
            codeLines(MOVE_SHEET).filter { "suggestedRows(" in it },
        )
        assertEquals(
            "the section must follow pickerAccount(chosen, owner).",
            listOf("val moveListedAccountId = pickerAccount(chosenAccountId, ownerAccountId)"),
            codeLines(MOVE_SHEET).filter { it.startsWith("val moveListedAccountId") },
        )
        assertEquals(
            "the list picker must hand the sheet the owner and the choice it lists on.",
            listOf("ownerAccountId = moveOwnerAccountId,", "chosenAccountId = moveAccountId,"),
            block(MOVE_SHEET, "ownerAccountId = ", 2),
        )
        assertEquals(
            "the reader must hand the sheet the owner and the choice it lists on.",
            listOf("ownerAccountId = moveOwnerAccountId,", "chosenAccountId = moveAccountId,"),
            block(MESSAGE_SCREEN, "ownerAccountId = ", 2),
        )
        assertEquals(
            "the titles and the section are drawn only when there is a section.",
            listOf("if (suggestedMoveRows.isNotEmpty()) {"),
            codeLines(MOVE_SHEET).filter { it.startsWith("if (suggestedMoveRows") },
        )
        for (name in listOf("suggestedRows(", "freezeSuggestions(", "FolderPickerRowItem(")) {
            assertEquals("MessageScreen computes or draws a section of its own (`$name`)", 0, codeLines(MESSAGE_SCREEN).count { name in it })
        }
    }

    @Test fun `a suggested row does exactly what the same row below does`() {
        assertEquals(
            "both lists must hand their row to the one tap handler, and the mark must be the " +
                "executed decision's, never a rank.",
            listOf(
                "FolderPickerRowItem(suggested.row, suggested.usualForSender) { pickMoveRow(suggested.row) }",
                "FolderPickerRowItem(row) { pickMoveRow(row) }",
            ),
            codeLines(MOVE_SHEET).filter { it.startsWith("FolderPickerRowItem(") },
        )
        assertEquals(
            "the reader keeps one move call.",
            1,
            codeLines(MESSAGE_SCREEN).count { it.startsWith("onMove(") },
        )
    }

    @Test fun `the section is frozen at the opening of each picker`() {
        assertEquals(
            listOf(
                "var moveFrozen by remember { mutableStateOf<FrozenSuggestions?>(null) }",
                "val moveSuggestions = freezeSuggestions(moveFrozen, moveListedAccountId, history, targets, sender, System.currentTimeMillis())",
                "SideEffect { moveFrozen = moveSuggestions }",
            ),
            codeLines(MOVE_SHEET).filter { "moveFrozen" in it },
        )
        assertEquals(
            "the list picker must hand the sheet the selection's memory and sender.",
            listOf("history = moveHistory,", "sender = moveSender,"),
            block(MOVE_SHEET, "history = ", 2),
        )
        assertEquals(
            "the reader must hand the sheet its picker's memory and the open message's sender.",
            listOf(
                "val folderHistory by viewModel.moveHistory.collectAsStateWithLifecycle()",
                "history = folderHistory,",
                "sender = loaded.email.from.firstOrNull()?.email,",
            ),
            block(MESSAGE_SCREEN, "val folderHistory by", 1) + block(MESSAGE_SCREEN, "history = ", 2),
        )
        assertEquals("the reader's sheet is composed inside `if (movePicker)`", 1, opensBefore(MESSAGE_SCREEN, "if (movePicker) {", "MoveFolderSheet("))
    }

    @Test fun `the two titles and the sender mark exist in the ten languages`() {
        val files = listOf(File(res, "values/strings.xml")) + (res.listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.startsWith("values-") }
            .map { File(it, "strings.xml") }
            .filter { it.isFile }
        assertEquals("ten string files", 10, files.size)
        val expected = setOf("inbox_move_suggestions", "inbox_move_all_folders", "inbox_move_usual_for_sender")
        val missing = files.associate { it.parentFile.name to (expected - keysOf(it)) }.filterValues { it.isNotEmpty() }
        assertEquals(emptyMap<String, Set<String>>(), missing)
    }

    // ── instrument (copied from CrossAccountMoveWiringLintTest) ─────────────────────────────────

    private fun keysOf(file: File): Set<String> =
        Regex("<(?:string|plurals)\\s+name=\"([^\"]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toSet()

    private fun codeLinesOf(body: String): List<String> =
        body.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { it.isNotEmpty() }

    private fun codeLinesNaming(body: String, needle: String): List<String> =
        codeLinesOf(body).filter { needle in it }

    private fun assertLineOrder(what: String, lines: List<String>, first: String, then: String) {
        val i = lines.indexOf(first)
        val j = lines.indexOf(then)
        assertTrue("$what — no code line reads exactly <$first>", i >= 0)
        assertTrue("$what — no code line reads exactly <$then>", j >= 0)
        assertTrue("$what — <$first> (line $i) must come BEFORE <$then> (line $j)", i < j)
    }

    private fun opensBefore(file: File, outer: String, inner: String): Int {
        val lines = codeLines(file)
        val o = lines.indexOfFirst { it.startsWith(outer) }
        val i = lines.indexOfFirst { it.startsWith(inner) }
        return if (o in 0 until i) 1 else 0
    }

    private fun codeLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val code = line.trim()
        if (code.isEmpty() || code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(code).takeIf { it.isNotBlank() }
    }

    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    private fun rawCodeLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val code = line.trimStart()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }

    private fun body(file: File, name: String): String {
        val lines = rawCodeLines(file)
        val declaration = Regex("""\bfun\s+$name\s*\(""")
        val start = lines.indexOfFirst { declaration.containsMatchIn(it) }
        check(start >= 0) { "${file.name} declares no '$name' — did it get renamed?" }
        val indent = lines[start].indentWidth()
        val out = mutableListOf<String>()
        var closed = false
        var depth = 0
        for (i in start until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            if (closed && i > start && line.indentWidth() <= indent) break
            out += line
            depth += line.count { it == '(' || it == '{' } - line.count { it == ')' || it == '}' }
            closed = depth == 0
        }
        return out.joinToString("\n")
    }

    private fun String.indentWidth() = length - trimStart().length

    private fun block(file: File, prefix: String, count: Int): List<String> {
        val lines = codeLines(file)
        val hits = lines.indices.filter { lines[it].startsWith(prefix) }
        val at = hits.singleOrNull()
            ?: error("${hits.size} code lines of ${file.name} start with `$prefix` — teach this lint the new shape rather than leave it green over something it never read")
        return lines.subList(at, minOf(at + count, lines.size))
    }

    private companion object {
        private const val APP = "app/src/main"
        private const val INBOX_VIEW_MODEL_PATH = "$APP/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }
        val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
        val MESSAGE_VIEW_MODEL: File by lazy { File(root, "$APP/kotlin/app/sterna/ui/message/MessageViewModel.kt") }
        val MOVE_SHEET: File by lazy { File(root, "$APP/kotlin/app/sterna/ui/inbox/MoveFolderSheet.kt") }
        val SELECTION_TOP_BAR: File by lazy { File(root, "$APP/kotlin/app/sterna/ui/inbox/SelectionTopBar.kt") }
        val MESSAGE_SCREEN: File by lazy { File(root, "$APP/kotlin/app/sterna/ui/message/MessageScreen.kt") }
        val res: File by lazy { File(root, "$APP/res") }
    }
}
