package app.sterna.core.data.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The store-version decision `SyncStateStore` runs at construction, executed. */
class SyncCursorVersionTest {

    @Test fun cursorsWrittenBeforeLabelsAreDropped() {
        // A delta from a v3 cursor only re-reads what changed since: a message labelled before the
        // update would keep an empty `labelsJson` until something else about it moved (#274).
        assertTrue("a v3 cursor must be dropped once so labelled rows are read in full", cursorsOutdated(3))
        assertTrue(cursorsOutdated(2))
        assertTrue("a store never versioned reads as 1", cursorsOutdated(1))
    }

    @Test fun cursorsOfTheCurrentVersionAreKept() {
        assertFalse("dropping current cursors would make every start a full re-query of every folder", cursorsOutdated(4))
    }
}
