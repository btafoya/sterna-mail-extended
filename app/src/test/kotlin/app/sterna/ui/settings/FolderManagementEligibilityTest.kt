package app.sterna.ui.settings

import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which rows of the folder screen carry the folder's own menu (New subfolder, Rename, Delete),
 * executed on the rows the screen is handed.
 */
class FolderManagementEligibilityTest {

    private val tree = listOf(
        Mailbox(id = "INBOX", name = "INBOX", role = "inbox"),
        Mailbox(id = "Sent", name = "Sent", role = "sent"),
        Mailbox(id = "Drafts", name = "Drafts", role = "drafts"),
        Mailbox(id = "Trash", name = "Trash", role = "trash"),
        Mailbox(id = "Junk", name = "Junk", role = "junk"),
        Mailbox(id = "Archive", name = "Archive", role = "archive"),
        Mailbox(id = "All", name = "All", role = "all"),
        Mailbox(id = "Travail", name = "Travail"),
        Mailbox(id = "Travail/2026", name = "2026", parentId = "Travail"),
        Mailbox(id = "Cache", name = "Cache"),
    )

    @Test fun `only a folder no role claims carries the menu, a hidden one included`() {
        val rows = folderManagementRows(
            mailboxes = tree,
            showOnlySubscribed = false,
            hidden = setOf("Cache"),
            watched = emptySet(),
            inboxId = "INBOX",
        )
        assertEquals(
            "the menu offers Rename and Delete: on a folder a role claims it lets the user delete " +
                "her Inbox, her Sent or her Trash. A hidden user folder is still listed here, and " +
                "this screen is the only place left to manage it.",
            setOf("Travail", "Travail/2026", "Cache"),
            rows.filter { it.mayManage }.map { it.mailbox.id }.toSet(),
        )
    }

    @Test fun `the rule asks the role and nothing else`() {
        assertEquals(
            listOf(false, false, false, false, false, false, false, true, true, true),
            tree.map { folderMayBeManaged(it) },
        )
    }
}
