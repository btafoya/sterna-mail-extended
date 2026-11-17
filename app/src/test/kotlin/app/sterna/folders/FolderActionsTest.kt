package app.sterna.folders

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.jmap.model.Mailbox
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The create, rename and delete the folder screen and the drawer both go through, executed on a
 * stand-in host that records what reached it. Two accounts are signed in throughout, so an action
 * landing on the account it was not asked for shows in the record.
 */
class FolderActionsTest {

    private class RecordingHost(private val renamedTo: (String, String) -> String) : FolderActionsHost {
        val calls = mutableListOf<String>()
        private val accounts = mapOf(
            "current" to AccountCredentials(server = "s", username = "c", password = "p", id = "current", protocol = MailProtocol.IMAP),
            "route" to AccountCredentials(server = "s", username = "r", password = "p", id = "route", protocol = MailProtocol.IMAP),
        )
        var failWith: Throwable? = null

        override fun credentials(accountId: String) = accounts[accountId]
        override suspend fun createFolder(credentials: AccountCredentials, name: String, parentId: String?) {
            failWith?.let { throw it }
            calls += "create ${credentials.id} '$name' under $parentId"
        }
        override suspend fun renameFolder(credentials: AccountCredentials, mailboxId: String, newName: String): String {
            failWith?.let { throw it }
            calls += "rename ${credentials.id} $mailboxId to '$newName'"
            return renamedTo(mailboxId, newName)
        }
        override fun followBaseline(accountId: String, oldMailboxId: String, newMailboxId: String) {
            calls += "baseline $accountId $oldMailboxId -> $newMailboxId"
        }
        override fun setFolderCollapsed(accountId: String, mailboxId: String, collapsed: Boolean) {
            calls += "collapsed $accountId $mailboxId $collapsed"
        }
        override suspend fun hideLocally(accountId: String, mailboxIds: List<String>) {
            calls += "hide $accountId $mailboxIds"
        }
        override suspend fun relist(credentials: AccountCredentials) {
            calls += "relist ${credentials.id}"
        }
        override fun scheduleDelete(accountId: String, mailboxId: String, holdBackMs: Long) {
            calls += "schedule $accountId $mailboxId $holdBackMs"
        }
        override fun cancelDelete(accountId: String, mailboxId: String) {
            calls += "cancel $accountId $mailboxId"
        }
    }

    /** IMAP: the id is the path, so a rename hands back a new one. */
    private fun imapHost() = RecordingHost { old, name -> old.substringBeforeLast('/', "").let { if (it.isEmpty()) name else "$it/$name" } }

    /** JMAP: the id survives the rename. */
    private fun jmapHost() = RecordingHost { old, _ -> old }

    @Test fun `a folder is created on the account named, under its trimmed name, and a root folder unfolds nothing`() = runTest {
        val host = imapHost()
        FolderActions(host).create("route", "  Receipts  ", null)
        assertEquals(listOf("create route 'Receipts' under null"), host.calls)
    }

    @Test fun `a subfolder unfolds its parent on the same account, after the server took it`() = runTest {
        val host = imapHost()
        FolderActions(host).create("route", "2026", "Receipts")
        assertEquals(
            "a subfolder born under a folded parent is invisible in the drawer until the user " +
                "guesses to unfold it; and the unfold must land on the account the folder was made on",
            listOf("create route '2026' under Receipts", "collapsed route Receipts false"),
            host.calls,
        )
    }

    @Test fun `a refused create unfolds nothing and reaches the caller`() = runTest {
        val host = imapHost().apply { failWith = IllegalStateException("NO [ALREADYEXISTS]") }
        try {
            FolderActions(host).create("route", "Receipts", "Parent")
            fail("the server's refusal must reach the screen, which prints it")
        } catch (e: IllegalStateException) {
            assertEquals("NO [ALREADYEXISTS]", e.message)
        }
        assertEquals(emptyList<String>(), host.calls)
    }

    @Test fun `an IMAP rename moves the new-mail baseline to the new path, on the account named`() = runTest {
        val host = imapHost()
        FolderActions(host).rename("route", "Work/Old", "  New ")
        assertEquals(
            "an IMAP id is its path: without the baseline following it, the renamed folder's next " +
                "diff finds every message new and notifies all of them",
            listOf("rename route Work/Old to 'New'", "baseline route Work/Old -> Work/New"),
            host.calls,
        )
    }

    @Test fun `a JMAP rename keeps its id, and its baseline stays where it is`() = runTest {
        val host = jmapHost()
        FolderActions(host).rename("route", "m42", "New")
        assertEquals(listOf("rename route m42 to 'New'"), host.calls)
    }

    @Test fun `a refused rename moves no baseline`() = runTest {
        val host = imapHost().apply { failWith = IllegalStateException("NO") }
        runCatching { FolderActions(host).rename("route", "Work/Old", "New") }
        assertEquals(emptyList<String>(), host.calls)
    }

    @Test fun `a delete schedules the held-back work and hides the folder with every subfolder, on the account named`() = runTest {
        val host = imapHost()
        val done = FolderActions(host).delete("route", FolderDeletion("Work", "Work", listOf("Work/A", "Work/A/B")), 5_000L)
        assertTrue(done)
        assertEquals(
            listOf("schedule route Work 5000", "hide route [Work, Work/A, Work/A/B]"),
            host.calls,
        )
    }

    @Test fun `nothing is done for an account that has no credentials`() = runTest {
        val host = imapHost()
        val actions = FolderActions(host)
        actions.create("gone", "x", "p")
        actions.rename("gone", "a/b", "c")
        assertFalse(actions.delete("gone", FolderDeletion("a", "a", emptyList()), 5_000L))
        assertEquals(emptyList<String>(), host.calls)
    }

    @Test fun `an undo cancels the work and lists the account's folders again`() = runTest {
        val host = imapHost()
        FolderActions(host).undoDelete("route", "Work")
        assertEquals(listOf("cancel route Work", "relist route"), host.calls)
    }

    @Test fun `a name of blanks cannot be confirmed`() {
        assertFalse(folderNameMayBeSubmitted(""))
        assertFalse(folderNameMayBeSubmitted("   "))
        assertTrue(folderNameMayBeSubmitted(" a "))
    }

    @Test fun `a folder with subfolders asks about them, whatever the protocol nests them by`() {
        val jmap = listOf(
            Mailbox(id = "p", name = "Parent"),
            Mailbox(id = "c", name = "Child", parentId = "p"),
            Mailbox(id = "g", name = "Grandchild", parentId = "c"),
            Mailbox(id = "o", name = "Other"),
        )
        val deletion = folderDeletion(jmap.first(), jmap)
        assertEquals(listOf("c", "g"), deletion.subfolderIds)
        assertTrue("a delete that takes subfolders must say so before they go", deletion.asksAboutSubfolders)
        assertEquals(listOf("p", "c", "g"), deletion.hiddenIds)

        val imap = listOf(
            Mailbox(id = "Work", name = "Work"),
            Mailbox(id = "Work/A", name = "A"),
            Mailbox(id = "Work/A/B", name = "B"),
            Mailbox(id = "Workshop", name = "Workshop"),
            Mailbox(id = "Lists.dev", name = "dev"),
            Mailbox(id = "Lists", name = "Lists"),
        )
        assertEquals(listOf("Work/A", "Work/A/B"), folderDescendantIds("Work", imap))
        assertEquals(listOf("Lists.dev"), folderDescendantIds("Lists", imap))
    }

    @Test fun `a folder with no subfolder in its own account's list gets the plain confirmation`() {
        val namesake = Mailbox(id = "Work", name = "Work")
        val thisAccount = listOf(namesake, Mailbox(id = "Other", name = "Other"))
        val deletion = folderDeletion(namesake, thisAccount)
        assertFalse(deletion.asksAboutSubfolders)
        assertEquals(listOf("Work"), deletion.hiddenIds)
    }

    @Test fun `a parentId cycle the server answers ends the walk`() {
        val cycle = listOf(
            Mailbox(id = "a", name = "A", parentId = "b"),
            Mailbox(id = "b", name = "B", parentId = "a"),
        )
        assertEquals(listOf("b"), folderDescendantIds("a", cycle))
    }
}
