package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The by-name fallbacks and the account's own choices, RUN together (#227).
 *
 * Pin `archive` onto the folder the server declares its bin and the `trash` role is left with no
 * holder at all, which is precisely when [trashFolderByName] is consulted, and it recognises that
 * same folder by its name. The delete would then file into the Archive the user designated, in
 * plain sight. The reverse holds for a bin pinned to `trash` and an archive resolved by name.
 */
class NameFallbackSkipsPinnedFoldersTest {

    private fun mailbox(id: String, name: String, role: String? = null) =
        Mailbox(id = id, name = name, role = role)

    private val listing = listOf(
        mailbox("mb-in", "INBOX", role = "inbox"),
        mailbox("mb-bin", "Trash"),
        mailbox("mb-arch", "Archive"),
    )

    @Test fun `the bin the user made her archive is not handed back as a bin`() {
        assertNull(
            trashFolderByName(listing.notPinnedToAnotherRole("trash", mapOf("archive" to "mb-bin"))),
        )
    }

    @Test fun `the archive the user made her bin is not handed back as an archive`() {
        assertNull(
            archiveFolderByName(listing.notPinnedToAnotherRole("archive", mapOf("trash" to "mb-arch"))),
        )
    }

    /** A folder pinned to the very role being resolved is not in the way of it: that is the
     *  choice being honoured, not a collision. */
    @Test fun `a folder pinned to the role being resolved stays in the running`() {
        assertEquals(
            "mb-bin",
            trashFolderByName(listing.notPinnedToAnotherRole("trash", mapOf("trash" to "mb-bin"))),
        )
    }

    /** With the pinned one out of the running, another folder the name table recognises answers,
     *  which is the whole difference from refusing the fallback outright. */
    @Test fun `the fallback moves on to the next folder the names recognise`() {
        val listed = listing + mailbox("mb-other", "Corbeille")
        assertEquals("mb-bin", trashFolderByName(listed))
        assertEquals(
            "mb-other",
            trashFolderByName(listed.notPinnedToAnotherRole("trash", mapOf("archive" to "mb-bin"))),
        )
    }

    @Test fun `with nothing pinned the listing goes through untouched`() {
        assertEquals(listing, listing.notPinnedToAnotherRole("trash", emptyMap()))
    }

    /** The ids a fallback for [pinnedToAnotherRole]'s role must skip, for the caller that has
     *  folder ids and no mailboxes (`MailRepository.cachedTrashMailboxId`, off the folder table). */
    @Test fun `the ids to skip are the other roles' choices, and only the five real roles`() {
        assertEquals(
            setOf("mb-arch", "mb-spam"),
            pinnedToAnotherRole(
                "trash",
                mapOf("archive" to "mb-arch", "junk" to "mb-spam", "trash" to "mb-bin", "all" to "mb-all"),
            ),
        )
    }
}
