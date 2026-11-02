package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * What the open of a message reads off its BODYSTRUCTURE (#157), on structures written by hand from
 * RFC 3501 §7.4.2 in the two shapes servers use: Dovecot's, lower case with every extension, and a
 * terser one, upper case and with none, as Stalwart may answer. Parsed by the shipped parser.
 */
class DisplayStructureTest {

    private fun structure(bodystructure: String): Any? =
        ImapParser(ByteArrayInputStream("$bodystructure\r\n".toByteArray(Charsets.ISO_8859_1)))
            .readResponse()
            .firstOrNull()

    private fun display(bodystructure: String): DisplayStructure? = displayStructureOf(structure(bodystructure))

    @Test fun `Dovecot's shape - the alternative's two texts and the file, with the server's octet count`() {
        val shown = display(DOVECOT_MIXED)!!
        assertEquals(DisplayTextPart("1.2", "quoted-printable", "utf-8", 13), shown.html)
        assertEquals(DisplayTextPart("1.1", "7bit", "utf-8", 6), shown.text)
        assertEquals(
            "size is body-fld-octets, the seventh field: what DownloadLimits.enforce compares",
            listOf(MimeAttachment("2", "figures.pdf", "application/pdf", 4096, "base64")),
            shown.attachments,
        )
        assertFalse(needsWholeSource(shown))
    }

    @Test fun `the terse shape - upper case, no extension data, the file known by its name alone`() {
        val shown = display(TERSE_MIXED)!!
        assertEquals(DisplayTextPart("1.2", "base64", "UTF-8", 40), shown.html)
        assertEquals(DisplayTextPart("1.1", "7bit", "UTF-8", 6), shown.text)
        assertEquals(listOf(MimeAttachment("2", "figures.pdf", "application/pdf", 4096, "base64")), shown.attachments)
    }

    @Test fun `an inline image is kept with its content id, an invitation as invite ics`() {
        val shown = display(
            """((("text" "html" ("charset" "utf-8") NIL NIL "7bit" 30 1 NIL ("inline" NIL) NIL NIL)""" +
                """("image" "png" NIL "<logo@x>" NIL "base64" 300 NIL ("inline" NIL) NIL NIL) "related" ("boundary" "R") NIL NIL NIL)""" +
                """("TEXT" "CALENDAR" ("CHARSET" "utf-8" "METHOD" "REQUEST") NIL NIL "7BIT" 500 20 NIL NIL NIL NIL)""" +
                """ "Mixed" ("boundary" "M") NIL NIL NIL)""",
        )!!
        assertEquals("1.1", shown.html?.section)
        assertNull(shown.text)
        assertEquals(
            listOf(
                MimeAttachment("1.2", "image", "image/png", 300, "base64", cid = "logo@x"),
                MimeAttachment("2", "invite.ics", "text/calendar", 500, "7bit"),
            ),
            shown.attachments,
        )
    }

    @Test fun `a named image keeps its content id, inline or attached, as Outlook and Apple Mail write it`() {
        val shown = display(
            """(("text" "html" ("charset" "utf-8") NIL NIL "7bit" 60 1 NIL NIL NIL NIL)""" +
                """("image" "png" ("name" "image001.png") "<image001.png@01DB0000.00000000>" NIL "base64" 12 NIL """ +
                """("inline" ("filename" "image001.png")) NIL NIL)""" +
                """("image" "jpeg" ("name" "photo.jpg") "<photo@x>" NIL "base64" 8 NIL ("attachment" ("filename" "photo.jpg")) NIL NIL)""" +
                """ "related" ("type" "text/html" "boundary" "N") NIL NIL NIL)""",
        )!!
        assertEquals(
            listOf(
                MimeAttachment("2", "image001.png", "image/png", 12, "base64", cid = "image001.png@01DB0000.00000000"),
                MimeAttachment("3", "photo.jpg", "image/jpeg", 8, "base64", cid = "photo@x"),
            ),
            shown.attachments,
        )
    }

    @Test fun `an attached message reads its disposition after its envelope, body and lines`() {
        val shown = display(
            """(("text" "plain" NIL NIL NIL "7bit" 12 1 NIL NIL NIL NIL)""" +
                """("message" "rfc822" NIL NIL NIL "7bit" 900 """ +
                """("Mon, 1 Sep 2026 10:00:00 +0000" "Hi" ((NIL NIL "bo" "example.org")) NIL NIL NIL NIL NIL NIL "<id@x>") """ +
                """("text" "plain" ("charset" "us-ascii") NIL NIL "7bit" 10 1 NIL NIL NIL NIL) 20 """ +
                """NIL ("attachment" ("filename" "fwd.eml")) NIL NIL) "mixed" ("boundary" "F") NIL NIL NIL)""",
        )!!
        assertEquals(listOf(MimeAttachment("2", "fwd.eml", "message/rfc822", 900, "7bit")), shown.attachments)
        assertEquals(DisplayTextPart("1", "7bit", null, 12), shown.text)
    }

    @Test fun `a filename in RFC 2231 form is decoded, and a text part that is a file is not the body`() {
        val shown = display(
            """(("TEXT" "PLAIN" ("CHARSET" "utf-8") NIL NIL "7BIT" 5 1 NIL ("ATTACHMENT" ("FILENAME" "notes.txt")) NIL NIL)""" +
                """("APPLICATION" "PDF" NIL NIL NIL "BASE64" 77 NIL ("Attachment" ("filename*" "utf-8''%D0%9E%D1%82%D1%87%D1%91%D1%82.pdf")) NIL NIL)""" +
                """ "MIXED" ("BOUNDARY" "X") NIL NIL NIL)""",
        )!!
        assertNull(shown.text)
        assertEquals(listOf("notes.txt", "Отчёт.pdf"), shown.attachments.map { it.name })
    }

    @Test fun `a message that is one text part is section 1`() {
        val shown = display("""("TEXT" "HTML" ("CHARSET" "iso-8859-1") NIL NIL "QUOTED-PRINTABLE" 120 3 NIL NIL NIL NIL)""")!!
        assertEquals(DisplayTextPart("1", "quoted-printable", "iso-8859-1", 120), shown.html)
    }

    @Test fun `what is not a body structure is refused whole, never read in part`() {
        for (broken in listOf(
            """("text")""",
            """("text" "plain" NIL NIL NIL "7bit" twelve 1)""",
            """(("text" "plain" NIL NIL NIL "7bit" 12 1))""",
            """(("text" "plain" NIL NIL NIL "7bit" 12 1)("application" "pdf" "name" NIL NIL "base64" 9) "mixed")""",
            "NIL",
            "BODYSTRUCTURE",
        )) {
            assertNull("must be refused: $broken", display(broken))
        }
    }

    @Test fun `signed, encrypted and S-MIME messages need the whole source, an ordinary one does not`() {
        val whole = listOf(
            """(("text" "plain" NIL NIL NIL "7bit" 1 1)("application" "pgp-signature" NIL NIL NIL "7bit" 1) "signed")""",
            """(("APPLICATION" "PGP-ENCRYPTED" NIL NIL NIL "7BIT" 1)("APPLICATION" "OCTET-STREAM" NIL NIL NIL "7BIT" 1) "ENCRYPTED")""",
            """("application" "pkcs7-mime" ("smime-type" "enveloped-data") NIL NIL "base64" 1)""",
            """("application" "x-pkcs7-mime" NIL NIL NIL "base64" 1)""",
            """(("text" "plain" NIL NIL NIL "7bit" 1 1)("application" "pgp-encrypted" NIL NIL NIL "7bit" 1) "mixed")""",
        )
        for (bodystructure in whole) {
            assertTrue("whole source: $bodystructure", needsWholeSource(display(bodystructure)!!))
        }
        assertFalse(needsWholeSource(display(DOVECOT_MIXED)!!))
        assertFalse(needsWholeSource(display(TERSE_MIXED)!!))
    }

    private companion object {
        const val DOVECOT_MIXED =
            """((("text" "plain" ("charset" "utf-8") NIL NIL "7bit" 6 1 NIL NIL NIL NIL)""" +
                """("text" "html" ("charset" "utf-8") NIL NIL "quoted-printable" 13 1 NIL NIL NIL NIL)""" +
                """ "alternative" ("boundary" "IN") NIL NIL NIL)""" +
                """("application" "pdf" ("name" "figures.pdf") NIL NIL "base64" 4096 NIL """ +
                """("attachment" ("filename" "figures.pdf")) NIL NIL) "mixed" ("boundary" "OUT") NIL NIL NIL)"""

        const val TERSE_MIXED =
            """((("TEXT" "PLAIN" ("CHARSET" "UTF-8") NIL NIL "7BIT" 6 1)""" +
                """("TEXT" "HTML" ("CHARSET" "UTF-8") NIL NIL "BASE64" 40 1) "ALTERNATIVE")""" +
                """("APPLICATION" "PDF" ("NAME" "figures.pdf") NIL NIL "BASE64" 4096) "MIXED")"""
    }
}
