package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The decision behind "Upload sent messages", EXECUTED — not read as text.
 *
 * `ImapMailService.send` APPENDs the copy whenever it is handed a folder, and nothing in the four
 * modules can run that path (it wants a real account, a real socket and a token refresher). So the
 * choice of what to hand it is [sentMailboxToUpload], a pure function, and this is the only place
 * where the setting's effect is actually run rather than described.
 *
 * ⛔ The three cases are spelled out one by one, with the expected value written down. A test that
 * recomputed the rule to decide what to assert would stay green with the shipped condition
 * inverted, which is the failure mode this file exists not to be.
 */
class SentCopyUploadTest {

    private val sent = "INBOX.Sent"

    @Test
    fun `setting on hands the send the sent folder it was given`() {
        assertEquals(
            "with the setting on, the Sent copy must still be uploaded — this is the shipped " +
                "default and every existing account is on it.",
            sent,
            sentMailboxToUpload(uploadSentCopy = true, sentMailboxOfRole = sent),
        )
    }

    @Test
    fun `setting off hands the send no folder at all`() {
        assertNull(
            "with the setting off the send must receive NO folder: null is what stops the APPEND, " +
                "and the SMTP submission itself is unaffected. Anything else here means the second " +
                "copy is still written and the setting changes nothing.",
            sentMailboxToUpload(uploadSentCopy = false, sentMailboxOfRole = sent),
        )
    }

    @Test
    fun `no sent folder known stays null, setting on`() {
        assertNull(
            "no folder carries the 'sent' role yet (a freshly added account, folders not synced): " +
                "the send must keep receiving null, which it already knows how to live with.",
            sentMailboxToUpload(uploadSentCopy = true, sentMailboxOfRole = null),
        )
    }

    @Test
    fun `no sent folder known stays null, setting off`() {
        assertNull(
            "setting off and no folder known must not conjure a folder up.",
            sentMailboxToUpload(uploadSentCopy = false, sentMailboxOfRole = null),
        )
    }
}
