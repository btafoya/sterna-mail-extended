package app.sterna.ui.inbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, not a behaviour test: it reads the source as text and proves nothing about
 * behaviour. What the mask decides is executed by `SwipeMaskTest`; what only this can see is that
 * `InboxViewModel`, which cannot be built on the JVM, asks those transitions, with those
 * arguments, at those places. Lines are compared whole, so a mutation that lengthens one reddens.
 */
class SwipeMaskWiringTest {

    @Test fun `the view model writes the mask at exactly these lines, in this order`() {
        assertEquals(
            "every line of InboxViewModel naming swipeMask, in file order. The declaration comes " +
                "before pagedListRows because that property's initializer reads it: declared after " +
                "it, the field is still null there, maskedGenerations' non-null parameter check " +
                "throws a NullPointerException, and the view model cannot be built. Eighteen " +
                "writers and no nineteenth. The conversation's are five: the close of a delete " +
                "where nothing flies at all, the pose of both halves of what its row may be drawn " +
                "under (`SwipeMaskTest` runs why a veil alone is not enough), the lift of a " +
                "representative's key no flight will carry, the three its flight is handed, and " +
                "the settle that takes the veil down. Then the swipe's pose at the finger, its " +
                "eviction's return (whose `flight != null` is what lifts a mask nothing will ever " +
                "lift otherwise), its put-back; Undo; the batch's unmasking of what it will not " +
                "fly and the three the bulk flight is handed (whose order `BulkFlightTest` runs); " +
                "and the delete's three: the pose at the tap, ahead of the plan's LIST round " +
                "trips, the destroyed rows handed to the generation that drops them, and the " +
                "put-back of everything this gesture never acted on. Lines found:\n" +
                maskLines().joinToString("\n"),
            listOf(
                "private val swipeMask = MutableStateFlow(SwipeMask())",
                "combine(maskedGenerations(pagedEmails, swipeMask), localDraftRows) { masked, local ->",
                "swipeMask.update { it.threadSettled(threadKeyOf(rep), evicted = destroy.isNotEmpty()).removalBegun(rep.emailKey(), evicted = destroy.isNotEmpty()) }",
                "swipeMask.update { it.posedThread(threadKeyOf(rep)).posed(rep.emailKey()) }",
                "if (flying.none { it.emailKey() == rep.emailKey() }) swipeMask.update { it.lifted(listOf(rep.emailKey())) }",
                "pose = { posed -> swipeMask.update { it.posed(posed) } },",
                "begun = { key, evicted -> swipeMask.update { it.removalBegun(key, evicted = evicted) } },",
                "lift = { back -> swipeMask.update { it.lifted(back) } },",
                "swipeMask.update { it.threadSettled(conversation, evicted = flown.any { (_, verdict) -> verdict.succeeded.isNotEmpty() }) }",
                "swipeMask.update { it.posed(email.emailKey()) }",
                "swipeMask.update { it.removalBegun(email.emailKey(), evicted = flight != null) }",
                "if (outcome?.putsRowBack ?: true) swipeMask.update { it.lifted(listOf(email.emailKey())) }",
                "swipeMask.update { mask -> mask.lifted(action.entries.map { EmailKey(it.accountId, it.emailId) }) }",
                "swipeMask.update { it.lifted(targetKeys - flying.mapTo(mutableSetOf()) { row -> row.email.emailKey() }) }",
                "pose = { posed -> swipeMask.update { it.posed(posed) } },",
                "begun = { key, evicted -> swipeMask.update { it.removalBegun(key, evicted = evicted) } },",
                "lift = { back -> swipeMask.update { it.lifted(back) } },",
                "swipeMask.update { it.posed(keys) }",
                "split.kept.forEach { e -> swipeMask.update { it.removalBegun(e.emailKey(), evicted = true) } }",
                "swipeMask.update { it.lifted(stillPosed) }",
            ),
            maskLines(),
        )
    }

    @Test fun `the list is the masked generation, filtered before the local drafts go on top`() {
        val lines = codeLines(INBOX_VIEW_MODEL).map { it.trim() }
        val at = lines.indexOf("val pagedListRows: Flow<PagingData<InboxRow>> =")
        check(at >= 0) { "InboxViewModel declares no pagedListRows of that type any more" }
        assertEquals(
            "the generation handed to withoutSwipedRows must be the masked one with ITS hidden " +
                "keys AND its veiled conversations: a hidden set taken from anywhere else is not " +
                "the one the generation lifted, and a generation filtered on the keys alone draws " +
                "the conversation row of a thread that left whole, every member being masked by a " +
                "key that cannot hide it.",
            listOf(
                "combine(maskedGenerations(pagedEmails, swipeMask), localDraftRows) { masked, local ->",
                "withLocalDrafts(withoutSwipedRows(masked.generation, masked.hidden, masked.hiddenThreads), local)",
                "}",
            ),
            lines.subList(at + 1, minOf(at + 4, lines.size)),
        )
        assertEquals(
            "and the filter must ask the executed predicate, on every row: `paged` alone is the " +
                "mask posed and never applied, the blank band back with every other test green.",
            listOf(
                "internal fun withoutSwipedRows(",
                "paged: PagingData<InboxRow>,",
                "hidden: Set<EmailKey>,",
                "hiddenThreads: Set<ThreadKey>,",
                "): PagingData<InboxRow> =",
                "paged.filter { row -> !hidesSwipedRow(row, hidden, hiddenThreads) }",
            ),
            codeLines(SWIPE_MASK).map { it.trim() }.let { mask ->
                val start = mask.indexOfFirst { it.startsWith("internal fun withoutSwipedRows(") }
                check(start >= 0) { "SwipeMask.kt declares no withoutSwipedRows" }
                mask.subList(start, minOf(start + 6, mask.size))
            },
        )
    }

    @Test fun `the mask is posed at the gesture, before the flight is launched`() {
        val body = bodyLines("private fun swipeRemove(")
        val pose = body.indexOf("swipeMask.update { it.posed(email.emailKey()) }")
        assertEquals(
            "the pose must be the line right after the credentials guard and right before the " +
                "launch: inside the coroutine it waits for a dispatch and at least one suspension, " +
                "and the list draws the row once more. Body was:\n" + body.joinToString("\n"),
            listOf("val credentials = credentialsFor(email) ?: return", "viewModelScope.launch {"),
            listOf(body.getOrNull(pose - 1), body.getOrNull(pose + 1)),
        )
    }

    @Test fun `the eviction's return is reported on the very next line`() {
        val body = bodyLines("private fun swipeRemove(")
        val evicted = body.indexOfFirst { it.startsWith("val flight = repo.beginRemoval(") }
        assertEquals(
            "the mask must learn the eviction returned before anything else suspends: the " +
                "generation it records is the one on screen at that moment. Body was:\n" +
                body.joinToString("\n"),
            "swipeMask.update { it.removalBegun(email.emailKey(), evicted = flight != null) }",
            body.getOrNull(evicted + 1),
        )
    }

    @Test fun `a row put back is unmasked after the repository has put it back`() {
        val body = bodyLines("private fun swipeRemove(")
        val finally = body.indexOf("} finally {")
        val back = body.indexOf("flight?.let { repo.endRemoval(it, restore = outcome?.putsRowBack ?: true) }")
        assertEquals(
            "the lift sits in the finally, on the line after endRemoval and under the same " +
                "`outcome?.putsRowBack ?: true`: a refused write and a cancelled one both put the " +
                "row back, and unmasking before the put-back shows the row, then the generation " +
                "that evicted it, then the row again. Body was:\n" + body.joinToString("\n"),
            "if (outcome?.putsRowBack ?: true) swipeMask.update { it.lifted(listOf(email.emailKey())) }",
            if (finally in 0 until back) body.getOrNull(back + 1) else "endRemoval is not inside the finally",
        )
    }

    @Test fun `undo unmasks its entries at the tap`() {
        val body = bodyLines("fun undo()")
        val lift = body.indexOf("swipeMask.update { mask -> mask.lifted(action.entries.map { EmailKey(it.accountId, it.emailId) }) }")
        val launch = body.indexOf("viewModelScope.launch {")
        assertEquals(
            "undo() must lift its entries' keys before it launches the restore: the rows it " +
                "asks back must not stay hidden behind a mask armed on a generation that never " +
                "changes. Body was:\n" + body.joinToString("\n"),
            true,
            lift >= 0 && launch > lift,
        )
    }

    // -- reading the source ------------------------------------------------------------------------

    private fun maskLines(): List<String> =
        codeLines(INBOX_VIEW_MODEL).map { it.trim() }.filter { Regex("""\bswipeMask\b""").containsMatchIn(it) }

    /** The body lines of the declaration starting at [header], trimmed, braces balanced. */
    private fun bodyLines(header: String): List<String> {
        val text = codeLines(INBOX_VIEW_MODEL).joinToString("\n")
        val start = text.indexOf(header)
        check(start >= 0) { "InboxViewModel.kt declares no '$header'" }
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

    /** [file]'s non-blank lines with every comment taken out, block comments tracked across lines,
     *  and a `//` only honoured outside a double-quoted string. */
    private fun codeLines(file: File): List<String> {
        val out = mutableListOf<String>()
        var inBlockComment = false
        for (raw in file.readLines()) {
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
        val INBOX_VIEW_MODEL: File by lazy { repoFile("app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt") }
        val SWIPE_MASK: File by lazy { repoFile("app/src/main/kotlin/app/sterna/ui/inbox/SwipeMask.kt") }

        private fun repoFile(path: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, path) }
                .firstOrNull { it.isFile }
                ?: error("cannot find $path from ${File("").absolutePath}")
    }
}
