package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Source lint read as text, because `InboxViewModel` wants an `Application`: it pins which lines
 *  ship on the swipe, never that the rule they call answers right. */
class SwipeDeleteProbeWiringTest {

    @Test fun `the swipe decides from the local caches, never from a socket`() {
        val body = body(INBOX_VIEW_MODEL, "delete")
        assertEquals(
            "the whole line, account argument included: the Trash is resolved for THIS message's " +
                "account, and in All inboxes a bare folder id belongs to another one (#31). " +
                "Body was:\n$body",
            listOf("val trash = runCatching { repo.cachedTrashMailboxId(credentials) }.getOrNull()"),
            codeLinesNaming(body, "cachedTrashMailboxId("),
        )
        assertEquals(
            "and the verdict is the same executable rule the network probe uses — the account's " +
                "Trash first, THIS message's folder second: a comparison inlined here is a second " +
                "rule no test runs, and inverting it must not read as the same line",
            listOf("if (deleteDestroysMessage(trash, email.mailboxId)) {"),
            codeLinesNaming(body, "deleteDestroysMessage("),
        )
        assertEquals(
            "no fallback anywhere in this gesture: `.getOrDefault(...)` on the Trash read makes an " +
                "unreadable folder cache decide, and the only value it could supply that changes " +
                "anything is a folder id it invented",
            emptyList<String>(),
            codeLinesNaming(body, "getOrDefault("),
        )
    }

    @Test fun `and each branch of it goes where it belongs`() {
        val body = body(INBOX_VIEW_MODEL, "delete")
        assertEquals(
            "the destroy arm is the HELD-BACK destroy (a real Undo window), the move arm is the " +
                "ordinary swipe with its own Undo, and they come in that order under the verdict " +
                "above. Whole lines, each arm carrying its own action: swapping the two bodies " +
                "must not read as the same list. Body was:\n$body",
            listOf(
                "heldBackDestroy(listOf(email), getApplication<Application>()" +
                    ".getString(R.string.status_message_deleted_forever))",
                "swipeRemove(email, getApplication<Application>()" +
                    ".getString(R.string.status_message_deleted)) { c, id -> repo.delete(c, id) }",
            ),
            codeLinesNaming(body, "getApplication<Application>()"),
        )
    }

    @Test fun `a conversation asks the same question, and asks the Trash once per account`() {
        val body = body(INBOX_VIEW_MODEL, "deleteThread")
        assertEquals(
            "the credentials are the MEMBER's, not the representative's: a thread in All inboxes " +
                "spans accounts, and judging a member of account B against account A's folder " +
                "table reads a folder id that account never had (#31). Body was:\n$body",
            listOf("credentialsFor(m)?.let { c ->"),
            codeLinesNaming(body, "credentialsFor("),
        )
        assertEquals(
            "per account, and against the member's OWN account: the same read as the single swipe, " +
                "with the same absence of a fallback",
            listOf("runCatching { repo.cachedTrashMailboxId(c) }.getOrNull()"),
            codeLinesNaming(body, "cachedTrashMailboxId("),
        )
        assertEquals(
            "memoised per account: without it a twenty-message thread asks the folder cache twenty " +
                "times, which is what the network probe already cost per member",
            listOf("val trash = trashByAccount.getOrPut(c.id) {"),
            codeLinesNaming(body, "getOrPut("),
        )
        assertEquals(
            "the same executable rule as the single swipe, handed THIS member's folder: a second " +
                "copy of the rule here could drift from the one DeleteDestroysMessageTest runs",
            listOf("deleteDestroysMessage(trash, m.mailboxId)"),
            codeLinesNaming(body, "deleteDestroysMessage("),
        )
        assertEquals(
            "destroy first, move second: naming the halves the other way round sends every " +
                "ordinary member of the conversation to the permanent destroy",
            listOf("val (destroy, move) = members.partition { m ->"),
            codeLinesNaming(body, "partition"),
        )
    }

    @Test fun `the rule it calls is the shipped one, not a namesake declared here`() {
        assertEquals(
            "the import and the two call sites, whole: a `private fun deleteDestroysMessage` in " +
                "this file shadows the import, leaves every line pinned above untouched, and is " +
                "free to answer the opposite",
            listOf(
                "import app.sterna.core.data.mail.deleteDestroysMessage",
                "if (deleteDestroysMessage(trash, email.mailboxId)) {",
                "deleteDestroysMessage(trash, m.mailboxId)",
            ),
            codeLines(INBOX_VIEW_MODEL).map { it.trim() }.filter { "deleteDestroysMessage" in it },
        )
    }

    @Test fun `nothing on the finger's path resolves a role over the network`() {
        val forbidden = listOf("deleteWouldDestroy(", "accountHasTrash(", "roleMailboxId(", "connect(")
        val offenders = listOf("delete", "deleteThread").flatMap { name ->
            val body = body(INBOX_VIEW_MODEL, name)
            forbidden.flatMap { needle -> codeLinesNaming(body, needle).map { "$name: $it" } }
        }
        assertEquals(
            "every one of $forbidden resolves the Trash over the SOCKET (an IMAP LIST, or a whole " +
                "JMAP session in All inboxes), and this is the path a finger waits on — one " +
                "probe per member on a thread. deleteSelected may still ask them, these two may not",
            emptyList<String>(),
            offenders,
        )
    }

    // Same readers as BulkSelectionWiringTest, deliberately duplicated rather than shared: a
    // helper these lints agree on is a helper a single edit can loosen for all of them at once.

    /** The code lines of [body] naming [needle], comments dropped, whitespace normalised. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    /** The lines of [file] that are code, with comments taken off. */
    private fun codeLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val code = line.trimStart()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
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

    /** The declaration of `fun`/`val` [name] in [file] and its body, as text, a name that is gone
     *  fails loudly, where a rule pinning nothing would stay green. */
    private fun body(file: File, name: String): String {
        val lines = codeLines(file)
        val declaration = Regex("""\b(fun|val|var)\s+$name\b""")
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

    companion object {
        private const val APP_SOURCES = "app/src/main/kotlin"
        private const val INBOX_VIEW_MODEL_PATH = "$APP_SOURCES/app/sterna/ui/inbox/InboxViewModel.kt"

        /** Repo root, walked up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
    }
}
