package app.sterna.ui.compose

import app.sterna.R
import app.sterna.core.jmap.ContentTooLargeException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class ComposedAttachmentOpenFailureTest {

    @Test fun `our own size ceiling is said plainly, online or not`() {
        assertEquals(
            R.string.status_attachment_too_large,
            composedAttachmentOpenFailure(ContentTooLargeException("too big", 1, 0), online = true),
        )
        assertEquals(
            R.string.status_attachment_too_large,
            composedAttachmentOpenFailure(ContentTooLargeException("too big", 1, 0), online = false),
        )
    }

    @Test fun `a transport failure with connectivity down is told as offline`() {
        assertEquals(
            R.string.status_action_offline,
            composedAttachmentOpenFailure(IOException("fetch", UnknownHostException("mail")), online = false),
        )
    }

    @Test fun `a transport failure while connected is not called offline`() {
        assertEquals(
            R.string.status_open_attachment_failed,
            composedAttachmentOpenFailure(UnknownHostException("mail"), online = true),
        )
    }

    @Test fun `a failure that is not the transport is not called offline, even with connectivity down`() {
        assertEquals(
            R.string.status_open_attachment_failed,
            composedAttachmentOpenFailure(IOException("No space left on device"), online = false),
        )
    }
}
