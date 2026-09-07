package app.sterna.core.data.mail

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import app.sterna.core.data.filter.SourceText
import org.junit.Test

/**
 * Executes [rawSourceBytes], the decision behind "Save as .eml": the String a raw source travels
 * in is a byte container (one char per octet), so the file gets those octets back UNCHANGED —
 * never a UTF-8 re-encoding, which would double every byte above 0x7F and hand the reader a
 * `.eml` that no longer matches the server's copy (a signature over it would not verify). And an
 * unreadable source is an UNKNOWN, refused, never a zero-byte file that looks like a saved message.
 */
class RawSourceBytesTest {

    @Test fun `an empty source is refused, not written as zero bytes`() {
        val t = assertThrows(MessageUnavailableException::class.java) { rawSourceBytes("M1", "") }
        assertTrue("message must say the source came back empty: ${t.message}", "empty" in t.message.orEmpty())
    }

    @Test fun `a whitespace-only source is refused too`() {
        val t = assertThrows(MessageUnavailableException::class.java) { rawSourceBytes("M1", "  \r\n") }
        assertTrue("message must say the source came back empty: ${t.message}", "empty" in t.message.orEmpty())
    }

    @Test fun `each char of the source is exactly one byte, 0xE9 stays 0xE9`() {
        val raw = "Subject: Réunion\r\n"
        val bytes = rawSourceBytes("M1", raw)
        // Written by hand: S u b j e c t : SP R é u n i o n CR LF, é being the single octet 0xE9.
        val expected = byteArrayOf(
            0x53, 0x75, 0x62, 0x6A, 0x65, 0x63, 0x74, 0x3A, 0x20,
            0x52, 0xE9.toByte(), 0x75, 0x6E, 0x69, 0x6F, 0x6E, 0x0D, 0x0A,
        )
        assertEquals("one byte per char — UTF-8 would give raw.length + 1", raw.length, bytes.size)
        assertArrayEquals(expected, bytes)
    }

    /**
     * Last resort, and only beside the executing tests above: the public entry point cannot be
     * built on a JVM bench (`MailRepository` needs Room and a network stack), so this pins that
     * [MailRepository.rawSource] still exists with the promised signature and still hands the
     * fetched source to [rawSourceBytes] — every code line of the file that names either, whole.
     * Delete the call and the export silently writes nothing; re-encode in UTF-8 and the bytes
     * above never reach the file. The fourth line is the one CALLER of `rawSource` inside the
     * file, the move between accounts (#189), pinned whole so a second maker of bytes cannot
     * hide behind a caller's name — and it is pinned WITH the numbering the row was ticked under,
     * which travels from `rawSource` down to the SELECT the fetch goes out on. Drop that argument
     * anywhere on these three lines and the export still works while the cross-account move reads
     * the octets of whatever holds that uid now ([ReadUnderTheNumberingItWasTickedUnderTest] runs
     * the read itself; only the text can say the argument is still handed down).
     */
    @Test fun `rawSource is wired to rawSourceBytes, and to nothing else`() {
        assertEquals(
            "MailRepository.rawSource must hand fetchRawSource's String to rawSourceBytes — that " +
                "is the ONE place the bytes are made, and where an empty source is refused. Lines found:",
            listOf(
                "internal fun rawSourceBytes(emailId: String, raw: String): ByteArray {",
                "suspend fun rawSource(credentials: AccountCredentials, emailId: String, frozen: FrozenNumbering = FrozenNumbering.NothingFrozen): ByteArray {",
                "return rawSourceBytes(emailId, fetchRawSource(credentials, email, emailId, frozen))",
                "override suspend fun readSource(): ByteArray = rawSource(source, email.id, frozen).also { messageId = messageIdOf(from, it) }",
            ),
            SourceText.codeLines(SourceText.read(REPOSITORY)).filter { "rawSource(" in it || "rawSourceBytes(" in it },
        )
    }

    private companion object {
        const val REPOSITORY = "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt"
    }
}
