package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [realignedInboxId] run: when a refresh moves the account's stored inbox onto the `inbox` role. */
class RealignedInboxIdTest {

    @Test fun `a refresh of a stored inbox the role no longer names moves it onto the role`() {
        assertEquals("INBOX", realignedInboxId("XYZ/INBOX", "XYZ/INBOX", "INBOX"))
    }

    @Test fun `a folder opened from the drawer never sends the reader back to the inbox`() {
        assertNull(realignedInboxId("Archives", "XYZ/INBOX", "INBOX"))
    }

    /** Once the stored inbox is the role, the decision stops: no second refresh, no loop. */
    @Test fun `a stored inbox already on the role stays`() {
        assertNull(realignedInboxId("INBOX", "INBOX", "INBOX"))
    }

    @Test fun `no inbox role in the cache leaves the stored inbox alone`() {
        assertNull(realignedInboxId("XYZ/INBOX", "XYZ/INBOX", null))
    }

    /** A new account's first refresh asks for no folder: its own path stores what came back. */
    @Test fun `a refresh that asked for no folder is not realigned`() {
        assertNull(realignedInboxId(null, "XYZ/INBOX", "INBOX"))
        assertNull(realignedInboxId(null, null, "INBOX"))
    }
}
