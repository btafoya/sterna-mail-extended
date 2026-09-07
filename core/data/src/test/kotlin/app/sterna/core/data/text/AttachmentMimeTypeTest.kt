package app.sterna.core.data.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Executes [attachmentMimeType] against pairs written by hand, never by re-running the rule. The
 * framework table is injected, so what stands in for the device here is three entries and nothing
 * else: a miss is as testable as a hit.
 */
class AttachmentMimeTypeTest {

    private val table = mapOf(
        "pdf" to "application/pdf",
        "jpg" to "image/jpeg",
        "html" to "text/html",
        "mp3" to "audio/mpeg",
        "7z" to "application/x-7z-compressed",
    )

    private val lookup: (String) -> String? = { table[it] }

    /** The defect itself: a sender who declares a PDF generic hands it to whatever claims generic. */
    @Test fun `a generic declaration is answered by the extension`() {
        assertEquals("application/pdf", attachmentMimeType("application/octet-stream", "rapport.pdf", lookup))
        assertEquals("image/jpeg", attachmentMimeType("application/octet-stream", "photo.jpg", lookup))
        assertEquals("text/html", attachmentMimeType("application/octet-stream", "page.html", lookup))
    }

    /** The framework table only answers a lowercase key, so a shouted name must be folded first. */
    @Test fun `a shouted extension is looked up all the same`() {
        assertEquals("application/pdf", attachmentMimeType("application/octet-stream", "NOTES.PDF", lookup))
        assertEquals("image/jpeg", attachmentMimeType("application/octet-stream", "Photo.Jpg", lookup))
    }

    /** The closed list, plus the shape JMAP hands over raw: mixed case and a name parameter. */
    @Test fun `the other generic spellings are generic too`() {
        assertEquals("application/pdf", attachmentMimeType("binary/octet-stream", "rapport.pdf", lookup))
        assertEquals("application/pdf", attachmentMimeType("*/*", "rapport.pdf", lookup))
        assertEquals(
            "application/pdf",
            attachmentMimeType("Application/Octet-Stream; name=rapport.pdf", "rapport.pdf", lookup),
        )
    }

    /** An absent type and an empty one are the same thing; only one of the two reaches the elvis. */
    @Test fun `nothing declared at all is generic`() {
        assertEquals("application/pdf", attachmentMimeType(null, "rapport.pdf", lookup))
        assertEquals("application/pdf", attachmentMimeType("", "rapport.pdf", lookup))
        assertEquals("application/pdf", attachmentMimeType("   ", "rapport.pdf", lookup))
    }

    /** The intent gets a wildcard rather than an empty string, which matches no activity at all. */
    @Test fun `nothing declared and no extension is the wildcard`() {
        assertEquals("*/*", attachmentMimeType(null, "archive", lookup))
        assertEquals("*/*", attachmentMimeType("", null, lookup))
        assertEquals("*/*", attachmentMimeType("*/*", "archive", lookup))
    }

    /**
     * The arbitration: a sender who declared a real type is believed, even when the name says
     * otherwise. Guessing over a declared type would open a text part as a PDF on her word alone.
     */
    @Test fun `a declared type that is not generic is never overruled`() {
        assertEquals("application/pdf", attachmentMimeType("application/pdf", "rapport.pdf", lookup))
        assertEquals("text/plain", attachmentMimeType("text/plain", "notes.PDF", lookup))
        assertEquals("image/jpeg", attachmentMimeType("image/jpeg", "photo.pdf", lookup))
    }

    /** A parameter in the Intent type matches nothing, and the calendar one arrives every time. */
    @Test fun `parameters are cut off a type that is kept`() {
        assertEquals("text/plain", attachmentMimeType("text/plain; charset=utf-8", "notes.txt", lookup))
        assertEquals("text/calendar", attachmentMimeType("text/calendar; method=REQUEST", "invite.ics", lookup))
        assertEquals("text/plain", attachmentMimeType("Text/Plain", "notes.txt", lookup))
        assertEquals("application/pdf", attachmentMimeType("*/*", "book.pdf") { "APPLICATION/PDF; q=1" })
    }

    /** A device whose table has no answer leaves the declaration standing, whatever it was. */
    @Test fun `a lookup that answers nothing falls back on what was declared`() {
        assertEquals("application/octet-stream", attachmentMimeType("application/octet-stream", "book.epub", lookup))
        assertEquals("application/octet-stream", attachmentMimeType("application/octet-stream", "book.epub") { "" })
        assertEquals("*/*", attachmentMimeType(null, "book.epub", lookup))
    }

    /** What follows the last dot has to look like a file type before it is asked about. */
    @Test fun `a name whose tail is not an extension is not looked up`() {
        val seen = mutableListOf<String>()
        val watching: (String) -> String? = { seen += it; table[it] }
        assertEquals("application/octet-stream", attachmentMimeType("application/octet-stream", "archive", watching))
        assertEquals("application/octet-stream", attachmentMimeType("application/octet-stream", "nom.", watching))
        assertEquals(
            "application/octet-stream",
            attachmentMimeType("application/octet-stream", "nom.tropdouzecaracteres", watching),
        )
        assertEquals("application/octet-stream", attachmentMimeType("application/octet-stream", "nom.pd f", watching))
        assertEquals("application/octet-stream", attachmentMimeType("application/octet-stream", "nom.p-d", watching))
        assertEquals("the table must not be asked about any of these", emptyList<String>(), seen)
    }

    /**
     * Digits and two-character tails are file types too. Narrow the class to letters, or the length
     * to three, and every `.mp3`, `.mp4` and `.7z` declared generic goes back to opening nowhere.
     */
    @Test fun `an extension made of digits is a file type like any other`() {
        assertEquals("audio/mpeg", attachmentMimeType("application/octet-stream", "bulletin.mp3", lookup))
        assertEquals("audio/mpeg", attachmentMimeType("application/octet-stream", "BULLETIN.MP3", lookup))
        assertEquals(
            "application/x-7z-compressed",
            attachmentMimeType("application/octet-stream", "dossier.7z", lookup),
        )
    }

    /**
     * A JMAP server hands the name over raw, so the padding a sender left on it must not decide
     * whether the extension is read. This is the asymmetry with IMAP, which trims before we see it.
     */
    @Test fun `a padded name is still read for its extension`() {
        assertEquals("application/pdf", attachmentMimeType("application/octet-stream", "rapport.pdf ", lookup))
        assertEquals("application/pdf", attachmentMimeType("application/octet-stream", "  rapport.pdf", lookup))
    }

    /** Twelve characters is still a file type; thirteen is a word, and the tail is not asked about. */
    @Test fun `twelve characters is the last length still asked about`() {
        val seen = mutableListOf<String>()
        val watching: (String) -> String? = { seen += it; table[it] }
        attachmentMimeType("application/octet-stream", "nom.abcdefghijkl", watching)
        attachmentMimeType("application/octet-stream", "nom.abcdefghijklm", watching)
        assertEquals(listOf("abcdefghijkl"), seen)
    }

    /** The one thing every caller may lean on: never empty, never shouted, never parameterised. */
    @Test fun `the answer is always a lowercase type with no parameter`() {
        listOf(
            attachmentMimeType(null, null, lookup),
            attachmentMimeType("", "", lookup),
            attachmentMimeType("APPLICATION/PDF; x=1", "a.b c", lookup),
            attachmentMimeType("application/octet-stream", "NOTES.PDF", lookup),
        ).forEach {
            assertEquals("no empty answer", true, it.isNotEmpty())
            assertEquals("no uppercase in $it", it.lowercase(), it)
            assertEquals("no parameter in $it", false, ";" in it)
        }
    }
}
