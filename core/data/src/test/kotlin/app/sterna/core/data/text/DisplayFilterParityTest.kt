package app.sterna.core.data.text

import app.sterna.core.imap.MimeParser
import app.sterna.core.jmap.model.withoutBidiAndControls
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two protocols filter a displayed header with the same list. Both shipped implementations are
 * run, over every BMP code point but the surrogates: IMAP's through its public door, which also
 * trims, hence a character framed between two letters so the trim never reaches it.
 */
class DisplayFilterParityTest {

    @Test fun `JMAP and IMAP drop exactly the same characters`() {
        val differences = (0x0000..0xFFFF)
            .filterNot { it in 0xD800..0xDFFF }
            .mapNotNull { code ->
                val c = code.toChar().toString()
                val imap = MimeParser.decodedHeaderValue("a${c}b")
                val jmap = "a" + withoutBidiAndControls(c) + "b"
                if (imap == jmap) null else "U+%04X imap=%s jmap=%s".format(code, imap?.length, jmap.length)
            }

        assertEquals(
            "code points the two filters disagree on (lengths of \"a<c>b\" after each):\n" +
                differences.take(40).joinToString("\n"),
            0,
            differences.size,
        )
    }
}
