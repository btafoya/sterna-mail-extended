package app.sterna.ui.message

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyFetchRingTest {

    @Test fun `a body still being fetched past the grace period shows the ring`() {
        assertTrue(
            "the header is on screen, the body is not here yet and 500 ms have passed: the body " +
                "area must say it is loading, or it stays blank until the fetch returns.",
            BodyFetchRing.shown(hasBody = false, spinnerDue = true),
        )
    }

    @Test fun `no ring inside the grace period`() {
        assertFalse(
            "within the first 500 ms a body read from the cache usually arrives: a ring here " +
                "would flash on every cached message.",
            BodyFetchRing.shown(hasBody = false, spinnerDue = false),
        )
    }

    @Test fun `a page that holds a body leaves the ring to the layout ring`() {
        assertFalse(
            "once the body is here the layout ring takes over at the same spot; answering true " +
                "draws a second ring over it.",
            BodyFetchRing.shown(hasBody = true, spinnerDue = true),
        )
        assertFalse(BodyFetchRing.shown(hasBody = true, spinnerDue = false))
    }
}
