package app.sterna.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The gate's answer, run, the half of [UnreadWidgetPresence] that needs no Android.
 */
class UnreadWidgetPresenceTest {

    /** No cell placed, the answer for the large majority of installs, and the expensive one to
     *  get wrong. */
    @Test fun `no cell placed means no widget`() {
        assertEquals(
            "an empty id array is the widget manager saying nobody placed this widget. Answering " +
                "true here subscribes a global Room query for the life of every process on " +
                "earth, which is exactly what the gate refuses.",
            false,
            anyWidgetPlaced(intArrayOf()),
        )
    }

    /** One cell is enough: the gate asks whether any exists, never how many. */
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
     * The callbacks' end. `onEnabled` fires for the first cell and `onDisabled` for the last one
     */
    @Test fun `the system's callbacks move the live answer, both ways`() {
        UnreadWidgetKind.entries.forEach { kind ->
            UnreadWidgetPresence.set(kind, true)
            assertEquals(
                "onEnabled must open the gate on the running process: the cell was placed long after " +
                    "the seed was read, and nothing else will tell the app about it ($kind).",
                true,
                UnreadWidgetPresence.live(kind).value,
            )

            UnreadWidgetPresence.set(kind, false)
            assertEquals(
                "onDisabled must close it again. Left open, the app goes on counting unread mail for " +
                    "a widget the user removed, for as long as the process lives ($kind).",
                false,
                UnreadWidgetPresence.live(kind).value,
            )
        }
    }

    @Test fun `placing one counter does not open the other's gate`() {
        UnreadWidgetPresence.set(UnreadWidgetKind.Inboxes, false)
        UnreadWidgetPresence.set(UnreadWidgetKind.EveryFolder, false)

        UnreadWidgetPresence.set(UnreadWidgetKind.EveryFolder, true)

        assertEquals(
            "only the every-folder counter was placed. The inboxes' gate opening with it " +
                "subscribes a Room query for a cell nobody placed.",
            mapOf(UnreadWidgetKind.Inboxes to false, UnreadWidgetKind.EveryFolder to true),
            UnreadWidgetKind.entries.associateWith { UnreadWidgetPresence.live(it).value },
        )
        UnreadWidgetPresence.set(UnreadWidgetKind.EveryFolder, false)
    }

    @Test fun `removing the last cell of one counter leaves the other's gate open`() {
        UnreadWidgetPresence.set(UnreadWidgetKind.Inboxes, true)
        UnreadWidgetPresence.set(UnreadWidgetKind.EveryFolder, true)

        UnreadWidgetPresence.set(UnreadWidgetKind.Inboxes, false)

        assertEquals(
            "the every-folder counter still has a cell placed. Its gate closing with the other's " +
                "drops its subscription and its number stops following the mail, silently.",
            mapOf(UnreadWidgetKind.Inboxes to false, UnreadWidgetKind.EveryFolder to true),
            UnreadWidgetKind.entries.associateWith { UnreadWidgetPresence.live(it).value },
        )

        UnreadWidgetPresence.set(UnreadWidgetKind.Inboxes, true)
        UnreadWidgetPresence.set(UnreadWidgetKind.EveryFolder, false)

        assertEquals(
            "and the other way round.",
            mapOf(UnreadWidgetKind.Inboxes to true, UnreadWidgetKind.EveryFolder to false),
            UnreadWidgetKind.entries.associateWith { UnreadWidgetPresence.live(it).value },
        )
        UnreadWidgetPresence.set(UnreadWidgetKind.Inboxes, false)
    }
}
