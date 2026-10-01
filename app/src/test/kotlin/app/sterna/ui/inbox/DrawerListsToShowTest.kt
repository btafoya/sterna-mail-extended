package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test

/** [drawerListsToShow], executed: the drawer's call site is a Composable nothing here can build. */
class DrawerListsToShowTest {

    @Test fun `nothing scheduled, snoozed or waiting draws none of the three`() {
        assertEquals(emptyList<DrawerList>(), drawerListsToShow(scheduled = 0, snoozed = 0, outbox = 0))
    }

    @Test fun `each list is drawn on its own count alone`() {
        assertEquals(listOf(DrawerList.SCHEDULED), drawerListsToShow(scheduled = 2, snoozed = 0, outbox = 0))
        assertEquals(listOf(DrawerList.SNOOZED), drawerListsToShow(scheduled = 0, snoozed = 1, outbox = 0))
        assertEquals(listOf(DrawerList.OUTBOX), drawerListsToShow(scheduled = 0, snoozed = 0, outbox = 3))
    }

    @Test fun `a negative count draws nothing`() {
        assertEquals(emptyList<DrawerList>(), drawerListsToShow(scheduled = -1, snoozed = -1, outbox = -1))
    }

    @Test fun `all three are drawn as Scheduled, Snoozed, Outbox`() {
        assertEquals(
            listOf(DrawerList.SCHEDULED, DrawerList.SNOOZED, DrawerList.OUTBOX),
            drawerListsToShow(scheduled = 1, snoozed = 1, outbox = 1),
        )
        assertEquals(
            listOf(DrawerList.SCHEDULED, DrawerList.OUTBOX),
            drawerListsToShow(scheduled = 1, snoozed = 0, outbox = 1),
        )
        assertEquals(
            listOf(DrawerList.SNOOZED, DrawerList.OUTBOX),
            drawerListsToShow(scheduled = 0, snoozed = 1, outbox = 1),
        )
    }
}
