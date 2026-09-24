package app.sterna.ui.inbox

import app.sterna.ui.NotificationAccountSwitch
import app.sterna.ui.NotificationFolderSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs [unifiedViewByChoice] against the two switches a tapped new-mail notification goes through
 * (#185). The launch is replayed through [openOnSelection] and [openOnOverridesMemory], the two
 * things `InboxViewModel` and `RootViewModel` hand it, never through a copy of either.
 */
class UnifiedViewByChoiceTest {

    /** A tap on B's notification for its Archive, both halves asked the same question. */
    private fun route(sel: Sel?, openedBySetting: Boolean): Pair<String?, String?> {
        val unified = unifiedViewByChoice(sel, openedBySetting)
        return NotificationAccountSwitch.resolve(ACCOUNT_B, ACCOUNT_A, listOf(ACCOUNT_A, ACCOUNT_B), unified) to
            NotificationFolderSwitch.resolve(ARCHIVE, (sel as? Sel.Folder)?.id, unified, listOf(INBOX, ARCHIVE))
    }

    private fun launch(openOnView: String?, remembered: Sel?): Pair<String?, String?> =
        route(openOnSelection(openOnView, remembered, true), openOnOverridesMemory(openOnView))

    @Test
    fun `under the setting's All inboxes, the notification moves both the account and the list`() {
        assertEquals(ACCOUNT_B to ARCHIVE, launch(OPEN_ON_UNIFIED, Sel.Folder(INBOX)))
        assertEquals(ACCOUNT_B to ARCHIVE, launch(OPEN_ON_UNIFIED, Sel.Unread(allAccounts = false)))
        assertEquals(ACCOUNT_B to ARCHIVE, launch(OPEN_ON_UNIFIED, null))
    }

    /** The memory is written from the view on screen, the seed included: after one launch under
     *  the setting it says "All inboxes" too, and must not win the view back for the setting. */
    @Test
    fun `under the setting, a memory that says All inboxes does not keep the list where it is`() {
        assertEquals(ACCOUNT_B to ARCHIVE, launch(OPEN_ON_UNIFIED, Sel.Unified))
    }

    @Test
    fun `under the view you left, a remembered All inboxes stays put as on main`() {
        assertEquals(null to null, launch(OPEN_ON_LAST, Sel.Unified))
        assertEquals(null to null, launch(null, Sel.Unified))
    }

    /** #235: the unread view of every account is reached from "All inboxes" and stands for it. */
    @Test
    fun `a remembered unread view of every account stays put like All inboxes`() {
        assertEquals(null to null, launch(OPEN_ON_LAST, Sel.Unread(allAccounts = true)))
        assertEquals(null to null, route(Sel.Unread(allAccounts = true), openedBySetting = false))
    }

    @Test
    fun `an All inboxes the reader went back to herself is not moved`() {
        assertEquals(null to null, route(Sel.Unified, openedBySetting = false))
        assertTrue(unifiedViewByChoice(Sel.Unified, openedBySetting = false))
    }

    @Test
    fun `a folder or the unread view is never a unified view, whoever opened it`() {
        for (openedBySetting in listOf(true, false)) {
            assertFalse(unifiedViewByChoice(Sel.Folder(INBOX), openedBySetting))
            assertFalse(unifiedViewByChoice(Sel.Unread(allAccounts = false), openedBySetting))
            assertFalse(unifiedViewByChoice(null, openedBySetting))
        }
    }

    @Test
    fun `the account half and the folder half give the same answer on every launch`() {
        for (stored in STORED_VALUES) {
            for (remembered in REMEMBERED) {
                val (account, folder) = launch(stored, remembered)
                assertEquals("stored=$stored, remembered=$remembered: $account, $folder", account == null, folder == null)
            }
        }
    }

    /**
     * [openOnOverridesMemory] restates which values [openOnSelection] answers without the memory.
     * Held level here, value by value: a value the rule reads the memory for must not be reported
     * as the setting's, and the reverse.
     */
    @Test
    fun `the setting overrides the memory exactly where openOnSelection ignores it`() {
        for (stored in STORED_VALUES) {
            for (unifiedViewExists in listOf(true, false)) {
                val answers = REMEMBERED.map { openOnSelection(stored, it, unifiedViewExists) }
                val readsMemory = answers != List(REMEMBERED.size) { answers.first() }
                assertEquals(
                    "stored=$stored, unifiedViewExists=$unifiedViewExists: answers $answers",
                    !readsMemory,
                    openOnOverridesMemory(stored),
                )
            }
        }
    }

    private companion object {
        const val ACCOUNT_A = "acc-a"
        const val ACCOUNT_B = "acc-b"
        const val INBOX = "inbox"
        const val ARCHIVE = "archive"
        val REMEMBERED = listOf(Sel.Folder(INBOX), Sel.Unified, Sel.Unread(allAccounts = false), Sel.Unread(allAccounts = true), null)
        val STORED_VALUES = listOf(OPEN_ON_LAST, OPEN_ON_INBOX, OPEN_ON_UNIFIED, null, "", "first-unread")
    }
}
