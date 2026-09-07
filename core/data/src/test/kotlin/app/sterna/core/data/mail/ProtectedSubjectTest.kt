package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

/**
 * RFC 9788 protected headers: which subject the reader shows once a PGP/MIME message is
 * decrypted (issue #128).
 *
 * The outer envelope of an encrypted message carries a COVER subject — "[...]", "...",
 * "Encrypted message" — and the real one travels inside the ciphertext, in the headers of the
 * decrypted entity. [decryptedSubject] is that whole decision, extracted as a pure function so
 * these tests can EXECUTE it with pinned arguments: `MailRepository` cannot be built on a JVM
 * bench (its `AccountStore` opens shared preferences in its constructor), and a test that merely
 * read the rule out of the source would be a copy of it.
 *
 * Nothing here is recomputed: every case is a literal (outer subject, entity) pair and the literal
 * string that must come out.
 *
 * The entity is a BYTE CONTAINER (one char per wire byte, ISO-8859-1 — see `MimeParser`'s KDoc),
 * so decoding is not cosmetic, and it has to be the SAME decoding the outer subject gets.
 */
class ProtectedSubjectTest {

    /** A decrypted MIME entity: [headers], a blank line, a body. */
    private fun entity(vararg headers: String): String =
        headers.joinToString("\r\n") + "\r\n\r\nThe plaintext body.\r\n"

    /** [text] as one RFC 2047 base64 encoded-word — the shape a mail client emits. */
    private fun word(text: String): String =
        "=?utf-8?B?" + Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)) + "?="

    /** [text] as the raw UTF-8 octets a header carries when the sender skips RFC 2047. */
    private fun rawBytes(text: String): String =
        String(text.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)

    @Test fun `the subject inside the decrypted entity wins over the cover subject`() {
        assertEquals(
            "Quarterly budget review",
            decryptedSubject("[...]", entity("Subject: Quarterly budget review", "Content-Type: text/plain")),
        )
    }

    @Test fun `an entity with no subject header keeps the outer subject`() {
        assertEquals(
            "Encrypted message",
            decryptedSubject("Encrypted message", entity("Content-Type: text/plain", "To: a@b.test")),
        )
    }

    /**
     * An empty protected subject is not information, it is a loss: the field exists but says
     * nothing, and overwriting a readable cover subject with "" leaves the reader with no title
     * at all. The guard is the whole point of these two cases.
     */
    @Test fun `an empty subject header keeps the outer subject`() {
        assertEquals(
            "Encrypted message",
            decryptedSubject("Encrypted message", entity("Subject:", "Content-Type: text/plain")),
        )
    }

    @Test fun `a whitespace-only subject header keeps the outer subject`() {
        assertEquals(
            "Encrypted message",
            decryptedSubject("Encrypted message", entity("Subject:   \t ", "Content-Type: text/plain")),
        )
    }

    @Test fun `an encoded-word subject comes out readable, not verbatim`() {
        assertEquals(
            "Réunion / Отчёт",
            decryptedSubject("[...]", entity("Subject: " + word("Réunion / Отчёт"))),
        )
    }

    @Test fun `a subject carrying its UTF-8 bytes raw comes out as text, not mojibake`() {
        assertEquals(
            "Fêtes déjà prévues",
            decryptedSubject("[...]", entity("Subject: " + rawBytes("Fêtes déjà prévues"))),
        )
    }

    /**
     * The same anti-spoofing filter the outer envelope subject gets (`ImapClient`). A
     * protected subject is sender-controlled content: letting it through unfiltered would open on
     * screen exactly what the outer path closes.
     */
    @Test fun `a bidi override in the protected subject is removed`() {
        assertEquals(
            "Facture fdp.exe",
            decryptedSubject("[...]", entity("Subject: " + rawBytes("Facture \u202Efdp.exe"))),
        )
    }

    @Test fun `a control character in the protected subject is removed`() {
        assertEquals(
            "Alerte rouge",
            decryptedSubject("[...]", entity("Subject: Alerte\u0007 rouge")),
        )
    }

    /**
     * OBSERVED behaviour, not a wish: `MimeParser.parseHeaders` (MimeParser-374) builds a
     * `Map`, so a duplicated field silently keeps the LAST occurrence. Pinned here because a
     * crafted entity can carry two `Subject:` lines and this is what would reach the screen.
     */
    @Test fun `with two subject headers the LAST one is what comes out`() {
        assertEquals(
            "Second",
            decryptedSubject("[...]", entity("Subject: First", "Subject: Second")),
        )
    }

    @Test fun `an empty entity keeps the outer subject`() {
        assertEquals("Encrypted message", decryptedSubject("Encrypted message", ""))
    }

    @Test fun `an entity with no header block at all keeps the outer subject`() {
        assertEquals(
            "Encrypted message",
            decryptedSubject("Encrypted message", "This is the plaintext body.\r\nNo header block here.\r\n"),
        )
    }
}
