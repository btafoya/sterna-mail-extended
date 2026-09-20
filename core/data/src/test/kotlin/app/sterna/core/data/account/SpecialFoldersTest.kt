package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the store writes when a role is pinned to a folder or handed back to the automatic rule,
 *  executed: [withSpecialFolder] is the whole decision behind `AccountStore.setSpecialFolder`. */
class SpecialFoldersTest {

    private fun account(id: String, folders: Map<String, String> = emptyMap()) =
        StoredAccount(id = id, server = "imap.example.test", username = "$id@example.test", specialFolders = folders)

    private val accounts = listOf(account("acc-1"), account("acc-2"))

    private fun foldersOf(list: List<StoredAccount>, id: String) =
        list.first { it.id == id }.specialFolders

    @Test fun `pinning a folder stores it under its role`() {
        val out = withSpecialFolder(accounts, "acc-1", "trash", "Dossiers/Poubelle")
        assertEquals(mapOf("trash" to "Dossiers/Poubelle"), foldersOf(out, "acc-1"))
    }

    @Test fun `pinning a role leaves the other roles where they were`() {
        val out = withSpecialFolder(
            listOf(account("acc-1", mapOf("junk" to "Spam maison", "sent" to "Envoyes"))),
            "acc-1",
            "trash",
            "Poubelle",
        )
        assertEquals(
            mapOf("junk" to "Spam maison", "sent" to "Envoyes", "trash" to "Poubelle"),
            foldersOf(out, "acc-1"),
        )
    }

    @Test fun `pinning the same role again replaces the folder`() {
        val out = withSpecialFolder(
            listOf(account("acc-1", mapOf("trash" to "Poubelle"))),
            "acc-1",
            "trash",
            "Corbeille",
        )
        assertEquals(mapOf("trash" to "Corbeille"), foldersOf(out, "acc-1"))
    }

    /** A cache row carries one role, so designating a folder takes it away from the other role it
     *  was pinned to: a record naming one folder twice leaves the screen reading back a choice that
     *  decides nothing, `specialRole` settling the row on the first of [SPECIAL_FOLDER_ROLES]. */
    @Test fun `pinning a folder takes it off the other role it carried`() {
        val out = withSpecialFolder(
            listOf(account("acc-1", mapOf("archive" to "Mein Archiv", "sent" to "Envoyes"))),
            "acc-1",
            "trash",
            "Mein Archiv",
        )
        assertEquals(mapOf("sent" to "Envoyes", "trash" to "Mein Archiv"), foldersOf(out, "acc-1"))
    }

    /** Whichever way round the two roles come in [SPECIAL_FOLDER_ROLES]: the folder kept its bin
     *  key here, and letting that one stand is what `specialRole` would settle on `archive` while
     *  the bin ends up with no holder at all. */
    @Test fun `pinning a folder takes an earlier role off it just the same`() {
        val out = withSpecialFolder(
            listOf(account("acc-1", mapOf("trash" to "Mein Archiv", "sent" to "Envoyes"))),
            "acc-1",
            "archive",
            "Mein Archiv",
        )
        assertEquals(mapOf("sent" to "Envoyes", "archive" to "Mein Archiv"), foldersOf(out, "acc-1"))
    }

    /** Released roles are the other ones: designating the folder a role already names is not a way
     *  to clear it. */
    @Test fun `pinning the folder a role already names keeps it`() {
        val out = withSpecialFolder(listOf(account("acc-1", mapOf("trash" to "Bin"))), "acc-1", "trash", "Bin")
        assertEquals(mapOf("trash" to "Bin"), foldersOf(out, "acc-1"))
    }

    /** Handing a role back to the automatic rule evicts nothing: a record from before the eviction
     *  may still name one folder twice, and the role it keeps is not the one being cleared. */
    @Test fun `handing a role back leaves another role naming the same folder`() {
        val out = withSpecialFolder(
            listOf(account("acc-1", mapOf("archive" to "Mein Archiv", "trash" to "Mein Archiv"))),
            "acc-1",
            "trash",
            null,
        )
        assertEquals(mapOf("archive" to "Mein Archiv"), foldersOf(out, "acc-1"))
    }

    @Test fun `a null folder hands the role back to the automatic rule`() {
        val out = withSpecialFolder(
            listOf(account("acc-1", mapOf("trash" to "Poubelle", "junk" to "Spam maison"))),
            "acc-1",
            "trash",
            null,
        )
        assertEquals(mapOf("junk" to "Spam maison"), foldersOf(out, "acc-1"))
    }

    /** On IMAP a folder id is a path, and two accounts of one server carry the same paths
     *  (#92/#121): a write must reach the record it names and no other. */
    @Test fun `the choice lands on the named account alone`() {
        val out = withSpecialFolder(accounts, "acc-1", "trash", "Poubelle")
        assertEquals(mapOf("trash" to "Poubelle"), foldersOf(out, "acc-1"))
        assertEquals(emptyMap<String, String>(), foldersOf(out, "acc-2"))
    }

    @Test fun `an unknown id writes nothing anywhere`() {
        assertEquals(accounts, withSpecialFolder(accounts, "acc-9", "trash", "Poubelle"))
    }

    /** Clearing a role nobody pinned is not a way to empty the registry. */
    @Test fun `clearing an unpinned role leaves the pinned ones`() {
        val out = withSpecialFolder(listOf(account("acc-1", mapOf("junk" to "Spam maison"))), "acc-1", "trash", null)
        assertEquals(mapOf("junk" to "Spam maison"), foldersOf(out, "acc-1"))
    }

    // ---- a renamed folder (#227): on IMAP the pinned id IS a path

    @Test fun `a rename carries the choice onto the new path`() {
        val out = withRenamedSpecialFolder(
            listOf(account("acc-1", mapOf("trash" to "INBOX.Poubelle"))),
            "acc-1",
            "INBOX.Poubelle",
            "INBOX.Corbeille",
            ".",
        )
        assertEquals(mapOf("trash" to "INBOX.Corbeille"), foldersOf(out, "acc-1"))
    }

    /** A choice is stored as a map value and never as a key, so the role keys must come out
     *  untouched; a record written before pinning released may still name one folder under two
     *  roles, and both have to follow. */
    @Test fun `two roles pinned to one folder both follow it`() {
        val out = withRenamedSpecialFolder(
            listOf(account("acc-1", mapOf("trash" to "Bin", "junk" to "Bin", "sent" to "Envoyes"))),
            "acc-1",
            "Bin",
            "Poubelle",
            "/",
        )
        assertEquals(mapOf("trash" to "Poubelle", "junk" to "Poubelle", "sent" to "Envoyes"), foldersOf(out, "acc-1"))
    }

    /** A folder pinned UNDER the renamed one moves with its parent. */
    @Test fun `a choice under the renamed folder moves with it`() {
        val out = withRenamedSpecialFolder(
            listOf(account("acc-1", mapOf("junk" to "Travail/Spam"))),
            "acc-1",
            "Travail",
            "Boulot",
            "/",
        )
        assertEquals(mapOf("junk" to "Boulot/Spam"), foldersOf(out, "acc-1"))
    }

    /** The descendant test is the prefix PLUS the delimiter: bare, `Travailleur` is dragged off
     *  with `Travail` and the role points at a path that never existed. */
    @Test fun `a namesake sibling of the renamed folder stays where it is`() {
        val out = withRenamedSpecialFolder(
            listOf(account("acc-1", mapOf("archive" to "Travailleur"))),
            "acc-1",
            "Travail",
            "Boulot",
            "/",
        )
        assertEquals(mapOf("archive" to "Travailleur"), foldersOf(out, "acc-1"))
    }

    @Test fun `a rename on another account touches nothing here`() {
        val out = withRenamedSpecialFolder(
            listOf(account("acc-1", mapOf("trash" to "Poubelle")), account("acc-2", mapOf("trash" to "Poubelle"))),
            "acc-2",
            "Poubelle",
            "Corbeille",
            "/",
        )
        assertEquals(mapOf("trash" to "Poubelle"), foldersOf(out, "acc-1"))
        assertEquals(mapOf("trash" to "Corbeille"), foldersOf(out, "acc-2"))
    }

    // ---- a destroyed folder (#227)

    /** Every role the destroyed ids carried goes, the caller having computed the subtree. */
    @Test fun `a delete forgets every role the destroyed folders carried`() {
        val out = withoutSpecialFolders(
            listOf(account("acc-1", mapOf("trash" to "Bin", "junk" to "Bin", "sent" to "Envoyes"))),
            "acc-1",
            listOf("Bin", "Travail/Spam"),
        )
        assertEquals(mapOf("sent" to "Envoyes"), foldersOf(out, "acc-1"))
    }

    /** Only what the caller named: this is the destroyed subtree, not a folder merely absent from
     *  a listing, whose choice is kept because the folder may be back. */
    @Test fun `a delete leaves the choices naming folders it did not destroy`() {
        val out = withoutSpecialFolders(
            listOf(account("acc-1", mapOf("trash" to "Travailleur", "junk" to "Travail/Spam"))),
            "acc-1",
            listOf("Travail", "Travail/Spam"),
        )
        assertEquals(mapOf("trash" to "Travailleur"), foldersOf(out, "acc-1"))
    }

    @Test fun `a delete on another account forgets nothing here`() {
        val out = withoutSpecialFolders(
            listOf(account("acc-1", mapOf("trash" to "Bin")), account("acc-2", mapOf("trash" to "Bin"))),
            "acc-2",
            listOf("Bin"),
        )
        assertEquals(mapOf("trash" to "Bin"), foldersOf(out, "acc-1"))
        assertEquals(emptyMap<String, String>(), foldersOf(out, "acc-2"))
    }

    /** The five the record can carry, and the two the lot leaves out, named here so a sixth added
     *  to the list has to be added on purpose. */
    @Test fun `the roles a folder can be pinned to are the five special ones`() {
        assertEquals(listOf("archive", "sent", "drafts", "trash", "junk"), SPECIAL_FOLDER_ROLES)
    }
}
