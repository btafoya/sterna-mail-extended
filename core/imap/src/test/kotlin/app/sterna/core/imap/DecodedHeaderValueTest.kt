package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

/**
 * [MimeParser.decodedHeaderValue] — the same sanitising door as [MimeParser.decodedHeaderOf], for
 * a value that has ALREADY been separated from its message.
 *
 * That is the whole difference and the whole reason it exists: `decodedHeaderOf` takes `(raw,
 * name)` and can only serve a caller holding the message source. JMAP hands over header fields as
 * name/value pairs, with no source to read (#160), and [decodeWords] and the two filters under it
 * are `internal` to this module — so the value has to come here rather than a second copy of the
 * chain growing where it is needed.
 */
class DecodedHeaderValueTest {

    private fun word(text: String): String =
        "=?utf-8?B?" + Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)) + "?="

    @Test fun `an encoded-word value comes back readable`() {
        assertEquals("Émile Zola <emile@example.org>", MimeParser.decodedHeaderValue(word("Émile Zola <emile@example.org>")))
    }

    @Test fun `a bidi override is removed from the value`() {
        assertEquals("Facture fdp.exe", MimeParser.decodedHeaderValue(word("Facture ‮fdp.exe")))
    }
}
