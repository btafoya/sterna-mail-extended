package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/** Source lint read as text, because `MailRepository` wants Room and a network stack: it pins which
 *  lines resolve the bin, never that the caller weighing them decides right. */
class CachedTrashMailboxIdWiringTest {

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    /** Every code line of [text] (comments and blanks dropped, whitespace normalised) so prose
     *  can neither satisfy a rule nor break one, and the assertions compare whole lines. */
    private fun codeLines(text: String): List<String> =
        text.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { it.isNotEmpty() }

    private fun codeLinesNaming(text: String, needle: String): List<String> =
        codeLines(text).filter { needle in it }

    @Test fun `the whole body is these lines, and nothing else resolves the bin`() {
        val body = bodyOf("cachedTrashMailboxId")
        assertEquals(
            "EVERY line, in order: the bin is resolved the way delete() finds it — the role " +
                "first, then the name — but off the FOLDER TABLE, scoped to this account, and " +
                "nothing else stands between. A line that widens the lookup past the role and " +
                "the server names is a delete aimed at a folder the user reads. Body was:\n$body",
            listOf(
                "{",
                """val cached = mailboxDao.idForRole(credentials.id, "trash")""",
                "if (cached != null) return cached",
                "if (credentials.protocol == MailProtocol.IMAP) return null",
                "return mailboxDao.idForAnyName(credentials.id, TRASH_FOLDER_NAMES)",
                "}",
            ),
            codeLines(body),
        )
    }

    @Test fun `the fallback by name stops at IMAP, and stops before it is reached`() {
        val body = bodyOf("cachedTrashMailboxId")
        assertEquals(
            "on IMAP the cache writes `~trash` on the folder that LOST the role election and " +
                "keeps its name, which is usually \"Trash\": electing that loser by name would " +
                "call a folder the user reads the bin, and a swipe inside it would then destroy " +
                "where imapRoleFolder only moved. Whole line, and it must return null, not a folder",
            listOf("if (credentials.protocol == MailProtocol.IMAP) return null"),
            codeLinesNaming(body, "MailProtocol.IMAP"),
        )
        assertEquals(
            "and the guard has to sit ABOVE the name lookup: below it, the loser is already " +
                "elected by the time the protocol is looked at",
            listOf(
                "if (credentials.protocol == MailProtocol.IMAP) return null",
                "return mailboxDao.idForAnyName(credentials.id, TRASH_FOLDER_NAMES)",
            ),
            codeLines(body).filter { "MailProtocol.IMAP" in it || "idForAnyName(" in it },
        )
    }

    @Test fun `answering costs no socket, which is the whole point of this one`() {
        val body = bodyOf("cachedTrashMailboxId")
        listOf("connect(", "imapRoleFolder(", "roleMailboxId(", "client.").forEach { reach ->
            assertEquals(
                "cachedTrashMailboxId must not name $reach — deleteWouldDestroy already asks the " +
                    "server, and this one exists so a finger on a row waits on no IMAP LIST and " +
                    "no JMAP session. Body was:\n$body",
                emptyList<String>(),
                codeLinesNaming(body, reach),
            )
        }
    }
}
