package app.sterna.ui.inbox

import app.sterna.R
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.mail.CrossAccountMove
import app.sterna.core.data.mail.EmailKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions behind a move into ANOTHER account's folder (#189), EXECUTED: which account the
 * picker lists, which folder it hides, whether the gesture leaves the account at all, and what the
 * one banner at the end says. Their call sites are pinned by [CrossAccountMoveWiringLintTest].
 */
class CrossAccountPickerTest {

    @Test
    fun `the picker lists the owner's folders until another account is chosen`() {
        assertEquals("A", pickerAccount(chosen = null, owner = "A"))
        assertEquals("B", pickerAccount(chosen = "B", owner = "A"))
        assertEquals("A", pickerAccount(chosen = "A", owner = "A"))
        assertNull(pickerAccount(chosen = null, owner = null))
        // A choice made while the owner is still unknown stands: the picker has one account.
        assertEquals("B", pickerAccount(chosen = "B", owner = null))
    }

    @Test
    fun `the current folder is excluded only inside the owner's own list`() {
        assertEquals("inbox", pickerExcludedMailbox(chosen = "A", owner = "A", current = "inbox"))
        // #92: an id of A would hide a homonymous folder of B.
        assertNull(pickerExcludedMailbox(chosen = "B", owner = "A", current = "inbox"))
        assertEquals("inbox", pickerExcludedMailbox(chosen = null, owner = "A", current = "inbox"))
        assertNull(pickerExcludedMailbox(chosen = null, owner = "A", current = null))
        assertNull(pickerExcludedMailbox(chosen = "B", owner = null, current = "inbox"))
    }

    @Test
    fun `picking the owner again is no choice at all`() {
        assertNull(pickerChoice(picked = "A", owner = "A"))
        assertEquals("B", pickerChoice(picked = "B", owner = "A"))
        assertEquals("B", pickerChoice(picked = "B", owner = null))
    }

    @Test
    fun `the owner leads the account row and the others keep the store's order`() {
        val a = account("A")
        val b = account("B")
        val c = account("C")
        assertEquals(listOf(b, a, c), pickerAccounts(listOf(a, b, c), owner = "B"))
        assertEquals(listOf(a, b, c), pickerAccounts(listOf(a, b, c), owner = "A"))
        // An owner the store no longer has: nobody leads, nothing is dropped.
        assertEquals(listOf(a, b, c), pickerAccounts(listOf(a, b, c), owner = "gone"))
        assertEquals(listOf(a, b, c), pickerAccounts(listOf(a, b, c), owner = null))
    }

    /**
     * The list's picker on a MIXED selection: spec.md § 1 keeps a selection spanning accounts on
     * main's behaviour (the retained account's messages go, the others are counted as skipped), so
     * the account row must not be drawn at all. It is not drawn by handing it no account to choose
     * from: [MoveAccountRow] returns straight away under two entries.
     */
    @Test
    fun `a selection that spans accounts is offered no account at all`() {
        val a = account("A")
        val b = account("B")
        val accounts = listOf(a, b)

        assertEquals(
            "a selection of two accounts must offer NO account row: the gesture only moves one " +
                "account's messages and skips the rest (spec.md § 1), so 'B › Archive' would " +
                "announce something the move does not do",
            emptyList<StoredAccount>(),
            selectionPickerAccounts(accounts, setOf(EmailKey("A", "1"), EmailKey("B", "2"))),
        )
        // The whole list, in the store's own order — this is the case #189 exists for (#73).
        assertEquals(
            "a selection inside ONE account keeps the account row, untouched and in order",
            accounts,
            selectionPickerAccounts(accounts, setOf(EmailKey("A", "1"), EmailKey("A", "2"))),
        )
        assertEquals(accounts, selectionPickerAccounts(accounts, setOf(EmailKey("A", "1"))))
        // Nothing selected resolves no account either: no owner, no row.
        assertEquals(emptyList<StoredAccount>(), selectionPickerAccounts(accounts, emptySet()))
        // A key whose account is unknown does not resolve one either, even next to a known one.
        assertEquals(
            emptyList<StoredAccount>(),
            selectionPickerAccounts(accounts, setOf(EmailKey(null, "1"), EmailKey("A", "2"))),
        )
        // The guard is selectionAccount's answer and nothing else: a single null-account key
        // resolves no account, so it gets no row either.
        assertEquals(emptyList<StoredAccount>(), selectionPickerAccounts(accounts, setOf(EmailKey(null, "1"))))
    }

    /**
     * The same guard, at the OTHER end: what the picker LISTS and where the tap GOES. The account
     * choice outlives the sheet it was made in (rotation closes the sheet, the ViewModel keeps the
     * choice), so a choice made on a single-account selection must stop counting the moment the
     * selection stops resolving one — otherwise the picker lists B's folders under names
     * indistinguishable from A's, with no account row on screen to explain it.
     */
    @Test
    fun `an account chosen on one selection stops counting on a selection that spans accounts`() {
        val mixed = setOf(EmailKey("A", "1"), EmailKey("B", "2"))
        val single = setOf(EmailKey("A", "1"), EmailKey("A", "2"))

        assertNull(
            "a choice held over from an earlier selection must be ignored once the selection " +
                "spans accounts: the picker would list B's folders and moveSelectedTo would send " +
                "A's message to B, with nothing on screen saying so (#189)",
            selectionMoveAccount("B", mixed),
        )
        // The case #189 exists for is untouched: one account selected, the choice stands.
        assertEquals("B", selectionMoveAccount("B", single))
        assertEquals("B", selectionMoveAccount("B", setOf(EmailKey("A", "1"))))
        // No choice made is no choice, whatever is selected.
        assertNull(selectionMoveAccount(null, single))
        assertNull(selectionMoveAccount(null, mixed))
        // Nothing selected resolves no account either — same answer as the row's.
        assertNull(selectionMoveAccount("B", emptySet()))
    }

    @Test
    fun `a move is between accounts only when a DIFFERENT account was chosen`() {
        assertFalse(isCrossAccountMove(targetAccountId = null, ownerAccountId = "A"))
        assertFalse(isCrossAccountMove(targetAccountId = "A", ownerAccountId = "A"))
        assertTrue(isCrossAccountMove(targetAccountId = "B", ownerAccountId = "A"))
        assertTrue(isCrossAccountMove(targetAccountId = "B", ownerAccountId = null))
    }

    @Test
    fun `the banner says a copy whose original stayed before anything else`() {
        val moved = CrossAccountMove.Moved
        val copied = CrossAccountMove.CopiedNotRemoved("x")
        val failed = CrossAccountMove.Failed("y")
        assertEquals(CrossAccountOutcome.ALL_MOVED, crossAccountBanner(listOf(moved, moved), skipped = 0))
        assertEquals(CrossAccountOutcome.SOME_COPIED_NOT_REMOVED, crossAccountBanner(listOf(moved, copied), skipped = 0))
        assertEquals(CrossAccountOutcome.PARTLY_FAILED, crossAccountBanner(listOf(failed, moved), skipped = 0))
        assertEquals(CrossAccountOutcome.PARTLY_FAILED, crossAccountBanner(listOf(moved), skipped = 1))
        assertEquals(CrossAccountOutcome.SOME_COPIED_NOT_REMOVED, crossAccountBanner(listOf(copied, failed), skipped = 0))
        assertEquals(CrossAccountOutcome.SOME_COPIED_NOT_REMOVED, crossAccountBanner(listOf(copied), skipped = 3))
    }

    @Test
    fun `the target is named account first, then folder`() {
        assertEquals("Work › Archive", crossAccountTargetLabel("Work", "Archive"))
    }

    /**
     * The words each verdict of ONE message comes with — the banner is the only thing said, there
     * is no Undo behind it, and "Moved to B › Archive" over a copy whose original stayed announces
     * a move that did not happen and hides a duplicate. Executed, not read off the source.
     */
    @Test
    fun `a copy whose original stayed is never announced as a move`() {
        // The three answers must BE three answers: a vacuous test would pass with all ids equal.
        assertNotEquals(R.string.status_moved_to_account, R.string.status_copied_not_removed)
        assertNotEquals(R.string.status_action_failed, R.string.status_action_offline)

        assertEquals(R.string.status_moved_to_account, crossAccountMessageRes(CrossAccountMove.Moved, online = true))
        assertEquals(R.string.status_moved_to_account, crossAccountMessageRes(CrossAccountMove.Moved, online = false))
        assertEquals(
            "a copy whose original stayed must say so, never 'moved': the original is still on A, " +
                "the user has a duplicate, and there is no Undo to take it back",
            R.string.status_copied_not_removed,
            crossAccountMessageRes(CrossAccountMove.CopiedNotRemoved("x"), online = true),
        )
        // Offline changes nothing here: the copy IS on B, and the original IS still on A.
        assertEquals(
            R.string.status_copied_not_removed,
            crossAccountMessageRes(CrossAccountMove.CopiedNotRemoved("x"), online = false),
        )
        assertEquals(R.string.status_action_failed, crossAccountMessageRes(CrossAccountMove.Failed("y"), online = true))
        assertEquals(R.string.status_action_offline, crossAccountMessageRes(CrossAccountMove.Failed("y"), online = false))
    }

    /** The same, for the ONE line a selection ends on — counted for two outcomes, plain for the third. */
    @Test
    fun `the selection banner counts what it says, and says nothing else`() {
        assertNotEquals(R.plurals.status_selection_moved, R.plurals.status_selection_copied_not_removed)

        assertEquals(
            "all moved is counted with the 'moved' plural",
            R.plurals.status_selection_moved,
            crossAccountSelectionPlural(CrossAccountOutcome.ALL_MOVED),
        )
        assertEquals(
            "and a batch that left originals behind must count THOSE, in their own words",
            R.plurals.status_selection_copied_not_removed,
            crossAccountSelectionPlural(CrossAccountOutcome.SOME_COPIED_NOT_REMOVED),
        )
        // Nothing to count: that line is a plain string, and it is the other function's.
        assertNull(crossAccountSelectionPlural(CrossAccountOutcome.PARTLY_FAILED))

        assertEquals(
            R.string.status_action_partly_failed,
            crossAccountSelectionFailedRes(CrossAccountOutcome.PARTLY_FAILED),
        )
        assertNull(crossAccountSelectionFailedRes(CrossAccountOutcome.ALL_MOVED))
        assertNull(crossAccountSelectionFailedRes(CrossAccountOutcome.SOME_COPIED_NOT_REMOVED))
    }

    private fun account(id: String) = StoredAccount(id = id, server = "mail.example.test", username = "$id@example.test")
}
