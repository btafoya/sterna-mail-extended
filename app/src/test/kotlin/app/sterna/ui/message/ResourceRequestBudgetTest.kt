package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * [RequestBudget] executed: the count of one document's resource requests, the single report of the
 * limit crossed, and the reset that gives the next document its own count.
 */
class ResourceRequestBudgetTest {

    private val images = InlineImages(
        mapOf("a" to "data:image/png;base64," + Base64.getEncoder().encodeToString(ByteArray(8) { it.toByte() })),
    )

    private fun RequestBudget.cid() = admit("cid", "cid:a", blockRemote = true, images = images)

    @Test fun `the limit is a thousand requests per document`() {
        assertEquals(1_000, MAX_RESOURCE_REQUESTS_PER_DOCUMENT)
    }

    @Test fun `the request past the limit is reported, once, and every one after it is blocked unread`() {
        val budget = RequestBudget()
        repeat(MAX_RESOURCE_REQUESTS_PER_DOCUMENT) { i ->
            val r = budget.cid()
            assertFalse("request ${i + 1} is within the limit and must not be reported", r.crossed)
            assertTrue(
                "request ${i + 1} is within the limit: the cid must be served from the map, got ${r.decision}",
                r.decision is ResourceDecision.Serve,
            )
        }
        val crossing = budget.cid()
        assertTrue("request ${MAX_RESOURCE_REQUESTS_PER_DOCUMENT + 1} crosses the limit and must say so", crossing.crossed)
        assertSame(ResourceDecision.Block, crossing.decision)
        repeat(5_000) { i ->
            val r = budget.cid()
            assertFalse("the crossing is reported once per load, reported again at request +${i + 2}", r.crossed)
            assertSame(
                "past the limit the map must not be read: a known cid is served otherwise",
                ResourceDecision.Block,
                r.decision,
            )
        }
    }

    @Test fun `every scheme counts, not only cid`() {
        val budget = RequestBudget()
        repeat(MAX_RESOURCE_REQUESTS_PER_DOCUMENT) { budget.admit("https", "https://x.test/p.gif", true, images) }
        val r = budget.admit("data", "data:image/gif;base64,R0lGOD", blockRemote = true, images = images)
        assertTrue("remote and cid requests count toward the same limit", r.crossed)
        assertSame(ResourceDecision.Block, r.decision)
    }

    @Test fun `under the limit the ordinary decision is returned`() {
        val budget = RequestBudget()
        assertSame(ResourceDecision.Block, budget.admit("https", "https://x.test/p.gif", true, images).decision)
        assertSame(ResourceDecision.PassThrough, budget.admit("https", "https://x.test/p.gif", false, images).decision)
        assertSame(ResourceDecision.PassThrough, budget.admit("data", "data:,x", true, images).decision)
    }

    @Test fun `after a reset the next document starts from zero and its first image is served`() {
        val budget = RequestBudget()
        repeat(MAX_RESOURCE_REQUESTS_PER_DOCUMENT + 10) { budget.cid() }
        budget.reset()
        val first = budget.cid()
        assertFalse("an ordinary document must not be given up on at its first image", first.crossed)
        assertTrue("its first image must be served, got ${first.decision}", first.decision is ResourceDecision.Serve)
        repeat(MAX_RESOURCE_REQUESTS_PER_DOCUMENT - 1) { assertFalse(budget.cid().crossed) }
        assertTrue("the reset document gets its own report when it crosses in turn", budget.cid().crossed)
    }

    @Test fun `a load of the message the reader gave up on keeps the verdict`() {
        assertTrue(
            "the warmed neighbour already shows this message as text; clearing the verdict on its " +
                "load puts the HTML back and makes its thousand requests again",
            keepsResourceVerdict(MessagePaging.entryKey("m1", "acc"), "m1", "acc"),
        )
    }

    @Test fun `a load of another message clears the verdict`() {
        assertFalse(keepsResourceVerdict(MessagePaging.entryKey("m1", "acc"), "m2", "acc"))
    }

    @Test fun `the same id in another account clears the verdict`() {
        assertFalse(keepsResourceVerdict(MessagePaging.entryKey("m1", "acc"), "m1", "other"))
        assertFalse(keepsResourceVerdict(MessagePaging.entryKey("m1", "acc"), "m1", null))
    }

    @Test fun `no verdict is kept when none was given`() {
        assertFalse(keepsResourceVerdict(null, "m1", "acc"))
    }
}
