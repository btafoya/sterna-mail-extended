package app.sterna.ui.inbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source lint, not a behaviour test: it reads `InboxViewModel.kt` as text and proves nothing about
 * behaviour. What a renumbered key resolves to is executed by `RestoredKeysTest`, what happens to
 * the rows and the ticks by `RenumberedRowsTest`; what only this can see is that Undo reports what
 * its restore renumbered before its job ends, and that the selection gestures ask for it after
 * their wait, in a view model that cannot be built on the JVM. Lines are compared whole.
 *
 * Holes, by their number in CONTRIBUTING.md:
 *  1. `keys`, `targetKeys`, `landing`, `it` are names: that `it` is the answer of `restoreAll` and
 *     `keys` the gesture's own selection is not readable here.
 *  3. Order is the order of lines in the function's text. `renumberRestored` is pinned inside
 *     undo() between the send and the hand-over, not inside the arm that runs on success.
 * The three gestures that resolve a selection ask `restoredRows`; the swipe and the open message's
 * move ask `handedOver`. The conversation gestures, the held-back destroy and the selection's move
 * to another account take the rows they read or were handed: no line here covers them.
 */
class RestoredKeysWiringLintTest {

    @Test fun `one record of what was renumbered, written by the restore and read by the resolution`() {
        assertEquals(
            "exactly these lines name the record, whole and in file order: its declaration, the " +
                "single-row gestures reading it, the restore writing it, the selection gestures " +
                "reading it, the search snapshot forgetting it when it is opened or left. A " +
                "second writer is an id nobody was told by the server; a third reader is a " +
                "translation copied beside the ones `RestoredKeysTest` runs. " +
                "Lines found:\n" + recordLines().joinToString("\n"),
            listOf(
                "private val restoredKeys = RestoredKeys()",
                "val now = restoredKeys.rowToWrite(row) { repo.cachedEmailsByIds(it) }",
                "restoredKeys.record(landing)",
                "restoredKeys.rowsFor(keys) { repo.cachedEmailsByIds(it) }",
                "restoredKeys.forget()",
            ),
            recordLines(),
        )
    }

    @Test fun `undo reports what its restore renumbered inside the job the gestures wait on`() {
        val body = bodyLines("fun undo()")
        val launch = body.indexOf("viewModelScope.launch {")
        val send = body.indexOfFirst { "repo.restoreAll(" in it }
        val report = body.indexOf(REPORT)
        val handed = body.indexOfFirst { it.startsWith("}.also { restore -> restoresInFlight.hold(") }
        assertTrue(
            "the report must sit after the send and before the hand-over that closes the launch: " +
                "a gesture released by the end of that job resolves at once, and what is " +
                "reported later it never reads. Found the launch at $launch, the send at $send, " +
                "the report at $report, the hand-over at $handed. Body was:\n" + body.joinToString("\n"),
            launch >= 0 && send > launch && report > send && handed > report,
        )
        assertEquals(
            "called once, from undo(), and declared once. The call closes the chain, past both " +
                "arms: a restore that threw reports too, as one that brought nothing home",
            listOf(
                REPORT,
                "private suspend fun renumberRestored(accountId: String?, restored: MailRepository.Restored, imap: Boolean) {",
            ),
            codeLines().map { it.trim() }.filter { "renumberRestored(" in it },
        )
        assertEquals(
            "and what a throw reports is every entry of that account, not come home",
            ".getOrElse { MailRepository.Restored(entries.mapTo(mutableSetOf()) { entry -> entry.emailId }) }",
            body[report - 1],
        )
    }

    @Test fun `the report records, then redraws the rows, then moves the ticks`() {
        assertEquals(
            "the whole body, in this order. The rows it reads are the ones the cache holds under " +
                "the new ids, and the same landing feeds the record, the snapshots and the ticks: " +
                "three sources would be three opinions on which key a message answers to. The " +
                "record comes first, it is what a waiting gesture reads",
            listOf(
                "val cached = repo.cachedEmailsByIds(restored.renumbered.values.map { EmailKey(accountId, it) })",
                "val landing = restoredLanding(accountId, restored, cached, imap)",
                "restoredKeys.record(landing)",
                "sources.renumber(landing.rows)",
                "writeTicks(currentTicks().renumbered(landing.renumbered))",
            ),
            bodyLines("private suspend fun renumberRestored("),
        )
    }

    @Test fun `the search bar's snapshot renumbers its stash, then redraws through the executed replacement`() {
        assertEquals(
            "the two decisions `SelectionSourcesRenumberTest` runs, the stash first: a " +
                "replacement written here by hand is the place a key gets drawn twice, and a " +
                "stash left as it was puts a row back under the id its message has left",
            listOf(
                "searchRemoved.renumber(landed)",
                "renumberedRows(rows, landed)?.let { searchState.value = searchState.value.copy(results = it) }",
            ),
            codeLines().map { it.trim() }.filter { "renumberedRows(" in it || ".renumber(landed)" in it },
        )
    }

    @Test fun `a gesture handed a row acts through one hand-over, whole`() {
        assertEquals(
            "the whole body. The row to write is `RestoredKeys.rowToWrite`'s answer, executed " +
                "by `RestoredKeysTest`. The mask posed at the finger is on the key the row was " +
                "handed under and nothing else lifts it; an unwritable row goes back on screen " +
                "and is said to have failed, and nothing is sent for it",
            listOf(
                "val now = restoredKeys.rowToWrite(row) { repo.cachedEmailsByIds(it) }",
                "if (now == RowToWrite.AsHanded) return false",
                "swipeMask.update { it.lifted(listOf(row.emailKey())) }",
                "if (now is RowToWrite.Landed) {",
                "again(now.row)",
                "return true",
                "}",
                "restoreSearchResults(listOf(row.emailKey()))",
                "if (flewOff) _swipeRewind.value = _swipeRewind.value + row.emailKey()",
                "_message.value = getApplication<Application>().getString(R.string.status_action_failed)",
                "return true",
            ),
            bodyLines("private suspend fun handedOver("),
        )
    }

    @Test fun `the swipe hands over after its wait, before it reads or evicts anything`() {
        assertOrder(
            bodyLines("private fun swipeRemove("),
            "restoresInFlight.settled(listOf(email.emailKey()))",
            "if (handedOver(email, flewOff = true) { swipeRemove(it, label, pickedInto, op) }) return@launch",
            "val flight = repo.beginRemoval(credentials.id, email.id)",
            why = "past the hand-over every line of the swipe names the row it was handed: its " +
                "ticket, its Undo entry and its write would all go out under the id the message left",
        )
    }

    @Test fun `the open message's move hands over after its wait, before it writes`() {
        assertOrder(
            bodyLines("private fun moveToAccount("),
            "restoresInFlight.settled(listOf(key))",
            "if (handedOver(email, flewOff = false) { moveToAccount(it, targetAccountId, targetMailboxId) }) return@launch",
            "runCatching { repo.moveToAccount(source, email, target, targetMailboxId, FrozenNumbering.NothingFrozen) }",
            why = "the write takes the row it was handed, and it has no Undo",
        )
    }

    @Test fun `the record is forgotten where the snapshot is opened or left`() {
        assertOrder(
            bodyLines("private fun openSearch("),
            "searchRemoved.clear()",
            "restoredKeys.forget()",
            "searchState.value = if (active) SearchUi(active = true, scope = scope) else SearchUi()",
            why = "with the stash, and before the rows are replaced: a record only speaks for " +
                "rows of the snapshot it was made under",
        )
    }

    @Test fun `the snooze asks what was renumbered after its wait`() {
        assertOrder(
            bodyLines("private fun bulk("),
            "restoresInFlight.settled(keys)",
            "val resolved = resolveSelectionTargets(",
            "restored = restoredRows(keys),",
            why = "asked before the wait, a restore still out has reported nothing",
        )
    }

    @Test fun `the batched move asks what was renumbered after its wait`() {
        assertOrder(
            bodyLines("private fun bulkBatched("),
            "restoresInFlight.settled(targetKeys)",
            "val resolved = resolveSelectionTargets(",
            "restored = restoredRows(targetKeys),",
            "flyBulkGroups(",
            why = "asked before the wait, a restore still out has reported nothing; and the " +
                "flight takes the targets of that resolution",
        )
    }

    @Test fun `the selection delete asks what was renumbered after its wait`() {
        assertOrder(
            bodyLines("fun deleteSelected()"),
            "val ticked = awaitTickedNumbering(keys)",
            "restoresInFlight.settled(keys)",
            "val resolved = resolveSelectionTargets(",
            "restored = restoredRows(keys),",
            "val plan = planSelectionDelete(",
            why = "the stamps of the tick are awaited first and stay keyed as ticked; the plan " +
                "that decides what is destroyed takes the targets of that resolution",
        )
    }

    // -- reading the source ------------------------------------------------------------------------

    private fun recordLines(): List<String> =
        codeLines().map { it.trim() }.filter { Regex("""\b(restoredKeys|RestoredKeys)\b""").containsMatchIn(it) }

    /** Every line of [expected] is in [body] exactly once, and they come in that order. */
    private fun assertOrder(body: List<String>, vararg expected: String, why: String) {
        val at = expected.map { line -> body.indices.filter { body[it] == line } }
        assertEquals(
            "$why. Each of these lines must be there once, whole:\n" + expected.joinToString("\n") +
                "\nBody was:\n" + body.joinToString("\n"),
            expected.map { 1 },
            at.map { it.size },
        )
        val order = at.map { it.single() }
        assertEquals(
            "$why. Expected this order:\n" + expected.joinToString("\n") +
                "\nBody was:\n" + body.joinToString("\n"),
            order.sorted(),
            order,
        )
    }

    /** The body lines of the declaration starting at [header], trimmed, comments off, braces balanced. */
    private fun bodyLines(header: String): List<String> {
        val text = codeLines().joinToString("\n")
        val start = text.indexOf(header)
        check(start >= 0) { "InboxViewModel.kt declares no '$header', did it get renamed?" }
        val open = text.indexOf('{', start + header.length - 1)
        var depth = 1
        var i = open + 1
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return text.substring(open + 1, i - 1).lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun codeLines(): List<String> = INBOX_VIEW_MODEL.readLines().mapNotNull { line ->
        val start = line.trimStart()
        if (start.startsWith("//") || start.startsWith("*") || start.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }

    /** [line] up to its first `//` outside a double-quoted string; `\` escapes the next character. */
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

    private companion object {

        const val REPORT = ".let { renumberRestored(accountId, it, imap = credentials.protocol == MailProtocol.IMAP) }"

        const val INBOX_VIEW_MODEL_PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        /** Repo root, walked up from the module's working directory. */
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath}: this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
    }
}
