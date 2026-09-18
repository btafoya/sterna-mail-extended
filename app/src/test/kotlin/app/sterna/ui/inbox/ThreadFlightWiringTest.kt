package app.sterna.ui.inbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source lint, not a behaviour test: it reads the source as text and proves nothing about
 * behaviour. What the flight does around the write is executed by `BulkFlightTest`, what the mask
 * decides by `SwipeMaskTest`, which bar a verdict leaves standing by `UndoFlightTest`; what only
 * this can see is that the collapsed conversation's swipe, in a view model that cannot be built on
 * the JVM, takes that flight rather than writing the server first and dropping its rows on the ack.
 * Lines are compared whole, so a mutation that lengthens one reddens.
 */
class ThreadFlightWiringTest {

    @Test fun `the row is posed by both halves of what it may be drawn under`() {
        assertEquals(
            "a collapsed conversation is drawn as one row and hidden two different ways, and the " +
                "gesture owes both. The veil is what hides a row of several messages, a member's " +
                "key being barred from hiding one (`hidesSwipedRow`); the representative's key is " +
                "what hides the row when the list counts the thread as one message, which happens " +
                "whenever its other messages sit outside the viewed folders — the swipe is offered " +
                "on `InboxRow.threadExpandable`, counted account-wide, and the veil is read " +
                "against `InboxRow.threadCount`, counted over those folders. Posing the veil " +
                "alone leaves such a row under the finger until the flight's own pose reaches it, " +
                "a Room read and a credentials load later.",
            listOf("swipeMask.update { it.posedThread(threadKeyOf(rep)).posed(rep.emailKey()) }"),
            bodyLines("private fun poseConversation("),
        )
    }

    @Test fun `every caller poses before it launches anything`() {
        val swipe = bodyLines("private fun threadSwipeRemove(")
        assertEquals(
            "the pose must come before the launch: inside the coroutine it waits for a dispatch " +
                "and at least one suspension, and the list draws the row again meanwhile. Body " +
                "was:\n" + swipe.joinToString("\n"),
            listOf("val conversation = threadKeyOf(rep)", "poseConversation(rep)", "viewModelScope.launch {"),
            swipe.take(3),
        )
        val delete = bodyLines("fun deleteThread(")
        assertEquals(
            "and deleteThread poses for itself, first thing: it reaches threadSwipeRemove two " +
                "suspensions later — the thread's membership, then each account's bin — so the " +
                "pose made down there is not the gesture. Body was:\n" + delete.joinToString("\n"),
            listOf("poseConversation(rep)", "viewModelScope.launch {"),
            delete.take(2),
        )
    }

    @Test fun `a delete where nothing flies takes its own pose down`() {
        val delete = bodyLines("fun deleteThread(")
        assertEquals(
            "when every member goes to the held-back destroy, or when nothing resolves at all, no " +
                "flight follows and nothing else would ever lift what the gesture posed: the " +
                "conversation would be hidden from every generation for the life of the process. " +
                "It closes on what really happened — the destroy evicted the rows, so the veil " +
                "waits for the generation that drops them; nothing evicted, and it goes now. Body " +
                "was:\n" + delete.joinToString("\n"),
            listOf(
                "swipeMask.update { it.threadSettled(threadKeyOf(rep), evicted = destroy.isNotEmpty())" +
                    ".removalBegun(rep.emailKey(), evicted = destroy.isNotEmpty()) }",
            ),
            delete.filter { "swipeMask" in it },
        )
    }

    @Test fun `a key posed at the gesture that the flight will not carry is lifted`() {
        val body = bodyLines("private fun threadSwipeRemove(")
        assertEquals(
            "the representative's key was posed with the veil, and a flight carries only the " +
                "members it grouped: a representative held back for a destroy, or whose account no " +
                "longer resolves, is masked with nothing coming that could ever unmask it. Body " +
                "was:\n" + body.joinToString("\n"),
            listOf("if (flying.none { it.emailKey() == rep.emailKey() }) swipeMask.update { it.lifted(listOf(rep.emailKey())) }"),
            body.filter { "it.lifted(listOf(rep.emailKey()))" in it },
        )
    }

    @Test fun `the members are grouped under the account whose credentials write them`() {
        val body = bodyLines("private fun threadSwipeRemove(")
        assertEquals(
            "a ticket is closed by account and id, and two accounts of one server share message " +
                "ids (#31): what may fly under an account's credentials is `bulkGroupUnder`'s " +
                "decision, asked once, with that account's own id. Defence in depth today, not a " +
                "live case: the membership is read with `cachedThreadEmails(accountId, …)`, one " +
                "account, so nothing is refused here. What it guards against is a member reaching " +
                "another account's credentials — `credentialsFor` falls back to the loaded " +
                "account for a key naming none — whose ticket would evict that account's message " +
                "of the same id and decount its folder. Body was:\n" + body.joinToString("\n"),
            listOf("val under = bulkGroupUnder(credentials, credentials.id, group.map { BulkRow(it.emailKey(), it.id) })"),
            body.filter { "bulkGroupUnder(" in it },
        )
    }

    @Test fun `the flight is the bulk one, handed exactly these`() {
        val body = bodyLines("private fun threadSwipeRemove(")
        val at = body.indexOf("flyBulkGroups(")
        assertTrue("threadSwipeRemove must hand its groups to flyBulkGroups. Body was:\n" + body.joinToString("\n"), at >= 0)
        assertEquals(
            "the flight's arguments are pinned whole and in order: the mask's three transitions, " +
                "the batch ticket at both ends, and a write that is the caller's per-message mover " +
                "looped into one verdict (`writeOneByOne`). A ticket taken per message would stall " +
                "the gesture on one disk commit each; a pose made inside the write loop would " +
                "leave the second account's rows drawn for the whole of the first's round trip. " +
                "Body was:\n" + body.joinToString("\n"),
            listOf(
                "groups = groups,",
                "pose = { posed -> swipeMask.update { it.posed(posed) } },",
                "begun = { key, evicted -> swipeMask.update { it.removalBegun(key, evicted = evicted) } },",
                "lift = { back -> swipeMask.update { it.lifted(back) } },",
                "begin = { accountId, ids -> repo.beginRemovalAll(accountId, ids) },",
                "write = { credentials, ids -> writeOneByOne(ids, refusal = { refused = true }) { id -> op(credentials, id) } },",
                "end = { ticket, restore -> repo.endRemovalAll(ticket, restore) },",
            ),
            body.subList(at + 1, minOf(at + 8, body.size)),
        )
    }

    @Test fun `the veil comes down even when the scope dies mid-write`() {
        val body = bodyLines("private fun threadSwipeRemove(")
        val finally = body.indexOf("} finally {")
        val uncancellable = body.indexOf("withContext(NonCancellable) {")
        val settle = body.indexOfFirst { it.startsWith("swipeMask.update { it.threadSettled(conversation") }
        assertTrue(
            "the settle must sit in a `finally`, under withContext(NonCancellable): a system back " +
                "gesture cancels this scope while the write is out, and a veil nobody takes down " +
                "hides that conversation from every generation for the life of the process. Body " +
                "was:\n" + body.joinToString("\n"),
            finally >= 0 && uncancellable > finally && settle > uncancellable,
        )
        assertEquals(
            "and the veil is armed on the flight's own verdict: a conversation nothing left goes " +
                "back on the list now, since no generation is coming that could lift it, and one " +
                "a write really emptied waits for the generation paged after the eviction. A " +
                "constant here is the whole gesture undone in one word — `true` hides a refused " +
                "conversation until the next sync, `false` blanks the band this exists to fill.",
            listOf("swipeMask.update { it.threadSettled(conversation, evicted = flown.any { (_, verdict) -> verdict.succeeded.isNotEmpty() }) }"),
            body.filter { "threadSettled(conversation" in it },
        )
    }

    @Test fun `the bar is offered at the gesture and re-posted when the verdict narrows it`() {
        val body = bodyLines("private fun threadSwipeRemove(")
        val offer = body.indexOf("val posted = if (entries.isEmpty()) null else undoOffer(entries, label, landed = false)")
        val flight = body.indexOf("flyBulkGroups(")
        val at = body.indexOf("val moved = movedKeys(flown)")
        assertTrue(
            "the offer goes up with the rows, unlanded, before the write leaves. Found offer at " +
                "$offer, flight at $flight, verdict at $at. Body was:\n" + body.joinToString("\n"),
            offer >= 0 && flight > offer && at > flight,
        )
        assertEquals(
            "the verdict's block is pinned whole and in order. `narrowedUndo` is asked BEFORE " +
                "recordLanding, which rewrites the very entries it reads, and what it answers is " +
                "re-posted with a deadline of its own: a deadline freezes the banner keys of the " +
                "bar it was posted for, so a bar narrowed in place keeps one naming rows the " +
                "server refused and takes down, six seconds later, the notification of a message " +
                "back in its folder. The identity check is the other gesture's: the bar may not be " +
                "ours any more. `UndoFlightTest` runs which bar a verdict leaves standing. Body " +
                "was:\n" + body.joinToString("\n"),
            listOf(
                "val moved = movedKeys(flown)",
                "val movedEntries = threadUndoEntries(moved.mapNotNull { byKey[it.first] }, moved.toMap())",
                "val standing = narrowedUndo(posted, movedEntries, label)",
                "recordLanding(posted, movedEntries)",
                "if (_undo.value === posted && standing !== posted) {",
                "retractUndo(posted)",
                "if (standing != null) {",
                "_undo.value = standing",
                "scheduleUndoneDeparture()",
                "}",
                "}",
            ),
            body.subList(at, minOf(at + 11, body.size)),
        )
    }

    @Test fun `the conversation row is told to rewind only when it comes back`() {
        val body = bodyLines("private fun threadSwipeRemove(")
        assertEquals(
            "the row flew off under the finger and the list re-emits it under the same key, " +
                "parked off screen at alpha 0: a representative that did not leave must be told " +
                "to come home. Read on what LANDED, never on a count of what flew: a " +
                "representative the server took is drawn nowhere any more, nobody would call " +
                "swipeRewound for its key, and the key would sit in the flow for the life of the " +
                "view model. Body was:\n" + body.joinToString("\n"),
            listOf("if (rep.emailKey() !in landed) _swipeRewind.value = _swipeRewind.value + rep.emailKey()"),
            body.filter { "_swipeRewind" in it },
        )
    }

    @Test fun `the ticket is the only repository call this path makes`() {
        val body = bodyLines("private fun threadSwipeRemove(")
        assertEquals(
            "the two ends of the batch ticket, and nothing else of the repository's. The ticket " +
                "is what decounts each member's source folder, once, when the rows leave; the " +
                "movers then credit the destination alone, off the row the ticket holds. An " +
                "eviction or a purge asked here as well would take that folder down a second " +
                "time, six for a conversation of three, until a full sync repairs it. What the " +
                "movers themselves do with the ticket is not readable here at all: it is executed " +
                "in core:data by `BulkActionCountsTheSourceOnceTest`. Calls found:\n" +
                body.joinToString("\n"),
            listOf("repo.beginRemovalAll(accountId, ids)", "repo.endRemovalAll(ticket, restore)"),
            body.flatMap { line -> Regex("""repo\.\w+\([^)]*\)""").findAll(line).map { it.value }.toList() },
        )
    }

    // -- reading the source ------------------------------------------------------------------------

    /** The body lines of the declaration starting at [header], trimmed, comments off, braces balanced. */
    private fun bodyLines(header: String): List<String> {
        val text = codeLines().joinToString("\n")
        val start = text.indexOf(header)
        check(start >= 0) { "InboxViewModel.kt declares no '$header' — did it get renamed?" }
        val open = text.indexOf('{', start + header.length)
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

    /** The view model's non-blank lines with every comment taken out, block comments tracked across
     *  lines, and a `//` only honoured outside a double-quoted string. */
    private fun codeLines(): List<String> {
        val out = mutableListOf<String>()
        var inBlockComment = false
        for (raw in INBOX_VIEW_MODEL.readLines()) {
            val code = StringBuilder()
            var inString = false
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                when {
                    inBlockComment -> if (c == '*' && raw.getOrNull(i + 1) == '/') {
                        inBlockComment = false
                        i++
                    }
                    inString -> {
                        code.append(c)
                        when {
                            c == '\\' -> raw.getOrNull(i + 1)?.let { code.append(it); i++ }
                            c == '"' -> inString = false
                        }
                    }
                    c == '"' -> {
                        code.append(c)
                        inString = true
                    }
                    c == '/' && raw.getOrNull(i + 1) == '*' -> {
                        inBlockComment = true
                        i++
                    }
                    c == '/' && raw.getOrNull(i + 1) == '/' -> i = raw.length
                    else -> code.append(c)
                }
                i++
            }
            if (code.isNotBlank()) out += code.toString().trimEnd()
        }
        return out
    }

    private companion object {
        private const val PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        val INBOX_VIEW_MODEL: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, PATH) }
                .firstOrNull { it.isFile }
                ?: error("cannot find $PATH from ${File("").absolutePath}")
        }
    }
}
