package app.sterna.ui.inbox

import app.sterna.core.jmap.JmapException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SyncErrorTextTest {

    private val refused = "REFUSED-SENTENCE"

    @Test fun `a token refresh the server refused in OAuth words gets the add-again sentence`() {
        val cause = JmapException("Token refresh failed: HTTP 401", httpCode = 401, oauthError = "invalid_client")
        assertTrue(isOAuthRefusal(cause))
        assertEquals(refused, syncErrorText(cause, refused))
    }

    @Test fun `the refusal is read from the OAuth word, not from the status code`() {
        // RFC 6749 §5.2 answers invalid_grant with a 400: a rule keyed on 401 would miss it.
        val cause = JmapException("Token refresh failed: HTTP 400", httpCode = 400, oauthError = "invalid_grant")
        assertEquals(refused, syncErrorText(cause, refused))
    }

    @Test fun `a JMAP 401 without an OAuth word keeps its own message`() {
        val cause = JmapException("Session fetch failed: HTTP 401", httpCode = 401)
        assertFalse(isOAuthRefusal(cause))
        assertEquals("Session fetch failed: HTTP 401", syncErrorText(cause, refused))
    }

    @Test fun `a refusal wrapped by another layer is still found`() {
        val inner = JmapException("Token refresh failed: HTTP 401", httpCode = 401, oauthError = "invalid_client")
        val cause = IllegalStateException("IMAP login failed", inner)
        assertEquals(refused, syncErrorText(cause, refused))
    }

    @Test(timeout = 5_000)
    fun `a cause chain that loops back on itself still gets an answer`() {
        val first = IllegalStateException("first")
        val second = IllegalStateException("second", first)
        first.initCause(second)
        assertFalse(isOAuthRefusal(first))
        assertEquals("first", syncErrorText(first, refused))
    }

    @Test fun `any other failure keeps its message, or its class name when it has none`() {
        assertEquals("connection reset", syncErrorText(IOException("connection reset"), refused))
        assertEquals("IOException", syncErrorText(IOException(), refused))
    }
}
