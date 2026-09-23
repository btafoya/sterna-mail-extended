package app.sterna.ui.compose

import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.pgp.PgpMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** "Take a photo" in the composer (#233): whether the camera may open, and the file it writes to. */
class CaptureDecisionTest {

    @Test fun aJmapPlainMessageOfflineRefusesBeforeTheCameraOpens() {
        assertTrue(captureRefusedOffline(MailProtocol.JMAP, PgpMode.OFF, online = false))
    }

    @Test fun aJmapMessageWithPgpOnOpensTheCameraOffline() {
        PgpMode.entries.filter { it != PgpMode.OFF }.forEach { mode ->
            assertFalse("JMAP with $mode stages locally", captureRefusedOffline(MailProtocol.JMAP, mode, online = false))
        }
    }

    @Test fun imapOpensTheCameraOfflineWhateverThePadlock() {
        PgpMode.entries.forEach { mode ->
            assertFalse("IMAP with $mode stages locally", captureRefusedOffline(MailProtocol.IMAP, mode, online = false))
        }
    }

    @Test fun onlineAlwaysOpensTheCamera() {
        MailProtocol.entries.forEach { protocol ->
            PgpMode.entries.forEach { mode ->
                assertFalse("$protocol / $mode online", captureRefusedOffline(protocol, mode, online = true))
            }
        }
    }

    @Test fun onlyAJmapPlainAttachmentGoesToTheServer() {
        assertFalse(stagesAttachmentLocally(MailProtocol.JMAP, PgpMode.OFF))
        assertTrue(stagesAttachmentLocally(MailProtocol.JMAP, PgpMode.SIGN))
        assertTrue(stagesAttachmentLocally(MailProtocol.IMAP, PgpMode.OFF))
    }

    @Test fun eachCaptureGetsItsOwnJpegFile() {
        val a = captureFileName(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val b = captureFileName(UUID.fromString("00000000-0000-0000-0000-000000000002"))
        assertNotEquals(a, b)
        listOf(a, b).forEach { name ->
            assertTrue("$name ends in .jpg", name.endsWith(".jpg"))
            assertTrue("$name is a bare file name", Regex("[A-Za-z0-9._-]+").matches(name))
        }
        assertEquals(a, captureFileName(UUID.fromString("00000000-0000-0000-0000-000000000001")))
    }

    @Test fun aPhotoIsAttachedOnlyWhenTakenAndNotEmpty() {
        assertTrue(captureLanded(taken = true, size = 1))
        assertFalse("taken but empty or absent is a cancel", captureLanded(taken = true, size = 0))
        assertFalse(captureLanded(taken = false, size = 2048))
    }
}
