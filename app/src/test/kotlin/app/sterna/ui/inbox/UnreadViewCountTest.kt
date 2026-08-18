package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The drawer badge of the "unread" view, RUN — not read as text.
 *
 * The badge must offer exactly the folders the list pages, so the count is summed over the scope
 * [unreadViewScopes] hands the pager — and this test builds that scope by CALLING it rather than by
 * retyping a list of folder ids: a test that spells the excluded roles again is a copy of the rule,
 * and stays green when the rule is inverted.
 *
 * ⛔ What is NOT claimed here, because it is not true: that the number equals the rows you land on.
 * In conversation view the badge sums per-folder counts while the list groups a thread over the
 * whole scope, so a thread unread in two folders of the scope is counted twice and drawn once. The
 * honest definition is the one below: the same aggregate the folder rows are badged with.
 */
class UnreadViewCountTest {

    private fun folder(id: String, role: String?, unread: Int) =
        Mailbox(id = id, name = id, role = role, unreadForList = unread)

    /** Inbox 3 + a user folder 4 = 7. Trash, Junk, Sent and Drafts carry the other 3. */
    private val folders = listOf(
        folder("inbox", "inbox", 3),
        folder("projets", null, 4),
        folder("trash", "trash", 1),
        folder("junk", "junk", 1),
        folder("sent", "sent", 1),
        folder("drafts", "drafts", 0),
    )

    @Test
    fun `the badge counts the folders the view pages, and nothing it excludes`() {
        assertEquals(
            "The entry must carry the unread of the folders the view actually pages. Counting the " +
                "whole account gives 10 — Trash, Junk and Sent thrown in — and the reader taps a " +
                "'10' to land on 7 rows.",
            7,
            unreadViewCount("a", unreadViewScopes("a", folders), folders),
        )
    }

    @Test
    fun `a role given to a folder moves the badge with the scope`() {
        // Same folders, but the user folder has since been elected Junk by the server. The scope
        // drops it; so must the badge. Nothing here restates which roles are dropped — the shipped
        // function is asked.
        val rescoped = folders.map { if (it.id == "projets") it.copy(role = "junk") else it }
        assertEquals(3, unreadViewCount("a", unreadViewScopes("a", rescoped), rescoped))
    }

    @Test
    fun `a sibling account's folder of the same id is not counted`() {
        // #121/#31: servers number mailboxes per account, so "a" names a folder in both accounts.
        // The scope is (account, folder) PAIRS and the account half is what has to be honoured —
        // dropping it counts the neighbour's mail into this account's badge.
        val mine = listOf(folder("a", "inbox", 5))
        assertEquals(0, unreadViewCount("me", listOf("sibling" to "a"), mine))
        assertEquals(5, unreadViewCount("me", listOf("me" to "a"), mine))
    }

    @Test
    fun `a scope naming a folder that is not in the list contributes nothing`() {
        // A folder deleted server-side between the scope being computed and the folder list
        // arriving. Zero, not a crash and not a stale count.
        assertEquals(3, unreadViewCount("a", listOf("a" to "inbox", "a" to "gone"), folders))
    }

    @Test
    fun `everything read is zero, and no account at all is zero`() {
        val allRead = folders.map { it.copy(unreadForList = 0) }
        assertEquals(0, unreadViewCount("a", unreadViewScopes("a", allRead), allRead))
        assertEquals(0, unreadViewCount(null, unreadViewScopes(null, folders), folders))
    }

    /**
     * ⚠ This proves ONE thing: which FIELD is read. `unreadForList` is what the folder rows below
     * the entry are badged with, so reading it is what keeps the drawer from contradicting itself;
     * `unreadEmails` is the server's stored count, and the two differ on any folder the app has
     * already marked read locally. It says nothing about the two being equal in general — for an
     * IMAP account `MailboxMapper` copies one into the other, and the pin still holds.
     */
    @Test
    fun `the badge reads the field the folder rows are badged with`() {
        val one = listOf(Mailbox(id = "inbox", name = "Inbox", role = "inbox", unreadEmails = 9, unreadForList = 2))
        assertEquals(2, unreadViewCount("a", unreadViewScopes("a", one), one))
    }
}
