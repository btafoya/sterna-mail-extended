package app.sterna.push

import app.sterna.core.data.account.SyncWindow
import org.junit.Assert.assertEquals
import org.junit.Test

class PullWindowTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test fun `an age window prunes before its age and keeps its limit as the floor`() {
        assertEquals(200 to now - 30 * day, pullWindowArgs(SyncWindow.DAYS_30, now))
        assertEquals(500 to now - 365 * day, pullWindowArgs(SyncWindow.YEAR_1, now))
    }

    @Test fun `a count window prunes nothing by age`() {
        assertEquals(100 to null, pullWindowArgs(SyncWindow.COUNT_100, now))
    }
}
