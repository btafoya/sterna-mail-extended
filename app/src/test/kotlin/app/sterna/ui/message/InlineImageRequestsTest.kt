package app.sterna.ui.message

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * [decideResourceRequest] executed: what the body's WebView is answered for each request, the
 * `cid:` images included. `BlockingWebViewClient` only translates the decision into a response.
 */
class InlineImageRequestsTest {

    private val logo = ByteArray(300) { (it * 7).toByte() }
    private val map = mapOf(
        "logo" to "data:image/png;base64," + Base64.getEncoder().encodeToString(logo),
        "broken" to "data:image/png;base64,!!not base64!!",
        "unencoded" to "data:image/png,rawtext",
    )

    private fun decide(scheme: String?, url: String?, blockRemote: Boolean, images: InlineImages = InlineImages(map)) =
        decideResourceRequest(scheme, url, blockRemote, images)

    private fun assertServesLogo(d: ResourceDecision, what: String) {
        assertTrue("$what must be served from the map, got $d", d is ResourceDecision.Serve)
        d as ResourceDecision.Serve
        assertEquals("$what: the mime of the data URI", "image/png", d.mime)
        assertArrayEquals("$what: the decoded bytes of the image", logo, d.bytes)
    }

    @Test fun `both spellings of a cid reference are served, bracketed ones percent-encoded or not`() {
        for (blockRemote in listOf(true, false)) {
            assertServesLogo(decide("cid", "cid:logo", blockRemote), "cid:logo (blockRemote=$blockRemote)")
            assertServesLogo(decide("cid", "cid:<logo>", blockRemote), "cid:<logo> (blockRemote=$blockRemote)")
            assertServesLogo(decide("cid", "cid:%3Clogo%3E", blockRemote), "cid:%3Clogo%3E (blockRemote=$blockRemote)")
            assertServesLogo(decide("CID", "CID:%3clogo%3e", blockRemote), "CID:%3clogo%3e (blockRemote=$blockRemote)")
        }
    }

    @Test fun `a cid is served when remote images are shown too`() {
        assertServesLogo(
            decide("cid", "cid:logo", blockRemote = false),
            "with remote images allowed the WebView is let through for http, but a cid it cannot " +
                "resolve by itself",
        )
    }

    @Test fun `an unknown cid is a broken image, never let through, whatever blockRemote says`() {
        for (blockRemote in listOf(true, false)) {
            assertSame("unknown cid, blockRemote=$blockRemote", ResourceDecision.Block, decide("cid", "cid:nope", blockRemote))
            assertSame("empty cid, blockRemote=$blockRemote", ResourceDecision.Block, decide("cid", "cid:", blockRemote))
            assertSame("no url, blockRemote=$blockRemote", ResourceDecision.Block, decide("cid", null, blockRemote))
            assertSame(
                "empty map, blockRemote=$blockRemote",
                ResourceDecision.Block,
                decide("cid", "cid:logo", blockRemote, InlineImages.EMPTY),
            )
        }
    }

    @Test fun `a cid cut short in a percent escape is a broken image, not an exception`() {
        for (url in listOf("cid:%", "cid:%3", "cid:logo%", "cid:logo%4")) {
            assertEquals(url, ResourceDecision.Block, decide("cid", url, blockRemote = true))
        }
    }

    @Test fun `an unreadable data URI is a broken image, not an exception`() {
        for (blockRemote in listOf(true, false)) {
            assertSame("bad base64", ResourceDecision.Block, decide("cid", "cid:broken", blockRemote))
            assertSame("not base64-encoded", ResourceDecision.Block, decide("cid", "cid:unencoded", blockRemote))
        }
        val odd = InlineImages(mapOf("a" to "https://evil.test/x.gif", "b" to "data:;base64,AAAA", "c" to "data:image/png;base64"))
        for (cid in listOf("a", "b", "c")) {
            assertSame("value for $cid is not a readable data URI", ResourceDecision.Block, decide("cid", "cid:$cid", true, odd))
        }
    }

    @Test fun `each image is decoded once per map and served from the same bytes`() {
        val images = InlineImages(map)
        val first = decide("cid", "cid:logo", true, images) as ResourceDecision.Serve
        val second = decide("cid", "cid:<logo>", false, images) as ResourceDecision.Serve
        assertSame("the second request must reuse the first decode", first.bytes, second.bytes)
    }

    @Test fun `remote and schemeless requests are blocked while remote images are held back`() {
        for (scheme in listOf("http", "https", "HTTPS", "ws", "ftp", "", null)) {
            assertSame("scheme=$scheme", ResourceDecision.Block, decide(scheme, "$scheme://evil.test/x.gif", blockRemote = true))
        }
    }

    @Test fun `remote requests are let through once remote images are shown`() {
        for (scheme in listOf("http", "https", "", null)) {
            assertSame("scheme=$scheme", ResourceDecision.PassThrough, decide(scheme, "$scheme://evil.test/x.gif", blockRemote = false))
        }
    }

    @Test fun `data and about are let through either way`() {
        for (blockRemote in listOf(true, false)) {
            assertSame(ResourceDecision.PassThrough, decide("data", "data:image/png;base64,AAAA", blockRemote))
            assertSame(ResourceDecision.PassThrough, decide("about", "about:blank", blockRemote))
        }
    }
}
