package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * EXECUTES [rawSourceFromCache] — the decision that decides whether the raw-source cache is asked
 * at all. Not a source lint: the function is pure, so the rule is run against its own inputs and
 * inverting it cannot stay green (#189).
 *
 * ⛔ What it is here to hold. `MailRepository.fetchRawSource` answered from a four-entry LRU keyed
 * `accountId:emailId` BEFORE any SELECT. A cross-account move that freezes the numbering of the
 * tick therefore had its refusal short-circuited on the read side whenever the message had just
 * been opened — which is the ordinary case, since the user reads a message and then moves it. The
 * octets went to the other account under an APPEND that is never replayed, and only the Trash half
 * refused: a permanent duplicate on B, with no Undo on this path.
 */
class RawSourceFromCacheTest {

    /** A cache lookup that counts how many times it was actually performed. */
    private class Lookup(private val answer: String?) : () -> String? {
        var reads = 0
        override fun invoke(): String? {
            reads++
            return answer
        }
    }

    /**
     * ⭐ THE CASE THAT COSTS THE DUPLICATE: a caller carrying a frozen numbering. The cache holds
     * no numbering at all, so it cannot answer for one — and the read must reach the server, where
     * the SELECT opposes the number and refuses.
     */
    @Test fun `a stated numbering skips the cache, and does not even look`() {
        val lookup = Lookup("Subject: whoever holds that uid now\r\n")

        assertNull(
            "a caller that froze a numbering must be sent to the server: the cache is keyed by " +
                "account and message id and holds NO numbering, so a hit answers 'the octets we " +
                "last read for that text' — which is precisely what a renumbered folder makes a " +
                "different message",
            rawSourceFromCache(42L) { lookup() },
        )
        assertEquals(
            "and it must not be CONSULTED either: consulting and discarding would still hand the " +
                "answer to any future caller written to use it, and the whole cost of this " +
                "decision is that the lookup does not happen",
            0, lookup.reads,
        )
    }

    /** Any stated number, not only the one this test picked — including the two edge values a
     *  numbering can carry on the wire. */
    @Test fun `every stated numbering skips the cache`() {
        listOf(1L, 42L, 0L, Long.MAX_VALUE).forEach { stated ->
            val lookup = Lookup("cached")
            assertNull("a numbering of $stated is still a numbering the cache cannot answer for", rawSourceFromCache(stated) { lookup() })
            assertEquals(0, lookup.reads)
        }
    }

    /**
     * ⛔ THE INVERSE WITNESS, without which "never answer from the cache" satisfies the case above
     * and is a total regression: every ordinary read — opening a message, viewing its headers,
     * saving it as `.eml`, retrying an OpenPGP interaction — would refetch the whole message from
     * the server, in the reader, on a mobile link.
     */
    @Test fun `no stated numbering answers from the cache, exactly as before`() {
        val lookup = Lookup("Subject: the message that was read\r\n")

        assertEquals(
            "an ordinary read carries nothing frozen and must be served by the cache",
            "Subject: the message that was read\r\n",
            rawSourceFromCache(null) { lookup() },
        )
        assertEquals("and it must be asked exactly once", 1, lookup.reads)
    }

    /** A miss is a miss: the caller fetches. The decision reports what the cache said, and does
     *  not turn an absent entry into anything else. */
    @Test fun `a miss with nothing frozen is a miss, and the lookup did happen`() {
        val lookup = Lookup(null)

        assertNull(rawSourceFromCache(null) { lookup() })
        assertEquals(1, lookup.reads)
    }
}
