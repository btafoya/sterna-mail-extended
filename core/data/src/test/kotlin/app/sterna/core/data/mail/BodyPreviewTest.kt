package app.sterna.core.data.mail

import app.sterna.core.imap.ImapTextPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset
import java.util.Base64

/**
 * A fetched fragment of a body, turned into the line a notification may show, executed, on the
 * bytes a server really sends.
 */
class BodyPreviewTest {

    /** [text] as it travels: encoded in [charset], then held one char per octet. */
    private fun wire(text: String, charset: Charset): String =
        String(text.toByteArray(charset), Charsets.ISO_8859_1)

    private fun part(mime: String, encoding: String?, charset: String?) =
        ImapTextPart(section = "1", mime = mime, encoding = encoding, charset = charset)

    private fun preview(
        raw: String,
        mime: String = "text/plain",
        encoding: String? = "7bit",
        charset: String? = "utf-8",
    ) = BodyPreview.fromPart(raw, part(mime, encoding, charset))

    // ---- the charset the sender announced ------------------------------------------------------

    @Test fun `an 8-bit UTF-8 body reads as its text`() {
        assertEquals("Café à Noël 日本語", preview(wire("Café à Noël 日本語", Charsets.UTF_8), encoding = "8bit"))
    }

    /** The charset is not a formality. Read as UTF-8, this body is a run of replacement
     *  characters; read as latin-1, it is what was written. */
    @Test fun `a latin-1 body is read as latin-1, not as UTF-8`() {
        val raw = wire("Réunion à Noël", Charsets.ISO_8859_1)
        assertEquals("Réunion à Noël", preview(raw, encoding = "8bit", charset = "iso-8859-1"))
    }

    @Test fun `a KOI8-R body is read as KOI8-R`() {
        val raw = wire("Привет, мир", charset("KOI8-R"))
        assertEquals("Привет, мир", preview(raw, encoding = "8bit", charset = "koi8-r"))
    }

    @Test fun `a windows-1252 body keeps its punctuation`() {
        val raw = wire("It’s — done", charset("windows-1252"))
        assertEquals("It’s — done", preview(raw, encoding = "8bit", charset = "windows-1252"))
    }

    @Test fun `a charset nobody knows falls back to UTF-8 rather than failing`() {
        val raw = wire("Café", Charsets.UTF_8)
        assertEquals("Café", preview(raw, encoding = "8bit", charset = "x-nonesuch-9"))
        assertEquals("Café", preview(raw, encoding = "8bit", charset = null))
        assertEquals("Café", preview(raw, encoding = "8bit", charset = ""))
    }

    // ---- the transfer-encoding -----------------------------------------------------------------

    @Test fun `a quoted-printable body is decoded, soft breaks included`() {
        val raw = "R=C3=A9union de l=\r\n'=C3=A9quipe"
        assertEquals("Réunion de l'équipe", preview(raw, encoding = "quoted-printable"))
    }

    /**
     * The other cut that always happens, and the commonest encoding of accented mail. The QP
     */
    @Test fun `a quoted-printable escape cut in half does not reach the screen`() {
        assertEquals("Café et th", preview("Caf=C3=A9 et th=C", encoding = "quoted-printable"))
        assertEquals("Bonjour", preview("Bonjour=", encoding = "quoted-printable"))
        assertEquals("Bonjour", preview("Bonjour=\r", encoding = "quoted-printable"))
    }

    /** The inverse witness: a complete escape or soft break at the very end still decodes, "drop
     *  the tail" must not become "drop a character of every message". */
    @Test fun `a complete escape at the end of the fragment survives`() {
        assertEquals("2 = 2", preview("2 =3D 2", encoding = "quoted-printable"))
        assertEquals("abc", preview("abc=\r\n", encoding = "quoted-printable"))
        assertEquals("Café", preview("Caf=C3=A9", encoding = "quoted-printable"))
    }

    @Test fun `a base64 body is decoded`() {
        val raw = java.util.Base64.getEncoder().encodeToString("Bonjour à tous".toByteArray(Charsets.UTF_8))
        assertEquals("Bonjour à tous", preview(raw, encoding = "base64"))
    }

    /**
     * The cut that always happens.
     */
    @Test fun `a base64 body cut mid-quantum still decodes to text, never to base64`() {
        val text = "The quick brown fox jumps over the lazy dog. "
        val encoded = java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        val cut = encoded.take(31) // 31 is not a multiple of 4: 7 whole quanta, 21 octets

        val shown = preview(cut, encoding = "base64")

        assertEquals(text.take(21), shown)
        assertFalse("the base64 itself must never reach the screen", shown!!.contains(cut.take(8)))
    }

    @Test fun `a base64 body wrapped in CRLF decodes across its lines`() {
        val text = "Bonjour, ceci est un message assez long pour tenir sur plusieurs lignes."
        val encoded = java.util.Base64.getMimeEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

        assertEquals(text, preview(encoded, encoding = "base64"))
    }

    /** A cut multi-byte character must not surface as a black diamond at the end of the line. */
    @Test fun `a UTF-8 character cut in half is dropped, not shown as a replacement`() {
        val whole = wire("Café", Charsets.UTF_8) // 5 octets: the é is two of them
        val shown = preview(whole.dropLast(1), encoding = "8bit")

        assertEquals("Caf", shown)
        assertFalse(shown!!.contains('�'))
    }

    // ---- HTML --------------------------------------------------------------------------------

    @Test fun `an HTML part is flattened to its text`() {
        val html = "<html><head><style>p{color:red}</style></head><body><p>Hi <b>there</b></p></body></html>"

        assertEquals("Hi there", preview(html, mime = "text/html"))
    }

    /**
     * The first 8 KB of a newsletter is very often its stylesheet. `htmlToText` removes a
     * `<style>` block by matching a pair of tags, so one whose closing tag is past the cut is not
     * removed at all and its CSS is read as text.
     */
    @Test fun `an HTML fragment cut inside a style block shows nothing, never the CSS`() {
        val cut = "<html><head><style>body{font-family:Helvetica;color:#333;background:#fff"

        assertNull(preview(cut, mime = "text/html"))
    }

    /** `<script>` and `<head>`, not only `<style>`: a cut swallows whichever came first, and
     *  `htmlToText` removes none of them without their closing tag. Without this case, narrowing
     *  the rule to `(style)` alone leaves the suite green and the JavaScript on the screen. */
    @Test fun `an HTML fragment cut inside a script or a head shows neither`() {
        assertEquals("Hello", preview("<p>Hello</p><script>var a = 1; if (a < 2) { go(", mime = "text/html"))
        assertEquals("Hello", preview("<p>Hello</p><head><meta charset=\"utf-8\"><title>New", mime = "text/html"))
    }

    @Test fun `the text before a cut style block still shows`() {
        val cut = "<p>Sale ends today</p><style>.x{color:red;background"

        assertEquals("Sale ends today", preview(cut, mime = "text/html"))
    }

    @Test fun `an HTML fragment cut in the middle of a tag does not show the tag`() {
        val cut = "<p>Hello there</p><div class=\"container\" style=\"margin:0;padd"

        assertEquals("Hello there", preview(cut, mime = "text/html"))
    }

    @Test fun `HTML entities are decoded and block breaks kept`() {
        assertEquals(
            "Café &amp; co\nSecond line",
            preview("<p>Caf&eacute; &amp;amp; co</p><p>Second line", mime = "text/html"),
        )
    }

    /** A plain-text part is not flattened: an angle-bracketed URL is content, and htmlToText
     *  would delete it as a tag. */
    @Test fun `a plain-text part keeps what would look like markup`() {
        assertEquals(
            "See <https://example.org/a> and mail <bob@example.org>",
            preview("See <https://example.org/a> and mail <bob@example.org>"),
        )
    }

    // ---- nothing to show is an answer -----------------------------------------------------------

    @Test fun `an empty or blank part yields null, never an empty line`() {
        assertNull(preview(""))
        assertNull(preview("   \r\n\t  "))
        assertNull(preview("", mime = "text/html"))
        assertNull(preview("<div>\n  <span> </span>\n</div>", mime = "text/html"))
    }

    // ---- the shaping belongs to the notification, not here ---------------------------------------

    /**
     * The layering, pinned.
     */
    @Test fun `nothing is capped, flattened or elided here`() {
        val long = "x".repeat(1000)
        assertEquals(long, preview(long))
        assertEquals("line one\nline two", preview("line one\nline two"))
        assertEquals("  spaced  out ", preview("  spaced  out "))
    }

    private val felis = "=felis-alternative=20261016114211=20715"

    private val russian = "Здравствуйте! Ваша скидка недели уже ждёт вас: двадцать процентов на всё, " +
        "что лежит в корзине, до воскресенья включительно. Спасибо, что вы с нами."

    private fun b64Lines(text: String): String =
        Base64.getMimeEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun felisFragment(textBody: String): String =
        "--$felis\r\n" +
            "Content-Transfer-Encoding: base64\r\n" +
            "Content-Type: text/plain; charset=\"utf-8\"\r\n" +
            "\r\n" +
            textBody + "\r\n" +
            "--$felis\r\n" +
            "Content-Transfer-Encoding: base64\r\n" +
            "Content-Type: text/html; charset=\"utf-8\"\r\n" +
            "\r\n" +
            b64Lines("<p>HTML</p>") + "\r\n" +
            "--$felis--\r\n"

    @Test fun `a multipart fragment behind a text-plain structure shows its first text part`() {
        val raw = felisFragment(b64Lines(russian))
        assertEquals(russian, preview(raw, encoding = "7bit"))
        assertEquals(russian, preview(raw, encoding = "base64"))
        assertEquals(russian, preview(raw, encoding = null, charset = null))
    }

    @Test fun `a multipart fragment cut inside its base64 text part shows the readable start`() {
        val encoded = b64Lines(russian)
        val cut = "--$felis\r\n" +
            "Content-Transfer-Encoding: base64\r\n" +
            "Content-Type: text/plain; charset=\"utf-8\"\r\n" +
            "\r\n" +
            encoded.substring(0, 4 * 30 + 2 + 2)
        for (encoding in listOf("7bit", "base64")) {
            val shown = preview(cut, encoding = encoding)!!
            assertTrue(shown, russian.startsWith(shown))
            assertTrue(shown, shown.startsWith("Здравствуйте! Ваша скидка"))
            assertFalse(shown, shown.contains('\uFFFD'))
        }
    }

    @Test fun `a multipart fragment whose first text part is HTML shows the HTML as text`() {
        val raw = "--$felis\r\n" +
            "Content-Type: text/html; charset=\"utf-8\"\r\n" +
            "Content-Transfer-Encoding: quoted-printable\r\n" +
            "\r\n" +
            "<p>Caf=C3=A9 ouvert</p>\r\n" +
            "--$felis\r\n" +
            "Content-Type: text/plain; charset=\"utf-8\"\r\n" +
            "\r\n" +
            "plain twin\r\n"
        assertEquals("Café ouvert", preview(raw))
    }

    @Test fun `a nested alternative inside a mixed fragment is read with its own boundary`() {
        val raw = "--=felis-mixed=1\r\n" +
            "Content-Type: multipart/alternative; boundary=\"$felis\"\r\n" +
            "\r\n" +
            felisFragment(b64Lines(russian)) +
            "--=felis-mixed=1\r\n" +
            "Content-Type: application/pdf; name=\"a.pdf\"\r\n" +
            "\r\n" +
            "JVBERi0=\r\n"
        assertEquals(russian, preview(raw))
    }

    @Test fun `a file or an invite before the body is not the body`() {
        val raw = "--b1\r\n" +
            "Content-Type: text/calendar; method=REQUEST\r\n" +
            "\r\n" +
            "BEGIN:VCALENDAR\r\n" +
            "--b1\r\n" +
            "Content-Type: text/plain; name=\"notes.txt\"\r\n" +
            "\r\n" +
            "file content\r\n" +
            "--b1\r\n" +
            "Content-Type: text/plain\r\n" +
            "Content-Disposition: attachment\r\n" +
            "\r\n" +
            "another file\r\n" +
            "--b1\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Disposition: inline\r\n" +
            "\r\n" +
            "the body\r\n"
        assertEquals("the body", preview(raw))
    }

    @Test fun `a multipart fragment with nothing readable yields null, never the raw MIME`() {
        val cutInHeaders = "--$felis\r\n" +
            "Content-Type: multipart/related; boundary=\"inner\"\r\n" +
            "\r\n" +
            "--inner\r\n" +
            "Content-Type: text/pl"
        assertNull(preview(cutInHeaders))
        assertNull(preview(cutInHeaders, encoding = "base64"))
        val imageOnly = "--b1\r\nContent-Type: image/png\r\nContent-Transfer-Encoding: base64\r\n\r\niVBORw0KGgo=\r\n"
        assertNull(preview(imageOnly))
    }

    @Test fun `a multipart fragment nested past the depth bound yields null`() {
        val depth = 40
        val sb = StringBuilder()
        for (i in 0 until depth) sb.append("--x${i}y\r\nContent-Type: multipart/mixed; boundary=x${i + 1}y\r\n\r\n")
        sb.append("--x${depth}y\r\nContent-Type: text/plain\r\n\r\ndeep\r\n")
        assertNull(preview(sb.toString()))
    }

    @Test fun `text that opens with two dashes but no header after is not a multipart`() {
        val signatureLike = "--Bonjour l'equipe,\nle serveur renvoie Content-Type: text/plain sans rien d'autre."
        assertEquals(signatureLike, preview(signatureLike))
        val bareDashes = "--abc\nle serveur renvoie Content-Type: text/plain"
        assertEquals(bareDashes, preview(bareDashes))
    }

    @Test fun `a multipart fragment is decoded by its inner part's charset, not the outer one`() {
        val cp1251 = charset("windows-1251")
        val eightBit = "--b1\r\n" +
            "Content-Type: text/plain; charset=windows-1251\r\n" +
            "Content-Transfer-Encoding: 8bit\r\n" +
            "\r\n" +
            wire("Привет, мир", cp1251) + "\r\n" +
            "--b1--\r\n"
        assertEquals("Привет, мир", preview(eightBit, encoding = "7bit", charset = "utf-8"))
        val qp = "Привет".toByteArray(cp1251).joinToString("") { "=%02X".format(it.toInt() and 0xFF) }
        val quoted = "--b1\r\n" +
            "Content-Type: text/plain; charset=windows-1251\r\n" +
            "Content-Transfer-Encoding: quoted-printable\r\n" +
            "\r\n" +
            qp + "\r\n" +
            "--b1--\r\n"
        assertEquals("Привет", preview(quoted, encoding = "7bit", charset = "utf-8"))
    }

    @Test fun `a multipart fragment with bare line feeds shows its text part`() {
        val raw = "--b1\nContent-Type: text/plain; charset=utf-8\n\nSalut\n--b1--\n"
        assertEquals("Salut", preview(raw))
    }

    @Test fun `MIME quoted in the middle of a text is the text`() {
        val raw = "Bonjour,\n--b\nContent-Type: text/plain\n\nx"
        assertEquals(raw, preview(raw))
    }

    @Test fun `a word and a colon after two dashes are not part headers`() {
        for (raw in listOf(
            "--Rappel\r\n10:30 salle B",
            "--Rappel\r\n10:30 salle B\r\n\r\nMerci",
            "--Lien\nhttps://exemple.fr",
        )) {
            assertEquals(raw, preview(raw))
        }
    }

    @Test fun `a part whose content headers come after another header is still a part`() {
        val raw = "--b\r\nX-Mailer: x\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nSalut"
        assertEquals("Salut", preview(raw))
    }
}
