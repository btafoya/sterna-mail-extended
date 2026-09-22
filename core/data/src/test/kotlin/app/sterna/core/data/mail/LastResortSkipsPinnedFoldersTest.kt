package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The last resorts of `archive` and `trash`, RUN against the account's own choices.
 *
 * Designate the server's `Archive` as the bin and the archive role is left with no holder: an
 * archive falling back on the literal "Archive" would file the message into the designated bin,
 * where "Empty trash" destroys it. The same holds for a JMAP server whose only `trash` mailbox the
 * user made her archive.
 */
class LastResortSkipsPinnedFoldersTest {

    @Test fun `archiving does not fall back on the folder designated as the bin`() {
        assertNull(lastResortImapFolder("archive", "Archive", mapOf("trash" to "Archive")))
    }

    @Test fun `deleting does not fall back on the folder designated as the archive`() {
        assertNull(lastResortImapFolder("trash", "Trash", mapOf("archive" to "Trash")))
    }

    /** IMAP names other than INBOX are case-sensitive in RFC 3501, but a case-insensitive server
     *  would CREATE nothing and MOVE into the pinned folder, so a case variant is refused too. */
    @Test fun `a case variant of a pinned folder is refused as well`() {
        assertNull(lastResortImapFolder("archive", "Archive", mapOf("trash" to "ARCHIVE")))
    }

    @Test fun `a free literal is still the last resort`() {
        assertEquals("Archive", lastResortImapFolder("archive", "Archive", mapOf("trash" to "Bin")))
        assertEquals("Trash", lastResortImapFolder("trash", "Trash", emptyMap()))
    }

    /** The folder pinned to the role being resolved is the choice honoured, not a collision; a key
     *  outside `SPECIAL_FOLDER_ROLES` routes nothing and blocks nothing. */
    @Test fun `a pin to the same role or to no special role does not block the literal`() {
        assertEquals("Trash", lastResortImapFolder("trash", "Trash", mapOf("trash" to "Trash")))
        assertEquals("Trash", lastResortImapFolder("trash", "Trash", mapOf("not-a-role" to "Trash")))
    }

    private val jmapListing = listOf(
        Mailbox(id = "mb-in", name = "INBOX", role = "inbox"),
        Mailbox(id = "mb-bin", name = "Corbeille", role = "trash"),
        Mailbox(id = "mb-arch", name = "Archives", role = "archive"),
    )

    @Test fun `the only server bin, designated as the archive, is not a bin`() {
        assertNull(serverRoleHolderNotPinnedElsewhere(jmapListing, "trash", mapOf("archive" to "mb-bin")))
    }

    @Test fun `the only server archive, designated as the bin, is not an archive`() {
        assertNull(serverRoleHolderNotPinnedElsewhere(jmapListing, "archive", mapOf("trash" to "mb-arch")))
    }

    @Test fun `a second holder of the server role answers when the first is pinned elsewhere`() {
        val twoBins = jmapListing + Mailbox(id = "mb-bin2", name = "Deleted", role = "trash")
        assertEquals("mb-bin2", serverRoleHolderNotPinnedElsewhere(twoBins, "trash", mapOf("archive" to "mb-bin")))
    }

    @Test fun `with nothing pinned the server's holder answers`() {
        assertEquals("mb-bin", serverRoleHolderNotPinnedElsewhere(jmapListing, "trash", emptyMap()))
        assertEquals("mb-arch", serverRoleHolderNotPinnedElsewhere(jmapListing, "archive", emptyMap()))
    }

    /** A delete never lands in a folder the server calls junk or all-mail, pinned or not. */
    @Test fun `the server role is matched exactly, never junk or all for a bin`() {
        val noBin = listOf(
            Mailbox(id = "mb-junk", name = "Spam", role = "junk"),
            Mailbox(id = "mb-all", name = "All Mail", role = "all"),
        )
        assertNull(serverRoleHolderNotPinnedElsewhere(noBin, "trash", emptyMap()))
    }
}
