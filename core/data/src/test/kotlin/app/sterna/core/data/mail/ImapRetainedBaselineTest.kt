package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An IMAP push pass reads the newest fifty of a folder, and `NewMailNotifier` replaces the stored
 * baseline with what the pass hands it. A busy inbox sorted from the top must not ring for old
 * mail: a message that slid to the fifty-first place comes back onto the page as soon as one above
 * it is deleted, and it must still be known then (#278).
 */
class ImapRetainedBaselineTest {

    private val folder = (1..60).map { "m$it" }
    private val m = "m51"

    @Test fun `a message past the page stays in the baseline while the cache holds it`() {
        val retained = imapRetainedBaseline(pageIds = folder.take(50), cachedIds = folder)

        assertTrue(
            "the 51st message fell out of the baseline: the next deletion pulls it back onto the " +
                "page and the notifier announces it as new. Retained: $retained",
            retained!!.contains(m),
        )
    }

    @Test fun `after a deletion at the top, nothing on the next page is unknown`() {
        val retained = imapRetainedBaseline(pageIds = folder.take(50), cachedIds = folder)!!
        val nextPage = folder.drop(1).take(50)

        assertEquals(
            "these ids would be announced as new mail after a mere deletion",
            emptyList<String>(),
            nextPage.filterNot { it in retained },
        )
    }

    @Test fun `mail that reached neither the page nor the cache is still unknown`() {
        val retained = imapRetainedBaseline(pageIds = folder.take(50), cachedIds = folder)!!

        assertTrue("an id nobody read entered the baseline", "n1" !in retained)
    }

    @Test fun `a page that cannot vouch for the folder still leaves the baseline alone`() {
        assertNull(
            "a null page means the folder's content is unknown; widening it by the cache would " +
                "overwrite the stored baseline with the cache alone",
            imapRetainedBaseline(pageIds = null, cachedIds = folder),
        )
    }

    @Test fun `each id is remembered once`() {
        val retained = imapRetainedBaseline(pageIds = folder.take(50), cachedIds = folder)!!

        assertEquals(retained.distinct(), retained)
    }
}
