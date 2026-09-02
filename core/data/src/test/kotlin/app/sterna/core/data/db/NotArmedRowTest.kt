package app.sterna.core.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The sentinel a row carries when its delivery could not be armed — the same pinning, for the same
 * reason, as `EditInterruptedRowTest` does for [OutboxLogic.EDIT_INTERRUPTED].
 */
class NotArmedRowTest {

    /**
     * ⛔ The sentinel is STORED, in every user's database, from the first version that ships it. It
     * is pinned as a literal so that changing the constant is seen for what it is: every row
     * already parked with the old token would stop being translated and print it raw on screen, in
     * nine languages, under a message that is merely waiting.
     */
    @Test fun `the sentinel is a fixed token, never a translated sentence`() {
        assertEquals("not-armed", OutboxLogic.NOT_ARMED)
    }

    /**
     * ⛔ And it is its own token. Reusing [OutboxLogic.EDIT_INTERRUPTED] would make an unarmed row
     * say "editing was interrupted, not sent" — a claim about a composer that was never opened.
     */
    @Test fun `the two sentinels are distinct tokens`() {
        assertNotEquals(OutboxLogic.EDIT_INTERRUPTED, OutboxLogic.NOT_ARMED)
    }

    /**
     * ⛔ And the HISTORIC opening, pinned by hand for the same reason and not one derived from it:
     * 1.5.2 and 1.5.3 wrote `unarmedNote`'s whole English sentence onto the row, those rows are in
     * users' databases now, and nothing rewrites them. This constant is how the screen recognises
     * them. Reworded — even slightly — the decoding stops matching and those rows go back to
     * printing English under a translated "Waiting to send", with no test to say so: every other
     * check builds its input FROM this constant, so it follows the mutation instead of catching it.
     */
    @Test fun `the historic opening of an unarmed row is frozen, word for word`() {
        assertEquals(
            "Queued, but delivery could not be armed:",
            OutboxLogic.NOT_ARMED_LEGACY_PREFIX,
        )
    }
}
