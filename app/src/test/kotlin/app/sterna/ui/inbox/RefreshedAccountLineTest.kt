package app.sterna.ui.inbox

import app.sterna.core.data.account.StoredAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * The account line of the top bar after a folder refresh (#302): the decision executed, and its
 * plug into `InboxViewModel.refreshFolder` read as source, since that `AndroidViewModel` cannot be
 * built in this module. Does not cover: what `MailRepository.refresh` names the account.
 */
class RefreshedAccountLineTest {

    private fun account(name: String) = StoredAccount(
        id = "acct-alpha", server = "imap.example.test", username = "alex@example.test", accountName = name,
    )

    // -- the decision --------------------------------------------------------------------------------

    @Test fun `a named account keeps the name it was given, not the login the server reports`() {
        assertEquals("Work", refreshedAccountLine(account("Work"), "alex@example.test"))
    }

    @Test fun `an unnamed account shows what the drawer shows for it`() {
        assertEquals("alex@example.test", refreshedAccountLine(account(""), "Stalwart Server"))
    }

    @Test fun `an account no longer stored falls back on the name from the refresh`() {
        assertEquals("alex@example.test", refreshedAccountLine(null, "alex@example.test"))
    }

    // -- the plug ------------------------------------------------------------------------------------

    @Test fun `refreshFolder writes the line of the account it refreshed, read by its id`() {
        val body = refreshFolderBody()
        val writes = callArgs(body, "meta.value = Meta")
        assertEquals(
            "refreshFolder must write the header once, its account line from the stored account it " +
                "refreshed and not from the data layer's name alone (#302):\n$body",
            listOf(
                listOf(
                    "refreshedAccountLine(store.account(credentials.id), updated.accountName)",
                    "updated.mailboxName", "updated.unreadCount",
                ),
            ),
            writes,
        )
        assertFalse(
            "`store.accountLabel()` is the CURRENT account, which the network wait may have switched",
            "store.accountLabel(" in body,
        )
    }

    // -- reading the source --------------------------------------------------------------------------

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

    /** The body of `refreshFolder`, `//` comment lines dropped, whitespace collapsed. */
    private fun refreshFolderBody(): String {
        val source = INBOX_VM.readText()
        val declaration = Regex("""\bfun\s+refreshFolder\s*\(""").find(source)
            ?: error("InboxViewModel has no function named 'refreshFolder' — renamed?")
        var i = source.indexOf('{', declaration.range.last)
        val open = i
        var depth = 0
        do {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        } while (depth > 0)
        return source.substring(open + 1, i - 1).lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") }
            .joinToString(" ").replace(Regex("""\s+"""), " ").trim()
    }

    private companion object {
        const val INBOX_VM_PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        val INBOX_VM: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, INBOX_VM_PATH) }
                .firstOrNull { it.isFile }
                ?: error("cannot locate $INBOX_VM_PATH from ${File("").absolutePath}")
        }
    }
}
