package app.sterna.core.data.mail

import app.sterna.core.data.db.MailboxEntity
import app.sterna.core.data.db.MailboxIdRole
import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the `~trash` mark STOPS — [MailboxEntity.toMailbox], EXECUTED.
 *
 * A folder that claimed the trash and lost it (a personal `Trash` plus a shared `Shared/…/Trash`)
 * is stored with a marked role so search and the FTS crawl skip it. That mark must not reach the
 * app: `ui.inbox.InboxScreen` gates "New subfolder", "Rename" and "Delete" of a folder's ⋮ menu on
 * `mailbox.role == null` — the one place in the app that tells "no role" apart from "a role I do
 * not know" — and it read `~trash` as a role, leaving the user's shared trash with a one-line menu,
 * no rename and no delete. The search fix must not cost a folder its menu.
 *
 * `toMailbox()` is that crossing: its only two call sites are `MailRepository.observeMailboxes`,
 * which is what every screen sees. Every expectation below is a literal; the shipped mapper is
 * called with pinned arguments and nothing here recomputes the erasure rule.
 */
class UnelectedRoleStopsAtTheDataBoundaryTest {

    private fun row(role: String?) = MailboxEntity(
        accountId = "acc-1",
        id = "Shared/team/Trash",
        name = "Trash",
        role = role,
        parentId = "Shared/team",
        sortOrder = 6003,
        totalEmails = 12,
        unreadEmails = 3,
    )

    @Test fun aMarkedTrashCrossesAsNoRoleAtAll() {
        assertNull(row("~trash").toMailbox().role)
    }

    @Test fun aMarkedJunkCrossesAsNoRoleAtAll() {
        assertNull(row("~junk").toMailbox().role)
    }

    /** The witness: the folder that WON the role still crosses as the trash, or the erasure would
     *  be taking the icon and the delete destination off the real one. */
    @Test fun theElectedTrashKeepsItsRoleAcross() {
        assertEquals("trash", row("trash").toMailbox().role)
        assertEquals("junk", row("junk").toMailbox().role)
        assertEquals("inbox", row("inbox").toMailbox().role)
        assertEquals("sent", row("sent").toMailbox().role)
    }

    @Test fun aFolderThatNeverHadARoleStillCrossesWithNone() {
        assertNull(row(null).toMailbox().role)
    }

    /**
     * The gesture this buys back. `InboxScreen` shows New subfolder / Rename / Delete only when
     * the mailbox it was handed has `role == null`; those three branches are the only writes of
     * `folderToAddChild` / `folderToRename` / `folderToDelete` in the app. Run the condition on the
     * model the repository actually hands it for a losing shared trash.
     */
    @Test fun theFolderMenuStillOffersSubfolderRenameAndDeleteOnALosingSharedTrash() {
        val onScreen = row("~trash").toMailbox()
        assertTrue(
            "a folder that lost its trash claim must reach the drawer as an ordinary folder, or " +
                "its ⋮ menu is down to Watch — no rename, no delete",
            onScreen.role == null,
        )
    }

    /** And the elected trash keeps the menu it always had (no subfolder/rename/delete on it). */
    @Test fun theFolderMenuStillWithholdsThemFromTheRealTrash() {
        assertTrue(row("trash").toMailbox().role != null)
    }

    /** The erasure touches the ROLE and nothing else: same id, name, parent, rank and counters, so
     *  the drawer row is character for character the one `main` drew. */
    @Test fun everyOtherFieldCrossesUntouched() {
        val marked = row("~trash").toMailbox()
        assertEquals("Shared/team/Trash", marked.id)
        assertEquals("Trash", marked.name)
        assertEquals("Shared/team", marked.parentId)
        assertEquals(6003, marked.sortOrder)
        assertEquals(12, marked.totalEmails)
        assertEquals(3, marked.unreadEmails)
        assertEquals(3, marked.unreadForList)
        assertNull("the role is still erased, and that is what buys the ⋮ menu back", marked.role)
    }

    /**
     * The ONE field that now differs from the role-less row this used to be equal to, and why it
     * had to: the drawer is the only way back into a folder that lost its claim (it is out of search
     * by an older, deliberate decision), and "subscribed folders only" (#174) hides a folder that is
     * unsubscribed, role-less and no routing target — which is precisely this one.
     *
     * The rest of the row is pinned above, field by field; only [Mailbox.lostRoleClaim] parts ways.
     */
    @Test fun theLostClaimIsTheSINGLEDifferenceFromTheRoleLessRow() {
        val marked = row("~trash").toMailbox()
        val roleLess = row(null).toMailbox()
        assertEquals(roleLess.copy(lostRoleClaim = true), marked)
        assertTrue(marked.lostRoleClaim)
        assertFalse(roleLess.lostRoleClaim)
    }

    /**
     * The case that closes the defect: a `Junk` and a `Spam` on one IMAP account, one of them
     * elected. The loser crosses with NO role — nothing about its icon, its menu or its
     * destinations changes — and with the claim carried as a boolean, which is all the drawer needs
     * to keep listing it.
     */
    @Test fun aLosingJunkCrossesWithNoRoleAndACarriedClaim() {
        val loser = row("~junk").toMailbox()
        assertNull("a lost claim is not a role and must not reach the screen as one", loser.role)
        assertTrue("the losing Spam must stay reachable from the drawer (#174)", loser.lostRoleClaim)
    }

    /** And nothing else carries it: an elected role, and no role at all, are not lost claims. */
    @Test fun anElectedRoleAndNoRoleAtAllCarryNoClaim() {
        assertFalse(row("junk").toMailbox().lostRoleClaim)
        assertFalse(row("trash").toMailbox().lostRoleClaim)
        assertFalse(row("inbox").toMailbox().lostRoleClaim)
        assertFalse(row("sent").toMailbox().lostRoleClaim)
        assertFalse(row(null).toMailbox().lostRoleClaim)
    }

    /**
     * The erasure is not a hole in the search fix: what excludes a losing trash reads the STORED
     * role, never this model. Both filters still answer on the marked row.
     */
    @Test fun theSearchFiltersStillSeeTheMarkOnTheStoredRow() {
        val stored = listOf(
            MailboxIdRole("i", "inbox"),
            MailboxIdRole("t", "~trash"),
        )
        assertEquals(listOf("i"), searchableFolderIds(stored))
        assertEquals(listOf("t"), excludedSearchFolderIds(stored))
    }
}
