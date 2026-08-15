package app.sterna.core.data.mail

import app.sterna.core.data.db.MailboxIdRole
import app.sterna.core.imap.ImapFolder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The cache row an IMAP folder becomes ([imapMailboxEntity]), EXECUTED — the one place that turns
 * "this folder claimed the trash and lost it" into something the rest of the app can act on.
 *
 * The defect: an account with a personal `Trash` and a shared `Shared/…/Trash` has two trashes,
 * `ImapClient.assignRoles` elects one, and the loser used to be stored role-less. A role-less
 * folder IS searched ([searchableFolderIds]) and IS crawled into the local index, so mail thrown
 * into the shared trash came back in search results and stayed there through a cache purge.
 *
 * Every expectation below is a literal. Nothing here recomputes the marking rule or reads the
 * source that contains it: the shipped function is called with pinned arguments, and inverting
 * what it decides makes these red.
 */
class UnelectedTrashIsMarkedTest {

    private fun folder(name: String, role: String?, unelected: String? = null) =
        ImapFolder(name = name, path = "Shared/team/$name", role = role, delimiter = "/", unelectedRole = unelected)

    private fun roleOf(folder: ImapFolder) = imapMailboxEntity("acc-1", folder, index = 3).role

    @Test fun aTrashThatLostTheRoleIsStoredMarked() {
        assertEquals("~trash", roleOf(folder("Trash", role = null, unelected = "trash")))
    }

    @Test fun aJunkThatLostTheRoleIsStoredMarked() {
        assertEquals("~junk", roleOf(folder("Spam", role = null, unelected = "junk")))
    }

    @Test fun theFolderThatWonTheRoleKeepsItPlain() {
        assertEquals("trash", roleOf(folder("Trash", role = "trash")))
        assertEquals("junk", roleOf(folder("Junk", role = "junk")))
    }

    /**
     * ⛔ A lost claim on any other role stays null, exactly as on the code this fixes: nothing
     * anywhere reads a `~sent`, and marking one would only put an unreadable value in the cache
     * row for no filter to use.
     */
    @Test fun aLostClaimOnAnyOtherRoleStaysNull() {
        assertEquals(null, roleOf(folder("Gesendet", role = null, unelected = "sent")))
        assertEquals(null, roleOf(folder("Archiv", role = null, unelected = "archive")))
        assertEquals(null, roleOf(folder("Entwürfe", role = null, unelected = "drafts")))
        assertEquals(null, roleOf(folder("Inbox", role = null, unelected = "inbox")))
    }

    @Test fun anOrdinaryFolderThatClaimedNothingStaysRoleLess() {
        assertEquals(null, roleOf(folder("Projekte", role = null)))
    }

    /** The rest of the row is untouched: the marking is a role, not a rename. */
    @Test fun theMarkedFolderKeepsItsRawNameItsPathAndItsPlaceInTheDrawer() {
        val row = imapMailboxEntity("acc-1", folder("Trash", role = null, unelected = "trash"), index = 3)
        assertEquals("acc-1", row.accountId)
        assertEquals("Shared/team/Trash", row.id)
        assertEquals("Trash", row.name)
        // 6 * 1000 + 3: the `else` rank, where a role-less folder already sat — a folder that lost
        // its claim must not jump to the trash's place in the drawer.
        assertEquals(6003, row.sortOrder)
        assertEquals(0, row.totalEmails)
        assertEquals(0, row.unreadEmails)
    }

    /** And the winner still ranks as the trash, so the marking did not cost the drawer its order. */
    @Test fun theElectedTrashKeepsItsRank() {
        assertEquals(5003, imapMailboxEntity("acc-1", folder("Trash", role = "trash"), index = 3).sortOrder)
    }

    /**
     * The mark and the source that excludes it are one decision in two files, in two modules that
     * do not depend on each other. This is where they meet: what [imapMailboxEntity] writes must
     * be what [NOT_SEARCHED_ROLES] holds, or the row is marked and searched anyway.
     */
    @Test fun whatIsMarkedIsWhatTheSearchSourceExcludes() {
        val drawer = listOf(
            MailboxIdRole("i", "inbox"),
            MailboxIdRole("t", roleOf(folder("Trash", role = null, unelected = "trash"))),
            MailboxIdRole("j", roleOf(folder("Spam", role = null, unelected = "junk"))),
        )
        assertEquals(listOf("i"), searchableFolderIds(drawer))
        assertEquals(listOf("t", "j"), excludedSearchFolderIds(drawer))
    }

    /**
     * And the shipped listings go through it — BOTH of them. The construction used to be written
     * out twice, identically, in `listMailboxes` and `loadFolder`; a rule applied to one copy and
     * not the other is the shape this extraction exists to make impossible. Read from the source
     * because neither caller can be reached from a JVM test (both need a live IMAP session), and
     * the arguments are pinned, not just the name of the function called.
     */
    @Test fun bothImapFolderListingsBuildTheirRowsInTheOnePlace() {
        val source = DaoQuerySource.mailSource("ImapMailService")
        assertEquals(
            "ImapMailService constructs a MailboxEntity outside imapMailboxEntity — the marking " +
                "would then apply to one folder listing and not the other",
            1, Regex("""(?<![A-Za-z])MailboxEntity\(""").findAll(source).count(),
        )
        assertEquals(
            "listMailboxes and loadFolder must both hand the account id, the listed folder and its " +
                "position to imapMailboxEntity",
            2, Regex("""imapMailboxEntity\(credentials\.id, folder, index\)""").findAll(source).count(),
        )
    }
}
