package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A PGP armor arrives with bare LFs, and nothing downstream fixes them.**
 *
 * The armored bytes are not built here: they come from the OpenPGP provider (OpenKeychain /
 * BouncyCastle `ArmoredOutputStream`), which separates its lines with bare `\n`. [PgpMime]
 * canonicalises the entity it SIGNS ([PgpMime.signablePayload]) and never the armor, so the
 * wrapper splices provider bytes verbatim into a message that is otherwise pure CRLF. On a relay
 * hardened after CVE-2023-51764 (`smtpd_forbid_bare_newline`) that message is refused and the
 * send never completes — the same message unsigned goes out.
 *
 * Two guards, tested separately because they are two distinct places to break: the producer
 * ([PgpMime.wrapSigned] / [PgpMime.wrapEncrypted]) and the delivery seam ([OutgoingMime.build],
 * the only one that also repairs an entity ALREADY on disk in `files/outbox/<id>/`).
 */
class PgpArmorIsCrlfTest {

    @Test
    fun `a signed wrapper made of provider bytes carries no bare LF`() {
        val entity = PgpMime.wrapSigned(
            "Content-Type: text/plain\r\n\r\nx",
            bareLfArmor("SIGNATURE"),
            "pgp-sha256",
            "BND",
        )

        assertNoBareLf("the wrapped signature", entity)
        assertArmorIsIntact("SIGNATURE", entity)
    }

    @Test
    fun `an encrypted wrapper made of provider bytes carries no bare LF`() {
        val entity = PgpMime.wrapEncrypted(bareLfArmor("MESSAGE"), "BND")

        assertNoBareLf("the wrapped ciphertext", entity)
        assertArmorIsIntact("MESSAGE", entity)
    }

    @Test
    fun `an entity already queued on disk is repaired at the delivery seam`() {
        // The case a fix in PgpMime alone does NOT cover: this entity was built by an older
        // version, written to files/outbox/<id>/prebuilt-entity.mime, and is read back verbatim.
        // Nothing will ever rebuild it — the seam is the only place left to make it conforming.
        val queued = "Content-Type: multipart/encrypted; boundary=\"B\"\r\n\r\n--B\n" +
            String(bareLfArmor("MESSAGE"), Charsets.US_ASCII) + "--B--\n"

        val mime = OutgoingMime.build(pgpMessage().copy(prebuiltEntity = queued))

        assertNoBareLf("the delivered message", mime)
        assertArmorIsIntact("MESSAGE", mime)
    }

    @Test
    fun `a signed entity crosses the delivery seam with its signed bytes intact`() {
        // The property the whole change puts at risk, and the only test that exercises it end to
        // end: canonicalising AT THE SEAM must not disturb the octets the signature covers.
        // A long body on purpose — the base64 is folded, so the signed payload is many lines and
        // there are separators to get wrong.
        val payload = PgpMime.signablePayload(OutgoingMime.buildBodyEntity(pgpMessage().copy(body = LONG_BODY)))
        val entity = PgpMime.wrapSigned(payload, bareLfArmor("SIGNATURE"), "pgp-sha256", "BND_SEAM")

        val mime = OutgoingMime.build(pgpMessage().copy(prebuiltEntity = entity))

        assertNoBareLf("the delivered signed message", mime)
        val env = MimeParser.detectCrypto(mime)
        assertNotNull("the delivered message no longer detects as PGP/MIME signed at all", env)
        assertEquals(
            "the bytes a verifier extracts from the delivered message are no longer the bytes that " +
                "were signed: every recipient reads \"invalid signature\", and the app says nothing",
            payload,
            env!!.signedEntityRaw,
        )
    }

    @Test
    fun `a bare CR inside signed bytes crosses the seam untouched`() {
        // The one thing canonicalisation must NOT tidy up. An entity signed before dee4a346
        // and still sitting in the outbox contains "\r\r\n" INSIDE the octets the signature was
        // computed over. Straightening it on the way out changes the signed bytes, and the
        // recipient's client reads "invalid signature" — a wrong message delivered, where a bare
        // LF only ever produced a refused one. And a bare CR is not what smtpd_forbid_bare_newline
        // rejects anyway.
        val queued = "Content-Type: text/plain\r\n\r\nsigned line one\r\r\nsigned line two\r\n"

        val mime = OutgoingMime.build(pgpMessage().copy(prebuiltEntity = queued))

        assertTrue(
            "the CR/CRLF of a pre-dee4a346 signed entity was straightened at the seam: the bytes " +
                "no longer match the signature and the recipient reads \"invalid signature\". " +
                "Delivered body was:\n" + mime.substringAfter("MIME-Version: 1.0\r\n"),
            "signed line one\r\r\nsigned line two" in mime,
        )
    }

    private fun pgpMessage() = OutgoingMessage(
        from = "a@b.c",
        to = listOf("d@e.f"),
        subject = "s",
        body = "unused when a pre-built entity replaces the body",
        messageId = "mid@b.c",
        dateMillis = 1_700_000_000_000L,
    )

    private companion object {
        /** Past the 57-byte input that fills one 76-column base64 line, so the payload is folded. */
        const val LONG_BODY =
            "The quick brown fox jumps over the lazy dog, and keeps on jumping until this " +
                "sentence is comfortably longer than fifty-seven bytes of plaintext."
    }
}

/**
 * The lines of a provider armor, as literals — the ONE description of the fixture, from which both
 * the bare-LF input and the expected CRLF output are joined.
 *
 * Two joins of this list, never a transformation of one into the other: an expected value
 * derived from the input by a `replace` would be a second copy of [PgpMime.canonicalize], and any
 * mutation of the real one would be reproduced by the test instead of caught by it.
 *
 * What each line is there for:
 *  - the **empty line** after `-----BEGIN…-----` is mandatory (RFC 4880 § 6.2): it separates the
 *    armor headers from the radix-64 block. Drop it and every message still sends, and NO
 *    recipient can verify the signature. It is inside the pinned block for exactly that reason.
 *  - twenty 62-column body lines, so the whole armor collapsed into a single "line" is well past
 *    the 998 octets RFC 5321 § 4.5.3.1 allows.
 *  - `.abcdef` is deliberate and NOT realistic radix-64 (neither the body nor the `=abcd` CRC24
 *    checksum can begin with '.'): the delivery loop dot-stuffs by testing the first character of
 *    each split element, so an un-split armor hides whether stuffing happens at all.
 */
internal fun armorLines(kind: String): List<String> = buildList {
    add("-----BEGIN PGP $kind-----")
    add("")
    repeat(20) { i -> add("iQIzBAABCgAdFiEEABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnop${"%06d".format(i)}") }
    add(".abcdef")
    add("=abcd")
    add("-----END PGP $kind-----")
}

/** The armor as the provider really hands it over: bare LF between every line. */
internal fun bareLfArmor(kind: String): ByteArray =
    armorLines(kind).joinToString(separator = "\n", postfix = "\n").toByteArray(Charsets.US_ASCII)

/** The same armor as it must appear once delivered: CRLF, every line, nothing added or dropped. */
internal fun expectedCrlfArmor(kind: String): String =
    armorLines(kind).joinToString(separator = "\r\n", postfix = "\r\n")

/** Every `\n` that is not preceded by `\r` — what a hardened relay counts and refuses. */
internal fun bareLfOffsets(s: String): List<Int> =
    s.indices.filter { s[it] == '\n' && (it == 0 || s[it - 1] != '\r') }

internal fun assertNoBareLf(what: String, s: String) {
    val offsets = bareLfOffsets(s)
    assertEquals(
        "$what still carries ${offsets.size} bare LF(s), at offsets ${offsets.take(5)}… — a relay " +
            "with smtpd_forbid_bare_newline refuses the DATA and the message never leaves the outbox",
        emptyList<Int>(),
        offsets,
    )
}

/**
 * The WHOLE armor, byte for byte, as one CRLF block — not a line-by-line search.
 *
 * A per-line check that skipped empty lines let a wrapper drop the mandatory blank line of
 * RFC 4880 § 6.2 while staying green: the message sends and nobody can verify it.
 */
internal fun assertArmorIsIntact(kind: String, produced: String) {
    val expected = expectedCrlfArmor(kind)
    assertTrue(
        "the armor is not present intact as a CRLF block — a line was dropped, reordered or " +
            "reflowed. Expected to find:\n$expected\ninside:\n$produced",
        expected in produced,
    )
}
