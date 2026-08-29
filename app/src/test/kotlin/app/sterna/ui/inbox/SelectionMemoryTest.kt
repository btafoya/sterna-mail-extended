package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Runs the drawer's memory of the current view — [encodeSelection] and [restoreSelection] — as the
 * pure functions they are, on the JVM, with LITERAL expectations.
 *
 * ⛔ Nothing here recomputes the rule it checks. A test that rebuilt the stored string from the
 * same pieces the code uses would stay green with the condition inverted; the encoded strings and
 * the restored selections below are written out by hand, character by character.
 *
 * The pattern being imitated — `AccountStore.currentId()` and its fallback — is executed by NO
 * test in this repo (no Robolectric, `SharedPreferences` is a framework type). That is exactly why
 * the DECISION was pulled out of the store and out of the ViewModel: the store now only carries an
 * opaque string, and everything that can be got wrong is in this file's reach.
 */
class SelectionMemoryTest {

    // -- what gets written ------------------------------------------------------------------------

    @Test
    fun `each of the three views encodes to its own string, account id first`() {
        assertEquals("acc-1\nf\nINBOX", encodeSelection("acc-1", Sel.Folder("INBOX")))
        assertEquals("acc-1\nu\n", encodeSelection("acc-1", Sel.Unified))
        assertEquals("acc-1\nn\n", encodeSelection("acc-1", Sel.Unread))
    }

    /**
     * The account id travels WITH the selection, never a bare folder id: two accounts on the same
     * server number their mailboxes independently, so "the folder called 5" is only a memory once
     * it says whose 5 it is (#92 / #121).
     */
    @Test
    fun `two accounts encode the same folder id to two different strings`() {
        assertEquals("acc-1\nf\n5", encodeSelection("acc-1", Sel.Folder("5")))
        assertEquals("acc-2\nf\n5", encodeSelection("acc-2", Sel.Folder("5")))
    }

    @Test
    fun `there is nothing to remember without an account`() {
        assertNull(encodeSelection(null, Sel.Folder("INBOX")))
        assertNull(encodeSelection(null, Sel.Unified))
        assertNull(encodeSelection(null, Sel.Unread))
    }

    /**
     * ⭐ An unknown folder id is not a memory. Storing `Folder(null)` would reopen on a list with no
     * folder instead of falling back on the Inbox as soon as its id IS known — the state the app is
     * in before the first sync of a fresh account.
     */
    @Test
    fun `a selection with no folder id is not remembered`() {
        assertNull(encodeSelection("acc-1", Sel.Folder(null)))
    }

    // -- what gets read back ----------------------------------------------------------------------

    @Test
    fun `the three views survive the round trip`() {
        assertEquals(Sel.Folder("INBOX"), restoreSelection(encodeSelection("acc-1", Sel.Folder("INBOX")), "acc-1", false))
        assertEquals(Sel.Unified, restoreSelection(encodeSelection("acc-1", Sel.Unified), "acc-1", true))
        assertEquals(Sel.Unread, restoreSelection(encodeSelection("acc-1", Sel.Unread), "acc-1", false))
    }

    /**
     * Only the separator is special: dots and slashes are ordinary characters in an IMAP mailbox
     * path and must come back untouched.
     */
    @Test
    fun `a real IMAP folder path survives the round trip intact`() {
        val stored = encodeSelection("acc-1", Sel.Folder("INBOX.Projets/2026"))
        assertEquals("acc-1\nf\nINBOX.Projets/2026", stored)
        assertEquals(Sel.Folder("INBOX.Projets/2026"), restoreSelection(stored, "acc-1", false))
    }

    /**
     * ⭐ A folder id CAN carry the separator, and the answer is to FORGET the view, not to escape
     * it. An IMAP path is decoded from modified UTF-7 with nothing filtered out of it
     * (`ImapFolder.path`), and a mailbox named with a line feed travels as `&AAo-` and decodes back
     * to a path holding U+000A (`ImapUtf7`) — so the string below is one the app writes ITSELF, not
     * a preference corrupted from elsewhere. Four parts, refused, and the launch lands on the
     * Inbox: a folder we decline to remember beats a folder we reopen wrong.
     *
     * ⛔ Not covered by `anything that is not three parts is refused`, which hands restoreSelection
     * strings written by hand and says nothing about what encodeSelection may produce. Adding an
     * escaping pair to both functions would keep every other test in this file green and change
     * only these two lines.
     */
    @Test
    fun `a folder id carrying the separator is refused, not escaped`() {
        val stored = encodeSelection("acc-1", Sel.Folder("INBOX.A\nB"))
        assertNull(
            "a remembered folder id holding the separator must be forgotten, not escaped and " +
                "reopened. Restored: ${restoreSelection(stored, "acc-1", true)}",
            restoreSelection(stored, "acc-1", true),
        )
        assertEquals("acc-1\nf\nINBOX.A\nB", stored)
    }

    @Test
    fun `nothing stored means the caller keeps today's behaviour`() {
        assertNull(restoreSelection(null, "acc-1", true))
        assertNull(restoreSelection("", "acc-1", true))
    }

    @Test
    fun `no current account means nothing to restore onto`() {
        assertNull(restoreSelection("acc-1\nf\nINBOX", null, true))
        assertNull(restoreSelection("acc-1\nu\n", null, true))
        assertNull(restoreSelection("acc-1\nn\n", null, true))
    }

    /**
     * ⭐ The central guard: the memory belongs to ONE account. Deleted account, account switched
     * between two launches, preferences restored from another install — every one of those lands
     * here, and without it the app opens a row of a NEIGHBOUR account under the current account's
     * header. All three forms, because a guard written on the folder arm alone still lets the
     * unified and unread views through.
     */
    @Test
    fun `a memory of another account is refused, in all three forms`() {
        assertNull(restoreSelection("acc-2\nf\nINBOX", "acc-1", true))
        assertNull(restoreSelection("acc-2\nu\n", "acc-1", true))
        assertNull(restoreSelection("acc-2\nn\n", "acc-1", true))
    }

    /**
     * ⭐ The unified view has no drawer entry on a single-account install (`InboxScreen` posts it
     * under `accounts.size > 1`): restored there, it would draw a list the reader cannot leave by
     * the way they entered it, under a title naming a view the app does not offer.
     * `selectionIsUnreachable` does NOT cover this — it only ever judges `Sel.Folder`.
     */
    @Test
    fun `the unified view is only restored where the app offers it`() {
        assertEquals(Sel.Unified, restoreSelection("acc-1\nu\n", "acc-1", true))
        assertNull(restoreSelection("acc-1\nu\n", "acc-1", false))
    }

    /** The per-account unread view means something on an install that has one account. */
    @Test
    fun `the unread view is restored whether or not a second account exists`() {
        assertEquals(Sel.Unread, restoreSelection("acc-1\nn\n", "acc-1", false))
        assertEquals(Sel.Unread, restoreSelection("acc-1\nn\n", "acc-1", true))
    }

    @Test
    fun `a folder memory with no folder id is refused`() {
        assertNull(restoreSelection("acc-1\nf\n", "acc-1", true))
    }

    @Test
    fun `anything that is not three parts is refused`() {
        assertNull(restoreSelection("acc-1\nf", "acc-1", true))
        assertNull(restoreSelection("acc-1", "acc-1", true))
        assertNull(restoreSelection("acc-1\nf\nINBOX\n", "acc-1", true))
        assertNull(restoreSelection("acc-1\nf\nINBOX\nextra", "acc-1", true))
    }

    @Test
    fun `an unknown form is refused`() {
        assertNull(restoreSelection("acc-1\nz\nINBOX", "acc-1", true))
        assertNull(restoreSelection("acc-1\n\nINBOX", "acc-1", true))
        assertNull(restoreSelection("hello", "acc-1", true))
    }
}
