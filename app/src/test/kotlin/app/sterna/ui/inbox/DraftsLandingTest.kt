package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DraftsLandingTest {

    private val inbox = Mailbox(id = "INBOX", name = "Inbox", role = "inbox")
    private val sent = Mailbox(id = "Sent", name = "Sent", role = "sent")
    private val drafts = Mailbox(id = "Drafts", name = "Drafts", role = "drafts")
    private val request = DraftsLandingRequest("acc-row", "acc-current")

    @Test fun `lands on the Drafts folder of the requested account`() {
        assertEquals(
            DraftsLanding.Folder(drafts),
            draftsLanding(request, "acc-row", listOf(inbox, sent, drafts)),
        )
    }

    @Test fun `waits while the list still shows the account it showed when asked`() {
        assertEquals(
            "the folder list in hand is the other account's: its Drafts would not hold the message",
            DraftsLanding.Wait,
            draftsLanding(request, "acc-current", listOf(inbox, drafts)),
        )
    }

    @Test fun `a list under a third account spends the request`() {
        assertEquals(
            "kept, it would fire the day she goes to the requested account herself",
            DraftsLanding.Plain,
            draftsLanding(request, "acc-other", listOf(inbox, drafts)),
        )
    }

    @Test fun `an empty first list of the requested account spends the request`() {
        assertEquals(
            "kept, it would select Drafts whenever the folders arrive, long after she has moved on",
            DraftsLanding.Plain,
            draftsLanding(request, "acc-row", emptyList()),
        )
    }

    @Test fun `no Drafts folder spends the request and leaves the list`() {
        assertEquals(DraftsLanding.Plain, draftsLanding(request, "acc-row", listOf(inbox, sent)))
    }

    @Test fun `nothing requested, nothing done`() {
        assertEquals(DraftsLanding.Wait, draftsLanding(null, "acc-row", listOf(inbox, drafts)))
    }

    @Test fun `a landing is parked only when the switch to its account took`() {
        assertEquals(
            DraftsLandingRequest("acc-row", "acc-current"),
            draftsLandingRequest("acc-row", "acc-row", "acc-current"),
        )
        assertNull(
            "a refused switch: parked, it would fire the day she goes to that account herself",
            draftsLandingRequest("acc-row", "acc-current", "acc-current"),
        )
        assertNull(draftsLandingRequest("acc-row", null, "acc-current"))
    }
}
