package app.sterna.core.imap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Whether a list row gets its paperclip, decided from the BODYSTRUCTURE of the list FETCH and stored
 * with the row: a wrong answer here stays wrong until the message is fetched again.
 */
class HasAttachmentTest {

    /** A BODYSTRUCTURE as a server writes it, parsed by the shipped parser. */
    private fun structure(bodystructure: String): Any? =
        ImapParser(ByteArrayInputStream("$bodystructure\r\n".toByteArray(Charsets.ISO_8859_1)))
            .readResponse()
            .firstOrNull()

    private companion object {
        const val PLAIN = """("text" "plain" ("charset" "utf-8") NIL NIL "7bit" 100 5)"""
        const val PDF = """("application" "pdf" ("name" "a.pdf") NIL NIL "base64" 900 NIL """ +
            """("attachment" ("filename" "a.pdf")) NIL)"""

        const val ENVELOPE = """("Tue, 15 Sep 2026 10:00:00 +0200" "Weekly notes" """ +
            """(("Ann" NIL "ann" "example.test")) (("Ann" NIL "ann" "example.test")) """ +
            """(("Ann" NIL "ann" "example.test")) (("Bob" NIL "bob" "example.test")) """ +
            """NIL NIL NIL "<notes@example.test>")"""

        /** A forwarded message with no disposition and no name, the way servers write it. */
        const val FORWARDED_UPPER = """("MESSAGE" "RFC822" NIL NIL NIL "7BIT" 312 $ENVELOPE """ +
            """("TEXT" "PLAIN" ("CHARSET" "UTF-8") NIL NIL "7BIT" 120 4) 10)"""

        const val FORWARDED_LOWER = """("message" "rfc822" NIL NIL NIL "7bit" 312 $ENVELOPE """ +
            """("text" "plain" ("charset" "utf-8") NIL NIL "7bit" 120 4) 10)"""

        /** A bounce report part: a `message` type the reader does not list as a file. */
        const val DELIVERY_STATUS = """("MESSAGE" "DELIVERY-STATUS" NIL NIL NIL "7BIT" 200)"""
    }

    @Test fun `a forwarded message with no disposition gets a paperclip`() {
        assertTrue(hasAttachment(structure("""($PLAIN$FORWARDED_UPPER "MIXED")""")))
    }

    @Test fun `a forwarded message written in lower case gets a paperclip`() {
        assertTrue(hasAttachment(structure("""($PLAIN$FORWARDED_LOWER "mixed")""")))
    }

    @Test fun `a delivery status part alone gets no paperclip`() {
        assertFalse(hasAttachment(structure("""($PLAIN$DELIVERY_STATUS "REPORT")""")))
    }

    @Test fun `a plain text message gets no paperclip`() {
        assertFalse(hasAttachment(structure(PLAIN)))
    }

    @Test fun `a part with an attachment disposition gets a paperclip`() {
        assertTrue(hasAttachment(structure("""($PLAIN$PDF "mixed")""")))
    }
}
