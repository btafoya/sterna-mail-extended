package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ReadingPaneRule.forList]: an anchor's `index` and `src` point into the list it was tapped in.
 * The scenario is a reader on the 3rd message of Sent, the process killed, "Open on" = Inbox: the
 * route comes back, the list behind it is a fresh `InboxViewModel` showing the inbox, and an index
 * kept would open, mark read and offer a receipt for the inbox's 3rd message.
 */
class AnchorBeforeListTest {

    private val identity: (String) -> String = { it }
    private val sentTap = MessageAnchor("m3", "acc-a", "list", 2, null, listIdentity = "vm-before")
    private val searchTap = MessageAnchor("m3", "acc-a", "search", 2, null, listIdentity = "vm-before")

    @Test fun `a list anchor before another list opens alone, by its id`() {
        assertEquals(
            "a list anchor arriving before a list other than its own must lose its index and its " +
                "source: kept, the reader pages over the new list and opens whatever sits at index 2",
            MessageAnchor("m3", "acc-a", null, 0, null),
            ReadingPaneRule.forList(sentTap, listIdentity = "vm-after"),
        )
    }

    @Test fun `a search anchor before another list opens alone, by its id`() {
        assertEquals(
            "search results live in the same ViewModel: a fresh one holds none of them, so the " +
                "anchor must come back a lone message",
            MessageAnchor("m3", "acc-a", null, 0, null),
            ReadingPaneRule.forList(searchTap, listIdentity = "vm-after"),
        )
    }

    @Test fun `the answer for another list is the restored anchor itself`() {
        assertEquals(
            "the repli is ReadingPaneRule.restored, the one rule for an anchor that outlived its list",
            ReadingPaneRule.restored(sentTap),
            ReadingPaneRule.forList(sentTap, listIdentity = "vm-after"),
        )
    }

    @Test fun `a list anchor before its own list keeps its paging context`() {
        assertEquals(
            "a rotation keeps the ViewModel, hence the list and the pager: index 2 of that list " +
                "is the message tapped and must stay",
            sentTap,
            ReadingPaneRule.forList(sentTap, listIdentity = "vm-before"),
        )
        assertEquals(
            "same for a search result read before the ViewModel that ran the search",
            searchTap,
            ReadingPaneRule.forList(searchTap, listIdentity = "vm-before"),
        )
    }

    @Test fun `a list anchor that carries no identity is treated as another list`() {
        assertEquals(
            "a route without the identity was written by a build that did not carry it, restored " +
                "across an update: nothing says its index points into this list, and trusting it " +
                "opens and marks read a message nobody chose",
            MessageAnchor("m3", "acc-a", null, 0, null),
            ReadingPaneRule.forList(sentTap.copy(listIdentity = null), listIdentity = "vm-after"),
        )
    }

    @Test fun `a conversation anchor and a lone message pass untouched`() {
        val thread = MessageAnchor("m2", "acc-b", "thread", 1, "acc-b|t9")
        assertEquals(
            "src=thread pages over the conversation found by its key, not by a list position: " +
                "the rule leaves it alone",
            thread,
            ReadingPaneRule.forList(thread, listIdentity = "vm-after"),
        )
        val lone = MessageAnchor("m1", "acc-a", null, 0, null)
        assertEquals("a lone message has no paging context to lose", lone, ReadingPaneRule.forList(lone, "vm-after"))
    }

    @Test fun `a restored anchor forgets the list it came from`() {
        assertNull(
            "a lone message belongs to no list; an identity left on it would travel on",
            ReadingPaneRule.restored(sentTap).listIdentity,
        )
    }

    @Test fun `the identity travels in list and search routes and reads back`() {
        assertEquals(
            "message/m3?accountId=acc-a&index=2&src=list&list=vm-before",
            sentTap.toRoute(identity),
        )
        assertEquals(
            "message/m3?accountId=acc-a&index=2&src=search&list=vm-before",
            searchTap.toRoute(identity),
        )
        assertEquals(
            "the identity goes through the encoder like every other argument",
            "message/[m3]?accountId=[acc-a]&index=2&src=list&list=[vm-before]",
            sentTap.toRoute { "[$it]" },
        )
        for (anchor in listOf(sentTap, searchTap)) {
            val read = MessageAnchor.fromRoute(
                anchor.emailId, anchor.accountId, anchor.index, anchor.src, anchor.thread, anchor.listIdentity,
            )
            assertEquals("read back from the route's arguments, the anchor is the one written", anchor, read)
        }
        assertNull("list=\"\" is how the route says 'no list'", MessageAnchor.fromRoute("m1", "a", 0, "list", null, "").listIdentity)
    }

    @Test fun `the thread route keeps its form, identity or not`() {
        assertEquals(
            "message/m1?accountId=acc-a&index=1&src=thread&thread=acc-a|t9",
            MessageAnchor("m1", "acc-a", "thread", 1, "acc-a|t9", listIdentity = "vm").toRoute(identity),
        )
    }
}
