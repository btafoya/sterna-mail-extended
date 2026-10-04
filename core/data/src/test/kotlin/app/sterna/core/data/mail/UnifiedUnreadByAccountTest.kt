package app.sterna.core.data.mail

import app.sterna.core.data.db.AccountMailboxUnread
import app.sterna.core.data.filter.SourceText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The unified unread total, broken down per account, the number a large widget cell shows one
 */
class UnifiedUnreadByAccountTest {

    /** Neither branch is ever taken by accident: the fakes answer with numbers nothing else uses. */
    private fun imapOnly(vararg ids: String): (String) -> Boolean = { it in ids }

    private fun row(accountId: String, id: String, unread: Int) =
        AccountMailboxUnread(accountId = accountId, id = id, unreadEmails = unread)

    /**
     * Codeberg #121, executed. Two accounts on the same server are routinely handed the same
     */
    @Test fun `two accounts sharing a mailbox id each get their own number`() {
        val counts = unreadByAccountFrom(
            scopes = listOf("a" to "42", "b" to "42"),
            rows = listOf(row("a", "42", unread = 3), row("b", "42", unread = 5)),
            isImap = imapOnly(),
            storedUnread = { error("no IMAP account here, the stored counter must not be read") },
        )

        assertEquals(
            "matching on the mailbox id alone folds two same-server accounts onto one row: each " +
                "account must be looked up by BOTH halves of its scope (Codeberg #121).",
            mapOf("a" to 3, "b" to 5),
            counts,
        )
    }

    /**
     * The other half of the same guard, and it had no witness at all: every other case here hands
     */
    @Test fun `an account's line is its inbox, not whichever folder Room listed first`() {
        val counts = unreadByAccountFrom(
            scopes = listOf("a" to "inbox-1"),
            rows = listOf(row("a", "archive-9", unread = 7), row("a", "inbox-1", unread = 2)),
            isImap = imapOnly(),
            storedUnread = { error("no IMAP account here, the stored counter must not be read") },
        )

        assertEquals(
            "matching on the account id alone reads whatever folder row came first: the inbox " +
                "must be found by BOTH halves of its scope, folder id included.",
            mapOf("a" to 2),
            counts,
        )
    }

    /**
     * The two halves of the app side by side. A JMAP inbox contributes the unread count of its
     * `mailboxes` row, never the account's stored counter, written in the foreground only and
     * stale after a push. An IMAP inbox contributes that stored counter, never its folder row.
     */
    @Test fun `an IMAP account contributes its stored counter, a JMAP account its inbox row`() {
        val counts = unreadByAccountFrom(
            scopes = listOf("imap" to "INBOX", "jmap" to "inbox-1"),
            rows = listOf(
                row("jmap", "inbox-1", unread = 6),
                // The IMAP inbox has a folder row too, deliberately not its number.
                row("imap", "INBOX", unread = 1),
            ),
            isImap = imapOnly("imap"),
            storedUnread = { id -> if (id == "imap") 9 else error("only IMAP reads the store, not $id") },
        )

        assertEquals(
            "the IMAP account must carry its stored server counter (9), not the 1 of its folder " +
                "row; the JMAP account must carry the 6 of its inbox row.",
            mapOf("imap" to 9, "jmap" to 6),
            counts,
        )
    }

    /**
     * An account with no inbox is absent, never an entry worth 0. `UnifiedInbox.countedInboxes`
     */
    @Test fun `an account that is not in the scopes is absent, not a zero`() {
        val counts = unreadByAccountFrom(
            scopes = listOf("synced" to "ia"),
            rows = listOf(row("synced", "ia", unread = 4), row("never-synced", "ib", unread = 8)),
            isImap = imapOnly(),
            storedUnread = { error("an account outside the scopes must not be read at all") },
        )

        assertEquals(
            "a never-synced account must not appear at all: `{synced=4, never-synced=0}` would " +
                "put a 0 on the widget, which claims something the app does not know.",
            mapOf("synced" to 4),
            counts,
        )
        assertEquals("and the map holds exactly the scoped accounts", 1, counts.size)
    }

    /** A scoped JMAP inbox whose folder row is not written yet is 0, never the stored counter. */
    @Test fun `a scoped inbox with no folder row yet counts zero`() {
        val counts = unreadByAccountFrom(
            scopes = listOf("a" to "ia"),
            rows = emptyList(),
            isImap = imapOnly(),
            storedUnread = { error("a JMAP account must not fall back to the stored counter") },
        )

        assertEquals(mapOf("a" to 0), counts)
    }

    /**
     * Whole lines, never a `contains`: a substring rule is satisfied by every mutation that
     * LENGTHENS the line. This is the one claim the tests above cannot execute, `MailRepository`
     */
    @Test fun `the repository hands the decision the folder rows and the store`() {
        val source = SourceText.read(
            "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt",
        )
        assertEquals(
            "MailRepository.observeUnifiedInboxUnreadByAccount(scopes) must observe the folder " +
                "rows and hand unreadByAccountFrom those rows, the IMAP predicate and the stored " +
                "counter. A count of the cached messages in their place disagrees with the " +
                "count the inbox folder of the drawer holds.",
            listOf(
                "fun observeUnifiedInboxUnreadByAccount(scopes: List<Pair<String, String>>): Flow<Map<String, Int>> =",
                "mailboxDao.observeUnread().map { rows ->",
                "unreadByAccountFrom(",
                "scopes = scopes,",
                "rows = rows,",
                "isImap = ::isImapAccount,",
                "storedUnread = { accountId -> accountStore.account(accountId)?.unread ?: 0 },",
                ")",
                "}",
            ),
            SourceText.codeLines(
                SourceText.functionSource(
                    source,
                    "fun observeUnifiedInboxUnreadByAccount(scopes: List<Pair<String, String>>)",
                ),
            ),
        )
    }

    @Test fun `the statement behind the folder rows names both halves of the scope and the count`() {
        assertEquals(
            "SELECT accountId, id, unreadEmails FROM mailboxes",
            DaoQuerySource.daoQuery("MailboxDao", "observeUnread"),
        )
    }
}
