package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.nio.charset.Charset

/**
 * An inline OpenPGP block in a part whose charset is not UTF-8: what the verifier receives, and
 * what the reader shows of the plaintext the engine hands back. Both run through
 * [MimeParser.detectCrypto] then [inlineVerifyBytes] / [inlineDisplayText], the calls the
 * repository makes.
 */
class InlineArmorOctetsTest {

    private val koi8r: Charset = Charset.forName("koi8-r")

    private fun clearsigned(body: String) =
        "-----BEGIN PGP SIGNED MESSAGE-----\r\nHash: SHA256\r\n\r\n$body\r\n" +
            "-----BEGIN PGP SIGNATURE-----\r\n\r\niQEzBAEBCAAdFiEE\r\n=kX9q\r\n-----END PGP SIGNATURE-----"

    private val encrypted = "-----BEGIN PGP MESSAGE-----\r\n\r\nhQEMA0ciAbCdEf\r\n=zZ9a\r\n-----END PGP MESSAGE-----"

    /** [text] as the octets [charset] puts on the wire, in the one-char-per-octet container. */
    private fun wire(text: String, charset: Charset): String =
        String(text.toByteArray(charset), Charsets.ISO_8859_1)

    private fun eightBit(declared: String, content: String) =
        "From: a@b.c\r\nContent-Type: text/plain; charset=$declared\r\n" +
            "Content-Transfer-Encoding: 8bit\r\n\r\n$content\r\n"

    private fun verified(raw: String): List<Byte> =
        inlineVerifyBytes(MimeParser.detectCrypto(raw)!!).toList()

    @Test
    fun `iso-8859-1 in 8bit hands the verifier the wire slice between the markers`() {
        val block = clearsigned("Été à Noël")
        val raw = eightBit("iso-8859-1", "Voilà :\r\n" + wire(block, Charsets.ISO_8859_1))
        val begin = raw.indexOf("-----BEGIN PGP SIGNED MESSAGE-----")
        val end = raw.indexOf("-----END PGP SIGNATURE-----") + "-----END PGP SIGNATURE-----".length

        assertEquals(raw.substring(begin, end).toByteArray(Charsets.ISO_8859_1).toList(), verified(raw))
        assertEquals(block.toByteArray(Charsets.ISO_8859_1).toList(), verified(raw))
    }

    @Test
    fun `iso-8859-1 in quoted-printable hands the verifier the decoded octets`() {
        val block = clearsigned("Été à Noël")
        val qp = block.toByteArray(Charsets.ISO_8859_1).joinToString("") { b ->
            val c = b.toInt() and 0xff
            if (c >= 0x80 || c == '='.code) "=%02X".format(c) else c.toChar().toString()
        }
        val raw = "From: a@b.c\r\nContent-Type: text/plain; charset=iso-8859-1\r\n" +
            "Content-Transfer-Encoding: quoted-printable\r\n\r\n$qp\r\n"

        assertEquals(block.toByteArray(Charsets.ISO_8859_1).toList(), verified(raw))
    }

    @Test
    fun `koi8-r in base64 hands the verifier the decoded octets`() {
        val block = clearsigned("Привет, мир")
        val b64 = java.util.Base64.getMimeEncoder().encodeToString(block.toByteArray(koi8r))
        val raw = "From: a@b.c\r\nContent-Type: text/plain; charset=koi8-r\r\n" +
            "Content-Transfer-Encoding: base64\r\n\r\n$b64\r\n"

        assertEquals(block.toByteArray(koi8r).toList(), verified(raw))
    }

    @Test
    fun `utf-8 in 8bit is unchanged`() {
        val block = clearsigned("Été à Noël")
        val raw = eightBit("utf-8", wire(block, Charsets.UTF_8))

        assertEquals(block.toByteArray(Charsets.UTF_8).toList(), verified(raw))
    }

    @Test
    fun `pure ascii is unchanged`() {
        val block = clearsigned("Summer at Christmas")
        val raw = "From: a@b.c\r\nContent-Type: text/plain\r\n\r\n$block\r\n"

        assertEquals(block.toByteArray(Charsets.US_ASCII).toList(), verified(raw))
    }

    /** Re-encoding the decoded armour in the declared charset would turn the lone `E9`, which is
     *  not UTF-8, into the three bytes of U+FFFD. */
    @Test
    fun `iso-8859-1 octets under a declared utf-8 reach the verifier untouched`() {
        val block = clearsigned("Été")
        val raw = eightBit("utf-8", wire(block, Charsets.ISO_8859_1))

        assertEquals(block.toByteArray(Charsets.ISO_8859_1).toList(), verified(raw))
    }

    /** The html part comes first and carries the block in UTF-8: slicing the whole source instead of
     *  the text/plain part would hand the verifier that copy. */
    @Test
    fun `in a multipart alternative the octets come from the text plain part`() {
        val block = clearsigned("Été à Noël")
        val raw = buildString {
            append("From: a@b.c\r\nContent-Type: multipart/alternative; boundary=\"ALT\"\r\n\r\n")
            append("--ALT\r\nContent-Type: text/html; charset=utf-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n")
            append("<pre>" + wire(block, Charsets.UTF_8) + "</pre>\r\n")
            append("--ALT\r\nContent-Type: text/plain; charset=iso-8859-1\r\nContent-Transfer-Encoding: 8bit\r\n\r\n")
            append(wire(block, Charsets.ISO_8859_1) + "\r\n")
            append("--ALT--\r\n")
        }

        assertEquals(block.toByteArray(Charsets.ISO_8859_1).toList(), verified(raw))
    }

    @Test
    fun `an encrypted inline block in an iso-8859-1 part keeps its armour bytes`() {
        val raw = eightBit("iso-8859-1", wire("Voilà :\r\n$encrypted", Charsets.ISO_8859_1))

        assertEquals(encrypted.toByteArray(Charsets.US_ASCII).toList(), verified(raw))
    }

    @Test
    fun `utf-16 keeps the decoded armour in utf-8 and shows the plaintext in utf-8`() {
        val block = clearsigned("Été")
        val b64 = java.util.Base64.getMimeEncoder().encodeToString(block.toByteArray(Charsets.UTF_16))
        val raw = "From: a@b.c\r\nContent-Type: text/plain; charset=utf-16\r\n" +
            "Content-Transfer-Encoding: base64\r\n\r\n$b64\r\n"
        val envelope = MimeParser.detectCrypto(raw)!!

        assertEquals(block.toByteArray(Charsets.UTF_8).toList(), inlineVerifyBytes(envelope).toList())
        assertEquals("Été", inlineDisplayText(envelope, "Été".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `the plaintext of a cleartext-signed iso-8859-1 block is shown in iso-8859-1`() {
        val raw = eightBit("iso-8859-1", wire(clearsigned("Été"), Charsets.ISO_8859_1))
        val envelope = MimeParser.detectCrypto(raw)!!

        assertEquals("Été", inlineDisplayText(envelope, "Été".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun `the plaintext of a cleartext-signed koi8-r block is shown in koi8-r`() {
        val b64 = java.util.Base64.getMimeEncoder().encodeToString(clearsigned("Привет").toByteArray(koi8r))
        val raw = "From: a@b.c\r\nContent-Type: text/plain; charset=koi8-r\r\n" +
            "Content-Transfer-Encoding: base64\r\n\r\n$b64\r\n"
        val envelope = MimeParser.detectCrypto(raw)!!

        assertEquals("Привет", inlineDisplayText(envelope, "Привет".toByteArray(koi8r)))
    }

    @Test
    fun `the plaintext of an encrypted block is read as utf-8 even in an iso-8859-1 part`() {
        val raw = eightBit("iso-8859-1", wire("Voilà :\r\n$encrypted", Charsets.ISO_8859_1))
        val envelope = MimeParser.detectCrypto(raw)!!
        val plaintext = "Été".toByteArray(Charsets.UTF_8)

        assertEquals("Été", inlineDisplayText(envelope, plaintext))
        assertNotEquals("Été", plaintext.toString(Charsets.ISO_8859_1))
    }
}
