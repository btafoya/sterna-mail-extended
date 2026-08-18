package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [refreshedInboxId] RUN: which mailbox the "unread" view's refresh may fire the unarchive-on-reply
 * hook (#50) for.
 *
 * The hook WRITES — `FetchAndNotify.onInboxRefreshed` advances a folder's notification baseline and
 * re-files threads — so pointing it at the wrong folder is not a cosmetic error: either that
 * folder's new mail is never announced, or archived threads move without anyone asking. The pick is
 * a function of its own precisely so this test can execute it; `UnreadRefreshWiringLintTest` is the
 * link saying the refresh asks it, with the refreshed folders and the cached inbox id.
 */
class RefreshedInboxIdTest {

    /** The multi-folder pass, as it comes back when the inbox was part of it. */
    private val refreshed = listOf("INBOX", "Archive", "Projets")

    @Test fun `the inbox is picked out of the folders that came back`() {
        assertEquals("INBOX", refreshedInboxId(refreshed, "INBOX"))
        assertEquals("Archive", refreshedInboxId(refreshed, "Archive"))
    }

    /**
     * ⛔ THE one. Taking the first refresh trusts a position produced in another module: here the
     * inbox is simply not in what came back (an IMAP pass where the inbox load was the one refused,
     * or a folder list where it is not first), and the hook must fire for nothing rather than write
     * `Archive`'s baseline and re-file `Archive`'s threads.
     */
    @Test fun `a first folder that is not the inbox is not mistaken for it`() {
        assertNull(refreshedInboxId(listOf("Archive", "Projets"), "INBOX"))
        assertNull(refreshedInboxId(emptyList(), "INBOX"))
    }

    /** No cached inbox id (an account never synced): nothing is known to be the inbox. */
    @Test fun `an unknown inbox fires the hook for nothing`() {
        assertNull(refreshedInboxId(refreshed, null))
        assertNull(refreshedInboxId(emptyList(), null))
    }
}
