package app.sterna.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The drawing each list's factory falls back on when its read times out, one per kind, executed.
 */
class RecentMailWidgetHeldDrawingTest {

    private val inboxes = RecentWidgetDrawing(RecentWidgetSnapshot(RecentWidgetCell.EmptyInbox, emptyList()), colours = null, tapExtras = emptyMap())
    private val unread = RecentWidgetDrawing(RecentWidgetSnapshot(RecentWidgetCell.NothingToSay, emptyList()), colours = null, tapExtras = emptyMap())

    @Test fun `a read of one list is never what the other list falls back on`() {
        val held = HeldDrawings()
        held.hold(RecentWidgetKind.Inboxes, inboxes)
        assertEquals(
            "the unread list must hold nothing while only the inboxes' list has been read. Shared, " +
                "an unread cell whose read times out serves the inboxes' rows, read ones included, " +
                "under a title that says they are unread.",
            mapOf(RecentWidgetKind.Inboxes to inboxes, RecentWidgetKind.Unread to null),
            RecentWidgetKind.entries.associateWith { held[it] },
        )
        held.hold(RecentWidgetKind.Unread, unread)
        assertEquals(
            mapOf(RecentWidgetKind.Inboxes to inboxes, RecentWidgetKind.Unread to unread),
            RecentWidgetKind.entries.associateWith { held[it] },
        )
    }

    @Test fun `forgetting one list leaves the other's drawing`() {
        val held = HeldDrawings()
        held.hold(RecentWidgetKind.Inboxes, inboxes)
        held.hold(RecentWidgetKind.Unread, unread)
        held.forget(RecentWidgetKind.Unread)
        assertEquals(
            mapOf(RecentWidgetKind.Inboxes to inboxes, RecentWidgetKind.Unread to null),
            RecentWidgetKind.entries.associateWith { held[it] },
        )
    }
}
