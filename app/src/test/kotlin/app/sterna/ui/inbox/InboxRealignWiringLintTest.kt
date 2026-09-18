package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint for the plug of [realignedInboxId] into `InboxViewModel.refreshFolder`, which this
 * module cannot run (an `AndroidViewModel` over a `MailRepository`, neither instantiable in the JVM);
 * the decision itself is executed by `RealignedInboxIdTest`. Arguments are compared whole, parsed
 * out of the call, so a call that grows a `null` or an extra fallback is caught, not waved through
 * by a substring. Does not cover: what `MailRepository.refresh` does with the id it is handed.
 */
class InboxRealignWiringLintTest {

    @Test fun `both refreshes name a folder, the second the one the decision returned`() {
        val body = refreshFolderBody()
        val realigned = realignedName(body)
        val refreshes = callArgs(body, "repo.refresh")
        assertEquals(
            "refreshFolder must ask the repository exactly twice, the requested folder then the " +
                "realigned inbox. Found ${refreshes.size} in:\n$body",
            2, refreshes.size,
        )
        assertEquals(
            "the first refresh is the folder asked for, with the account's window and cutoff",
            listOf("credentials", "mailboxId", "window.limit", "pruneBefore"), refreshes[0],
        )
        assertEquals(
            "the second refresh must name the folder the decision returned: a `null` there is a " +
                "refresh with no folder, which `imapWriteThrough` does not bound (#165)",
            listOf("credentials", realigned, "window.limit", "pruneBefore"), refreshes[1],
        )
        assertTrue(
            "the second refresh runs only when the decision returned a folder",
            "if ($realigned == null) first else repo.refresh(" in body,
        )
    }

    @Test fun `the decision reads the refreshed account's stored inbox and the cache's inbox role`() {
        val lines = refreshFolderLines()
        val stored = lines.firstNotNullOfOrNull { STORED.matchEntire(it)?.groupValues?.get(1) }
            ?: error(
                "refreshFolder no longer reads the stored inbox of the account it refreshes as a " +
                    "whole statement `val … = store.account(credentials.id)?.inboxId`:\n" +
                    lines.joinToString("\n"),
            )
        val firstRefresh = lines.indexOfFirst { "repo.refresh(" in it }
        assertTrue(
            "the stored inbox must be read before the network: after it, a switch may have moved it",
            lines.indexOfFirst { STORED.matches(it) } in 0 until firstRefresh,
        )
        val body = refreshFolderBody()
        assertEquals(
            "the decision is (folder asked for, the refreshed account's stored inbox, the cache's " +
                "`inbox` role for that account)",
            listOf(listOf("mailboxId", stored, "repo.cachedInboxMailboxId(credentials)")),
            callArgs(body, "realignedInboxId"),
        )
        assertFalse(
            "`store.inboxMailboxId()` reads the CURRENT account after the wait, not the refreshed one",
            "store.inboxMailboxId(" in body,
        )
        val realigned = realignedName(body)
        assertTrue(
            "a realigned refresh must be stored, and the stored inbox compared is the refreshed " +
                "account's:\n$body",
            "if (updated.mailboxId == $stored || $realigned != null || mailboxId == null) {" in body,
        )
    }

    @Test fun `the inbox is stored against the refreshed account`() {
        val body = refreshFolderBody()
        assertEquals(
            "refreshFolder must store the inbox against the account it refreshed, never the current one",
            listOf(
                listOf(
                    "credentials.id", "updated.mailboxId", "updated.mailboxName",
                    "updated.accountName", "updated.unreadCount",
                ),
            ),
            callArgs(body, "store.saveInboxMetaFor"),
        )
        assertFalse(
            "`saveInboxMeta(` writes the CURRENT account, which the wait may have switched",
            Regex("""\bsaveInboxMeta\s*\(""").containsMatchIn(body),
        )
    }

    @Test fun `the inbox role is read from the cache of the account asked for`() {
        val text = codeText(File(root, MAIL_REPOSITORY_PATH).readLines())
        val at = text.indexOf("suspend fun cachedInboxMailboxId(credentials: AccountCredentials): String? =")
        assertTrue("MailRepository declares no `cachedInboxMailboxId(credentials: AccountCredentials)`", at >= 0)
        assertEquals(
            "the accessor must return the cached role as is, no filter or fallback after it",
            "mailboxDao.idForRole(credentials.id, \"inbox\")",
            text.substring(text.indexOf("=", at) + 1).split(Regex("""\s(?:(?:suspend|private|internal|override) )*fun\s""")).first().trim(),
        )
    }

    // -- reading the source --------------------------------------------------------------------------

    private fun realignedName(body: String): String =
        Regex("""\bval (\w+) = realignedInboxId\(""").findAll(body).map { it.groupValues[1] }.toList()
            .singleOrNull()
            ?: error("refreshFolder must bind the decision once, `val … = realignedInboxId(`:\n$body")

    /** The top-level arguments of every call to [callee] in [code], each trimmed. */
    private fun callArgs(code: String, callee: String): List<List<String>> =
        Regex("""(?<![\w.])${Regex.escape(callee)}\s*\(""").findAll(code).map { match ->
            val args = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 1
            var i = match.range.last + 1
            while (depth > 0) {
                val c = code[i]
                when (c) {
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> depth--
                }
                if (depth == 0 || (depth == 1 && c == ',')) {
                    args += current.toString().trim()
                    current.clear()
                } else {
                    current.append(c)
                }
                i++
            }
            args.filter { it.isNotEmpty() }
        }.toList()

    private fun refreshFolderLines(): List<String> {
        val lines = codeLines(INBOX_VM.readLines())
        val start = lines.indexOfFirst { "fun refreshFolder(" in it }
        check(start >= 0) { "InboxViewModel.kt declares no `fun refreshFolder(` — renamed?" }
        var depth = 0
        var end = start
        do {
            depth += lines[end].count { it == '{' } - lines[end].count { it == '}' }
            end++
        } while (depth > 0)
        return lines.subList(start + 1, end - 1)
    }

    private fun refreshFolderBody(): String =
        refreshFolderLines().joinToString(" ").replace(Regex("""\s+"""), " ").trim()

    private fun codeText(raw: List<String>): String =
        codeLines(raw).joinToString(" ").replace(Regex("""\s+"""), " ").trim()

    /** [raw] with every comment taken out, each line trimmed, blank lines dropped. */
    private fun codeLines(raw: List<String>): List<String> {
        val out = mutableListOf<String>()
        var inBlockComment = false
        for (line in raw) {
            val code = StringBuilder()
            var inString = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    inBlockComment -> if (c == '*' && line.getOrNull(i + 1) == '/') {
                        inBlockComment = false
                        i++
                    }
                    inString -> {
                        code.append(c)
                        when {
                            c == '\\' -> line.getOrNull(i + 1)?.let { code.append(it); i++ }
                            c == '"' -> inString = false
                        }
                    }
                    c == '"' -> {
                        code.append(c)
                        inString = true
                    }
                    c == '/' && line.getOrNull(i + 1) == '*' -> {
                        inBlockComment = true
                        i++
                    }
                    c == '/' && line.getOrNull(i + 1) == '/' -> i = line.length
                    else -> code.append(c)
                }
                i++
            }
            code.toString().trim().takeIf { it.isNotEmpty() }?.let { out += it }
        }
        return out
    }

    private companion object {
        val STORED = Regex("""val (\w+) = store\.account\(credentials\.id\)\?\.inboxId""")

        const val INBOX_VM_PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"
        const val MAIL_REPOSITORY_PATH = "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VM_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "sources as text and needs a working directory inside the checkout",
                )
        }

        val INBOX_VM: File by lazy { File(root, INBOX_VM_PATH) }
    }
}
