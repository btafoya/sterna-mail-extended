package app.sterna.ui.inbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source lint, not a behaviour test: it reads `InboxViewModel.kt` as text and proves nothing about
 * behaviour. What the wait does is executed by `RestoresInFlightTest`; what only this can see is
 * that Undo hands its restore over, and that every gesture writing a message's folder asks before
 * it reads the cache, in a view model that cannot be built on the JVM. Lines are compared whole,
 * so a call that is lengthened, negated or handed other keys reddens.
 *
 * Holes, by their number in CONTRIBUTING.md:
 *  1. `keys`, `targetKeys`, `emails` are names: that they hold the gesture's own messages is not
 *     readable here.
 *  3. Order is the order of lines in the function's text. A wait moved into a branch, or a cache
 *     read added above it under another name than the ones pinned below, is not seen.
 * A conversation's wait names its representative and the members it was handed, never members it
 * has yet to read: one of those being restored on its own is covered by no line pinned here.
 */
class RestoresInFlightWiringLintTest {

    @Test fun `one register, one writer, and the gestures that ask it, in file order`() {
        assertEquals(
            "exactly these lines name the register, whole and in file order: its declaration, the " +
                "held-back destroy, the open message's move to another account, the conversation " +
                "delete, the conversation swipe, the swipe, Undo handing its restore over, the " +
                "snooze, the batched move, the selection delete, the selection's move to another " +
                "account. A gesture missing here writes across a restore still out; a second " +
                "writer is a second opinion on which messages are being restored. Lines found:\n" +
                registerLines().joinToString("\n"),
            listOf(
                "private val restoresInFlight = RestoresInFlight()",
                HELD_BACK,
                MOVE_TO_ACCOUNT,
                DELETE_THREAD,
                THREAD_SWIPE,
                SWIPE,
                UNDO_HANDS_OVER,
                SELECTION,
                BATCHED,
                SELECTION,
                SELECTION,
            ),
            registerLines(),
        )
    }

    @Test fun `undo hands over the very job that restores, with the keys it put back on screen`() {
        val body = bodyLines("fun undo()")
        val launch = body.indexOf("viewModelScope.launch {")
        val send = body.indexOfFirst { "repo.restoreAll(" in it }
        val handed = body.indexOf(UNDO_HANDS_OVER)
        assertTrue(
            "the job held must be the launch that sends `restoreAll`, closed by the hand-over " +
                "itself: a job made beside it ends on its own clock, and the keys are the ones " +
                "`restoreSearchResults` drew at the tap. Found the launch at $launch, the send at " +
                "$send, the hand-over at $handed. Body was:\n" + body.joinToString("\n"),
            launch >= 0 && send > launch && handed > send,
        )
        assertEquals(
            "and the hand-over closes that launch, it is the last line of undo()",
            UNDO_HANDS_OVER,
            body.last(),
        )
        assertEquals(
            "one launch in undo(), so the job handed over cannot be another one",
            1,
            body.count { "viewModelScope.launch" in it },
        )
    }

    @Test fun `the selection delete waits after the numbering of the gesture and before it resolves`() {
        val body = bodyLines("fun deleteSelected()")
        assertOrder(
            body,
            "viewModelScope.launch {",
            "val ticked = awaitTickedNumbering(keys)",
            "val numbering = selectionNumbering(keys)",
            SELECTION,
            "val resolved = resolveSelectionTargets(",
            "cached = repo.cachedEmailsByIds(keys),",
            why = "the stamps and the numbering are the gesture's and are read before any wait; " +
                "the cache is what a restore still out has not refilled, so the wait is ahead of " +
                "the resolution, and the mask posed at the tap is ahead of the launch",
        )
        assertEquals(
            "the wait comes straight after the numbering frozen at the gesture",
            SELECTION,
            body[body.indexOf("val numbering = selectionNumbering(keys)") + 1],
        )
    }

    @Test fun `the batched move waits first thing, before it resolves and before the flight`() {
        val body = bodyLines("private fun bulkBatched(")
        assertEquals(
            "first statement of the launch: archive, move, spam and the delete's move leg all " +
                "resolve here. Body was:\n" + body.joinToString("\n"),
            BATCHED,
            body[body.indexOf("viewModelScope.launch {") + 1],
        )
        assertOrder(
            body,
            BATCHED,
            "val resolved = resolveSelectionTargets(",
            "cached = repo.cachedEmailsByIds(targetKeys),",
            "flyBulkGroups(",
            why = "ahead of the resolution, and outside the flight: the flight takes every ticket " +
                "before its first write and must not be made to wait inside that",
        )
    }

    @Test fun `the snooze waits first thing, before it resolves`() {
        val body = bodyLines("private fun bulk(")
        assertEquals(
            "first statement of the launch. Body was:\n" + body.joinToString("\n"),
            SELECTION,
            body[body.indexOf("viewModelScope.launch {") + 1],
        )
        assertOrder(
            body,
            SELECTION,
            "val resolved = resolveSelectionTargets(",
            "cached = repo.cachedEmailsByIds(keys),",
            why = "ahead of the resolution",
        )
    }

    @Test fun `the swipe masks at the finger, then waits, then reads the cache`() {
        val body = bodyLines("private fun swipeRemove(")
        assertOrder(
            body,
            "swipeMask.update { it.posed(email.emailKey()) }",
            "viewModelScope.launch {",
            "dropSearchResults(setOf(email.emailKey()))",
            SWIPE,
            "val source = email.mailboxId ?: repo.cachedEmail(credentials.id, email.id)?.mailboxId",
            "val flight = repo.beginRemoval(credentials.id, email.id)",
            why = "nothing drawn waits: the mask is posed before the launch and the search list " +
                "is cut before the wait. The source folder and the eviction are read from a cache " +
                "the restore has not refilled, so they come after",
        )
    }

    @Test fun `the conversation swipe waits first thing, before it reads its members`() {
        val body = bodyLines("private fun threadSwipeRemove(")
        assertEquals(
            "first statement of the launch. Body was:\n" + body.joinToString("\n"),
            THREAD_SWIPE,
            body[body.indexOf("viewModelScope.launch {") + 1],
        )
        assertOrder(
            body,
            "poseConversation(rep)",
            "viewModelScope.launch {",
            THREAD_SWIPE,
            "val all = members ?: threadMessages(rep)",
            "flyBulkGroups(",
            why = "the membership is read from the cache, and the flight must not wait inside itself",
        )
    }

    @Test fun `the conversation delete waits first thing, before it reads its members`() {
        val body = bodyLines("fun deleteThread(")
        assertEquals(
            "first statement of the launch: it reads the membership itself and hands it to the " +
                "swipe and to the held-back destroy, which are then too late to wait for it. Body " +
                "was:\n" + body.joinToString("\n"),
            DELETE_THREAD,
            body[body.indexOf("viewModelScope.launch {") + 1],
        )
        assertOrder(
            body,
            "poseConversation(rep)",
            "viewModelScope.launch {",
            DELETE_THREAD,
            "val members = threadMessages(rep)",
            why = "the membership is read from the cache",
        )
    }

    @Test fun `the held-back destroy waits before it freezes anything`() {
        val body = bodyLines("private suspend fun heldBackDestroy(")
        assertEquals(
            "first statement: the numbering it freezes and the eviction it makes both read the " +
                "cached row. Body was:\n" + body.joinToString("\n"),
            HELD_BACK,
            body.first(),
        )
        assertOrder(
            body,
            HELD_BACK,
            "val numbering = recordedNumbering(targets)",
            "flushPendingDestroy()",
            "repo.evictAll(accountId, ids)",
            why = "ahead of the numbering read, of the flush of an earlier destroy, and of the eviction",
        )
    }

    @Test fun `the open message's move to another account cuts the search list, then waits, then writes`() {
        val body = bodyLines("private fun moveToAccount(")
        assertOrder(
            body,
            "viewModelScope.launch {",
            "val key = email.emailKey()",
            "dropSearchResults(setOf(key))",
            MOVE_TO_ACCOUNT,
            "runCatching { repo.moveToAccount(source, email, target, targetMailboxId, FrozenNumbering.NothingFrozen) }",
            why = "nothing drawn waits, the search list is cut before the wait; the write takes " +
                "the row it was handed and has no Undo, so crossing a restore still out leaves a " +
                "copy on the other account and the original wherever the last write put it",
        )
    }

    @Test fun `the selection's move to another account waits before it reads the cache`() {
        val body = bodyLines("private suspend fun moveSelectedToAccount(")
        assertOrder(
            body,
            "val target = store.credentials(targetAccountId)",
            "clearSelection()",
            SELECTION,
            "val emails = repo.cachedEmailsByIds(movable).associateBy { it.emailKey() }",
            "val now = recordedNumbering(crossAccountTargets)",
            "for (key in leaving) {",
            why = "a row a restore has not put back in the cache gets no verdict but `no cached " +
                "row`; the refusal of a vanished target stays ahead of the selection being " +
                "cleared, and the wait is behind both",
        )
        assertEquals(
            "the wait comes straight before the cache read",
            SELECTION,
            body[body.indexOf("val emails = repo.cachedEmailsByIds(movable).associateBy { it.emailKey() }") - 1],
        )
    }

    // -- reading the source ------------------------------------------------------------------------

    private fun registerLines(): List<String> =
        codeLines().map { it.trim() }.filter { Regex("""\b(restoresInFlight|RestoresInFlight)\b""").containsMatchIn(it) }

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

    companion object {

        private const val SELECTION = "restoresInFlight.settled(keys)"
        private const val BATCHED = "restoresInFlight.settled(targetKeys)"
        private const val SWIPE = "restoresInFlight.settled(listOf(email.emailKey()))"
        private const val THREAD_SWIPE =
            "restoresInFlight.settled(listOf(rep.emailKey()) + members.orEmpty().map { it.emailKey() })"
        private const val DELETE_THREAD = "restoresInFlight.settled(listOf(rep.emailKey()))"
        private const val MOVE_TO_ACCOUNT = "restoresInFlight.settled(listOf(key))"
        private const val HELD_BACK = "restoresInFlight.settled(emails.map { it.emailKey() })"
        private const val UNDO_HANDS_OVER =
            "}.also { restore -> restoresInFlight.hold(action.entries.map { EmailKey(it.accountId, it.emailId) }, restore) }"

        private const val INBOX_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        /** Repo root, walked up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath}: this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
    }
}
