package app.sterna.core.jmap

import app.sterna.core.jmap.model.JmapSession
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How `uploadBlob` reads a refused upload (#242): the server's own size limit becomes a
 * [ContentTooLargeException], anything else stays a [JmapException] carrying its status, and no
 * text from the response body ever reaches an exception message.
 */
class UploadRefusalOnTheWireTest {

    private val auth = BasicAuth("alex@masto.top", "secret")
    private val detail = "DETAIL-FROM-THE-SERVER-must-not-leak"

    private fun problem(limit: String): MockResponse = MockResponse()
        .setResponseCode(400)
        .setHeader("Content-Type", "application/problem+json")
        .setBody(
            """{"type":"urn:ietf:params:jmap:error:limit","status":400,"detail":"$detail","limit":"$limit"}""",
        )

    private fun uploadAgainst(response: MockResponse): Throwable {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(response)
            val session = JmapSession(
                apiUrl = server.url("/jmap/api/").toString(),
                uploadUrl = server.url("/jmap/upload/{accountId}/").toString(),
            )
            val result = runCatching {
                runBlocking { JmapClient().uploadBlob(session, "acc1", ByteArray(2048), "application/pdf", auth) }
            }
            assertEquals("a refused upload is not retried", 1, server.requestCount)
            return result.exceptionOrNull() ?: throw AssertionError("the upload should have failed")
        } finally {
            server.shutdown()
        }
    }

    @Test fun `the server's upload size limit becomes ContentTooLargeException without its detail`() {
        val e = uploadAgainst(problem("maxSizeUpload"))
        assertTrue("expected ContentTooLargeException, got $e", e is ContentTooLargeException)
        e as ContentTooLargeException
        assertEquals(2048L, e.bytes)
        assertEquals(-1L, e.maxBytes)
        assertFalse(e.message.orEmpty().contains(detail))
    }

    @Test fun `a bare 413 stays a JmapException carrying its status`() {
        val e = uploadAgainst(MockResponse().setResponseCode(413))
        assertTrue("expected JmapException, got $e", e is JmapException)
        assertEquals(413, (e as JmapException).httpCode)
    }

    @Test fun `another limit is not a size refusal and does not leak its detail`() {
        val e = uploadAgainst(problem("maxConcurrentUpload"))
        assertTrue("expected JmapException, got $e", e is JmapException)
        assertEquals(400, (e as JmapException).httpCode)
        assertFalse(e.message.orEmpty().contains(detail))
    }

    @Test fun `a body that is not JSON stays a JmapException carrying its status`() {
        val e = uploadAgainst(MockResponse().setResponseCode(400).setBody("<html>$detail</html>"))
        assertTrue("expected JmapException, got $e", e is JmapException)
        assertEquals(400, (e as JmapException).httpCode)
        assertFalse(e.message.orEmpty().contains(detail))
    }

    @Test fun `a JSON body that is not an object stays a JmapException`() {
        val e = uploadAgainst(MockResponse().setResponseCode(400).setBody("""["maxSizeUpload"]"""))
        assertTrue("expected JmapException, got $e", e is JmapException)
        assertEquals(400, (e as JmapException).httpCode)
    }
}
