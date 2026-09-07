package app.sterna.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The gate's answer, RUN — the half of [UnreadWidgetPresence] that needs no Android.
 *
 * WHY THIS FILE EXISTS. The seed used to read `UnreadWidgetDraw.placedIds(context).isNotEmpty()`
 * inline, nothing in the suite referenced [UnreadWidgetPresence] at all, and the wiring lint next
 * door pins that the seed is CALLED, never what it answers. Inverting that one call to `isEmpty()`
 * left the whole suite green — and it breaks the app in both directions at once:
 *
 *  - a user who HAS placed a cell: the gate stays shut for the life of the process, the collection
 *    never starts, and the number stops following the mail. That is the very defect the push pane
 *    was written to close;
 *  - a user who has NOT: two global Room aggregates subscribed for the life of every process, for
 *    a widget that does not exist. That expense is the reason the gate exists.
 *
 * Neither shows on screen for the user who is not looking, and neither would have reddened anything.
 */
class UnreadWidgetPresenceTest {

    /** No cell placed — the answer for the large majority of installs, and the expensive one to
     *  get wrong. */
    @Test fun `no cell placed means no widget`() {
        assertEquals(
            "an empty id array is the widget manager saying nobody placed this widget. Answering " +
                "true here subscribes two global Room aggregates for the life of every process on " +
                "earth, which is exactly what the gate refuses.",
            false,
            anyWidgetPlaced(intArrayOf()),
        )
    }

    /** One cell is enough: the gate asks whether ANY exists, never how many. */
    @Test fun `one cell placed means a widget`() {
        assertEquals(
            "a single id is a placed cell. Answering false here leaves the gate shut for the life " +
                "of the process: the count is never collected and the cell sits on a stale number " +
                "while the drawer beside it decrements.",
            true,
            anyWidgetPlaced(intArrayOf(7)),
        )
    }

    @Test fun `several cells are still just a widget`() {
        assertEquals(true, anyWidgetPlaced(intArrayOf(7, 9, 11)))
    }

    /**
     * The callbacks' end. `onEnabled` fires for the FIRST cell and `onDisabled` for the LAST one
     * going, and the collection follows this flow without a restart — so what they write has to
     * arrive, and it has to arrive the right way round.
     */
    @Test fun `the system's callbacks move the live answer, both ways`() {
        UnreadWidgetPresence.set(true)
        assertEquals(
            "onEnabled must open the gate on the running process: the cell was placed long after " +
                "the seed was read, and nothing else will tell the app about it.",
            true,
            UnreadWidgetPresence.live.value,
        )

        UnreadWidgetPresence.set(false)
        assertEquals(
            "onDisabled must close it again. Left open, the app goes on counting unread mail for " +
                "a widget the user removed, for as long as the process lives.",
            false,
            UnreadWidgetPresence.live.value,
        )
    }
}
