package app.sterna.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

/**
 * The two widgets' presence gates, RUN side by side — the half of them that needs no Android.
 *
 * ⛔ WHY THIS FILE EXISTS. `UnreadWidgetPresence` is a process-global boolean, seeded from the ids
 * of `UnreadWidgetProvider` alone, and `UnreadWidgetPush` gates its whole collection on it. Pointing
 * the latest-messages provider's `onEnabled`/`onDisabled` at it — the shortest way to write this
 * pane, and the one that compiles — breaks the OTHER widget: remove the last latest-messages cell
 * and the counter's gate closes, its subscription is dropped, and a counter cell still sitting on
 * the home screen quietly stops following the mail. Nothing on screen says so, and no user could
 * connect the two.
 */
class RecentMailWidgetPresenceTest {

    /** Two objects, two flows. Sameness here is the defect itself, before any value is read. */
    @Test fun `the two widgets do not share one state`() {
        assertNotSame(
            "the latest-messages gate must be its own StateFlow. Shared, one widget's callbacks " +
                "answer for the other's.",
            UnreadWidgetPresence.live,
            RecentMailWidgetPresence.live,
        )
    }

    @Test fun `the latest-messages callbacks move its own answer, both ways`() {
        RecentMailWidgetPresence.set(true)
        assertEquals(true, RecentMailWidgetPresence.live.value)
        RecentMailWidgetPresence.set(false)
        assertEquals(false, RecentMailWidgetPresence.live.value)
    }

    /**
     * ⛔ The one that matters: the last latest-messages cell going must leave the COUNTER's gate
     * exactly as it was. A counter cell is still on the home screen.
     */
    @Test fun `removing the last latest-messages cell leaves the counter's gate open`() {
        UnreadWidgetPresence.set(true)
        RecentMailWidgetPresence.set(true)

        RecentMailWidgetPresence.set(false)

        assertEquals(
            "the counter still has a cell placed. Its gate closing here drops the unread " +
                "subscription and the number stops following the mail, silently.",
            true,
            UnreadWidgetPresence.live.value,
        )
        assertEquals(false, RecentMailWidgetPresence.live.value)
        UnreadWidgetPresence.set(false)
    }

    /** And the other direction: the counter's last cell going says nothing about this widget. */
    @Test fun `removing the last counter cell leaves the latest-messages gate open`() {
        UnreadWidgetPresence.set(true)
        RecentMailWidgetPresence.set(true)

        UnreadWidgetPresence.set(false)

        assertEquals(true, RecentMailWidgetPresence.live.value)
        RecentMailWidgetPresence.set(false)
    }
}
