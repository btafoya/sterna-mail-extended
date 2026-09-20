package app.sterna.core.data.mail

import app.sterna.core.data.account.SPECIAL_FOLDER_ROLES
import app.sterna.core.data.db.MailboxIdRole
import app.sterna.core.imap.ImapFolder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The user's per-account choice of a folder for a special role, executed where the role is decided
 * ([imapMailboxEntity]), not where it is read: every consumer of the `role` column, the drawer, the
 * search, the delete, follows from the row alone, offline included.
 */
class ChosenSpecialFolderTest {

    private fun folder(path: String, role: String? = null, unelected: String? = null, delimiter: String = "/") =
        ImapFolder(
            name = path.substringAfterLast(delimiter),
            path = path,
            role = role,
            delimiter = delimiter,
            unelectedRole = unelected,
        )

    /**
     * A listing where every role is already carried by the folder the server or the name table
     * elected, so a choice can only ever take a role away from someone.
     *
     * `INBOX.Courrier ancien` is a folder of a Dovecot `INBOX.` namespace: its leaf name and its
     * path differ, which is what tells a choice matched on the path apart from one matched on the
     * name. Matching the name would pin a role onto no folder at all on such a server while
     * dispossessing the automatic holder, leaving the role with no holder.
     */
    private val listing = listOf(
        folder("INBOX", role = "inbox"),
        folder("Trash", role = "trash"),
        folder("Spam", role = "junk"),
        folder("Archives", role = "archive"),
        folder("Vieux courrier"),
        folder("Corbeille perso", unelected = "trash"),
        folder("INBOX.Courrier ancien", delimiter = "."),
    )

    /** Every row's role as the cache would hold it, keyed by path, for [chosen] as the account
     *  stores it, through the same two calls `listMailboxes` makes. */
    private fun roles(chosen: Map<String, String>): Map<String, String?> {
        val honoured = honouredSpecialFolders(chosen, listing.map { it.roleCandidate() })
        return listing.mapIndexed { index, folder ->
            folder.path to imapMailboxEntity("acc-1", folder, index, honoured).role
        }.toMap()
    }

    /** What `MailRepository.imapRoleFolder` would resolve off those rows: the first row carrying
     *  [role], its own one-line rule. */
    private fun resolved(chosen: Map<String, String>, role: String): String? =
        roles(chosen).entries.firstOrNull { it.value == role }?.key

    @Test fun `with no choice stored nothing moves`() {
        assertEquals(
            mapOf(
                "INBOX" to "inbox",
                "Trash" to "trash",
                "Spam" to "junk",
                "Archives" to "archive",
                "Vieux courrier" to null,
                "Corbeille perso" to "~trash",
                "INBOX.Courrier ancien" to null,
            ),
            roles(emptyMap()),
        )
    }

    @Test fun `the chosen folder carries the role, plainly`() {
        assertEquals("junk", roles(mapOf("junk" to "Vieux courrier"))["Vieux courrier"])
        assertEquals("archive", roles(mapOf("archive" to "Vieux courrier"))["Vieux courrier"])
        assertEquals("sent", roles(mapOf("sent" to "Vieux courrier"))["Vieux courrier"])
        assertEquals("drafts", roles(mapOf("drafts" to "Vieux courrier"))["Vieux courrier"])
        assertEquals("trash", roles(mapOf("trash" to "Vieux courrier"))["Vieux courrier"])
    }

    /** The choice names a folder by its id, which on IMAP is its PATH: on a server whose folders
     *  hang under a namespace the leaf name names no folder the account can be pointed at. */
    @Test fun `a folder under a namespace is pinned by its path, not by its leaf name`() {
        val nested = "INBOX.Courrier ancien"
        SPECIAL_FOLDER_ROLES.forEach { role ->
            val rows = roles(mapOf(role to nested))
            assertEquals("pinning $role on $nested must give that row the role", role, rows[nested])
            assertEquals(
                "and the leaf name must pin nothing: `Courrier ancien` is not a folder of this listing",
                emptyMap<String, String>(),
                honouredSpecialFolders(mapOf(role to "Courrier ancien"), listing.map { it.roleCandidate() }),
            )
        }
    }

    /** A row carries one role, so a folder pinned to two settles in the order the store declares
     *  ([SPECIAL_FOLDER_ROLES]), never in the order a map happened to iterate. */
    @Test fun `a folder pinned to two roles takes the first of the declared order`() {
        val rows = roles(mapOf("junk" to "Vieux courrier", "drafts" to "Vieux courrier"))
        assertEquals("drafts", rows["Vieux courrier"])
        assertEquals("~junk", rows["Spam"])
        val reversed = roles(mapOf("trash" to "Vieux courrier", "archive" to "Vieux courrier"))
        assertEquals("archive", reversed["Vieux courrier"])
    }

    @Test fun `the dispossessed spam is marked, exactly like a loser of the listing's own election`() {
        val rows = roles(mapOf("junk" to "Vieux courrier"))
        assertEquals("~junk", rows["Spam"])
        assertEquals("junk", rows["Vieux courrier"])
    }

    @Test fun `the dispossessed trash is marked too, and so is the one that had already lost`() {
        val rows = roles(mapOf("trash" to "Vieux courrier"))
        assertEquals("~trash", rows["Trash"])
        assertEquals("~trash", rows["Corbeille perso"])
        assertEquals("trash", rows["Vieux courrier"])
    }

    /** Only the roles a search skips are marked, as [UNELECTED_ROLE_MARK] says: a dispossessed
     *  archive is an ordinary folder and stays searchable. */
    @Test fun `a dispossessed archive simply loses the role`() {
        assertEquals(null, roles(mapOf("archive" to "Vieux courrier"))["Archives"])
    }

    @Test fun `a folder that had claimed the role and lost it takes it plainly when chosen`() {
        val rows = roles(mapOf("trash" to "Corbeille perso"))
        assertEquals("trash", rows["Corbeille perso"])
        assertEquals("~trash", rows["Trash"])
    }

    /**
     * The guard that protects mail: a choice naming a folder this listing does not carry (deleted
     * on the server, unsubscribed, a listing that failed) falls back on the automatic role. With
     * the role held by nobody, the gestures fall back on a folder recognised by its name, and the
     * message lands in one nobody designated.
     */
    @Test fun `a choice naming a folder the listing does not carry leaves the automatic role alone`() {
        val rows = roles(mapOf("trash" to "Poubelle disparue"))
        assertEquals("trash", rows["Trash"])
        assertEquals("~trash", rows["Corbeille perso"])
        assertEquals("Trash", resolved(mapOf("trash" to "Poubelle disparue"), "trash"))
    }

    @Test fun `a role is carried by exactly one folder, chosen or not`() {
        listOf(emptyMap(), mapOf("trash" to "Vieux courrier"), mapOf("trash" to "Poubelle disparue")).forEach { chosen ->
            assertEquals(
                "two folders carrying `trash` is two bins in the drawer, and a delete aiming at " +
                    "whichever came first in the listing. Choice was $chosen",
                1,
                roles(chosen).values.count { it == "trash" },
            )
        }
    }

    @Test fun `the delete resolves onto the chosen folder`() {
        assertEquals("Vieux courrier", resolved(mapOf("trash" to "Vieux courrier"), "trash"))
        assertEquals("Trash", resolved(emptyMap(), "trash"))
    }

    /**
     * The other half of the same rule, and the one nothing else guards: the inbox may not be the
     * target of a choice either. A row whose `inbox` role is overwritten stops answering
     * `MailRepository.cachedInboxMailboxId`, so unarchiving silently moves nothing, and under a
     * `trash` or `junk` role it drops out of every search result.
     */
    @Test fun `the inbox is never honoured as a target`() {
        SPECIAL_FOLDER_ROLES.forEach { role ->
            val rows = roles(mapOf(role to "INBOX"))
            assertEquals("pinning $role on the inbox must change nothing", "inbox", rows["INBOX"])
            assertEquals("and must leave the automatic holder its role", "trash", rows["Trash"])
        }
    }

    /** The inbox is recognised by the path RFC 3501 §5.1 reserves, whatever case the server wrote
     *  it in and whether or not the listing got as far as giving it the role. */
    @Test fun `the reserved path is refused as a target even without the role`() {
        val listed = listOf(folder("inbox"), folder("Trash", role = "trash"))
        assertEquals(emptyMap<String, String>(), honouredSpecialFolders(mapOf("trash" to "inbox"), listed.map { it.roleCandidate() }))
    }

    /** The inbox is not one of the five ([SPECIAL_FOLDER_ROLES]): RFC 3501 §5.1 fixes it to the
     *  path INBOX, and a stored key naming it must take nothing. */
    @Test fun `a key that is not one of the five roles takes nothing`() {
        val rows = roles(mapOf("inbox" to "Vieux courrier", "all" to "Archives"))
        assertEquals("inbox", rows["INBOX"])
        assertEquals(null, rows["Vieux courrier"])
        assertEquals("archive", rows["Archives"])
    }

    /** The marking is the one thing that keeps a dispossessed spam out of every search result. */
    @Test fun `the dispossessed spam stays out of search and the chosen one too`() {
        val rows = roles(mapOf("junk" to "Vieux courrier"))
        val drawer = listOf(
            MailboxIdRole("INBOX", rows["INBOX"]),
            MailboxIdRole("Spam", rows["Spam"]),
            MailboxIdRole("Vieux courrier", rows["Vieux courrier"]),
        )
        assertEquals(listOf("INBOX"), searchableFolderIds(drawer))
        assertEquals(listOf("Spam", "Vieux courrier"), excludedSearchFolderIds(drawer))
    }

    /** The row is a role change, not a rename or a reorder. */
    @Test fun `choosing a folder changes its role and its rank, and nothing else about the row`() {
        val chosen = imapMailboxEntity("acc-1", listing[4], index = 4, mapOf("trash" to "Vieux courrier"))
        val automatic = imapMailboxEntity("acc-1", listing[4], index = 4)
        assertEquals(automatic.copy(role = "trash", sortOrder = 5004), chosen)
    }

    /** What the listing settles is untouched when nothing is pinned: the server-stated role still
     *  beats a guessed one, this choice sits above that rule rather than replacing it. */
    @Test fun `the listing's own election is left whole`() {
        val stated = listOf(folder("Bin", role = "trash"), folder("Trash", unelected = "trash"))
        val honoured = honouredSpecialFolders(emptyMap(), stated.map { it.roleCandidate() })
        assertEquals("trash", imapMailboxEntity("acc-1", stated[0], 0, honoured).role)
        assertEquals("~trash", imapMailboxEntity("acc-1", stated[1], 1, honoured).role)
    }
}
