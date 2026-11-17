package app.sterna.core.data.mail

import app.sterna.core.imap.MimeBody
import app.sterna.core.imap.MimeParser
import app.sterna.core.jmap.model.Email
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the IMAP open reads off the source to say which address a message was delivered to (#302). */
class DeliveryHeadersTest {

    private fun opened(header: String): Email {
        val crlf = header.trimMargin().replace("\n", "\r\n") + "\r\n\r\n"
        return openedImapEmail(
            Email(id = "acc:INBOX:7", accountId = "acc"),
            MimeBody(html = null, text = "hi"),
            crlf,
            MimeParser.rawHeaders(crlf),
            emptySet(),
        )
    }

    @Test fun `the open carries the highest delivery header and the List-Id`() {
        val email = opened(
            """
            |Delivered-To: sales@example.com
            |X-Original-To: sales@example.com
            |Delivered-To: me@example.com
            |X-Original-To: relay@example.org
            |List-Id: <team.example.com>
            |From: Ann <ann@example.org>
            |Subject: Hi
            """,
        )
        assertEquals("sales@example.com", email.deliveredAddress)
        assertEquals("<team.example.com>", email.listId)
    }

    @Test fun `an X-Original-To the sender slipped under the server's Delivered-To is not taken`() {
        val email = opened(
            """
            |Delivered-To: me@example.com
            |Received: from mx.example.org
            |From: Ann <ann@example.org>
            |X-Original-To: boss@example.com
            |Subject: Hi
            """,
        )
        assertEquals("me@example.com", email.deliveredAddress)
    }

    @Test fun `an X-Original-To the server wrote above its Delivered-To is taken`() {
        val email = opened(
            """
            |X-Original-To: sales@example.com
            |Delivered-To: me@example.com
            |Received: from mx.example.org
            |From: Ann <ann@example.org>
            """,
        )
        assertEquals("sales@example.com", email.deliveredAddress)
    }

    @Test fun `a source without them leaves both null`() {
        val email = opened(
            """
            |From: Ann <ann@example.org>
            |To: me@example.com
            |Subject: Hi
            """,
        )
        assertEquals(null, email.deliveredAddress)
        assertEquals(null, email.listId)
    }

    @Test fun `the header name is matched without regard to case`() {
        val email = opened(
            """
            |delivered-to: sales@example.com
            |From: Ann <ann@example.org>
            """,
        )
        assertEquals("sales@example.com", email.deliveredAddress)
    }
}
