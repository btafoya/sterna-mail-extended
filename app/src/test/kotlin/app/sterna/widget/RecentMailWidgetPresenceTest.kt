package app.sterna.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

/**
 * The two widgets' presence gates, run side by side, the half of them that needs no Android.
 */
class RecentMailWidgetPresenceTest {

    /** Two objects, two flows. Sameness here is the defect itself, before any value is read. */
    @Test fun `the two widgets do not share one state`() {
        assertNotSame(
            "the latest-messages gate must be its own StateFlow. Shared, one widget's callbacks " +
                "answer for the other's.",
            UnreadWidgetPresence.live(UnreadWidgetKind.Inboxes),
            RecentMailWidgetPresence.live(RecentWidgetKind.Inboxes),
        )
    }

    @Test fun `the latest-messages callbacks move its own answer, both ways`() {
        RecentMailWidgetPresence.set(RecentWidgetKind.Inboxes, true)
        assertEquals(true, RecentMailWidgetPresence.live(RecentWidgetKind.Inboxes).value)
        RecentMailWidgetPresence.set(RecentWidgetKind.Inboxes, false)
        assertEquals(false, RecentMailWidgetPresence.live(RecentWidgetKind.Inboxes).value)
    }

    /**
     * The one that matters: the last latest-messages cell going must leave the counter's gate
     * exactly as it was. A counter cell is still on the home screen.
     */
    @Test fun `removing the last latest-messages cell leaves the counter's gate open`() {
        UnreadWidgetPresence.set(UnreadWidgetKind.Inboxes, true)
        RecentMailWidgetPresence.set(RecentWidgetKind.Inboxes, true)

        RecentMailWidgetPresence.set(RecentWidgetKind.Inboxes, false)

        assertEquals(
            "the counter still has a cell placed. Its gate closing here drops the unread " +
                "subscription and the number stops following the mail, silently.",
            true,
            UnreadWidgetPresence.live(UnreadWidgetKind.Inboxes).value,
        )
        assertEquals(false, RecentMailWidgetPresence.live(RecentWidgetKind.Inboxes).value)
        UnreadWidgetPresence.set(UnreadWidgetKind.Inboxes, false)
    }

    /** And the other direction: the counter's last cell going says nothing about this widget. */
    @Test fun `removing the last counter cell leaves the latest-messages gate open`() {
        UnreadWidgetPresence.set(UnreadWidgetKind.Inboxes, true)
        RecentMailWidgetPresence.set(RecentWidgetKind.Inboxes, true)

        UnreadWidgetPresence.set(UnreadWidgetKind.Inboxes, false)

        assertEquals(true, RecentMailWidgetPresence.live(RecentWidgetKind.Inboxes).value)
        RecentMailWidgetPresence.set(RecentWidgetKind.Inboxes, false)
    }

    @Test fun `the two lists do not share one state`() {
        assertNotSame(
            "each list kind must have its own StateFlow. Shared, the last unread-list cell going " +
                "stops the inboxes' list from following the mail while it is still on the screen.",
            RecentMailWidgetPresence.live(RecentWidgetKind.Inboxes),
            RecentMailWidgetPresence.live(RecentWidgetKind.Unread),
        )
    }

    @Test fun `removing the last cell of one list leaves the other list's gate open, both ways`() {
        RecentMailWidgetPresence.set(RecentWidgetKind.Inboxes, true)
        RecentMailWidgetPresence.set(RecentWidgetKind.Unread, true)

        RecentMailWidgetPresence.set(RecentWidgetKind.Unread, false)
        val inboxesAfterUnreadLeft = RecentMailWidgetPresence.live(RecentWidgetKind.Inboxes).value

        RecentMailWidgetPresence.set(RecentWidgetKind.Unread, true)
        RecentMailWidgetPresence.set(RecentWidgetKind.Inboxes, false)
        val unreadAfterInboxesLeft = RecentMailWidgetPresence.live(RecentWidgetKind.Unread).value

        RecentMailWidgetPresence.set(RecentWidgetKind.Unread, false)
        assertEquals(
            "a list whose cell is still placed must keep its gate open when the other list's last " +
                "cell goes.",
            listOf(true, true),
            listOf(inboxesAfterUnreadLeft, unreadAfterInboxesLeft),
        )
    }
}
