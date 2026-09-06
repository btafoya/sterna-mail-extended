package app.sterna.core.data.mail

import app.sterna.core.data.text.htmlToText
import app.sterna.core.imap.ImapTextPart
import app.sterna.core.imap.MimeParser

/**
 * A fetched fragment of one MIME text part, turned into the readable opening of a message.
 *
 * It lives in `:core:data` and not in `:core:imap` for one reason: [htmlToText] is here, and the
 * dependency runs one way. And it is pure — bytes and what the server said about them, in; text or
 * null, out — because it is the half of the preview a socket test can never pin: which charset was
 * applied, what a cut multi-byte character became, whether a truncated `<style>` reached the screen
 * as CSS.
 *
 * NOTHING here shapes the result: no flattening, no cap, no ellipsis. That happens once, in
 * `MailNotificationText.shapePreview`, which is the single crossing the two protocols share. A cap
 * applied twice is a cap that will drift; a cap applied here would also hide from that function the
 * fact that it has to hold.
 *
 * A HALF-TRUE LIST IS STILL REFUSED, and that refusal is unchanged: filling the IMAP cache's
 * `preview` column for exactly the messages that happened to be NOTIFIED would light up a handful
 * of rows in the message list and leave every other row blank, which is worse than a list that
 * never promises an opening line at all. The notification path therefore still persists nothing —
 * `ImapMailService.fetchPreview` returns its text and writes no row, and `loadWatchedFolders`
 * caches its page with `preview = null`.
 *
 * What this paragraph never priced is the other way out, and it is the one taken for Codeberg
 * #187: fill the column for THE WHOLE PAGE the folder read is already holding, not for the few
 * messages a notification happened to want. `ImapMailService.previewsForPage` reads the opening of
 * every message of the page that has readable text and no cached opening yet, in one `UID FETCH`
 * per MIME section, on the session that has just selected the folder. A blank row then means the
 * message has no readable text — not that nobody notified it — which is the property the refusal
 * above was protecting, and it is what makes the list's "Preview" setting mean anything on IMAP.
 */
object BodyPreview {

    /**
     * The readable text of [raw] — the (possibly truncated) transfer-encoded octets of [part] as
     * they came off the wire, one char per octet — or null when there is nothing to show.
     *
     * Null, never "" and never a blank line: an empty preview must leave the notification with its
     * subject alone rather than an empty second line. A part that decodes to whitespace, an HTML
     * fragment that carried no text yet, a section the server answered nothing for — all of them
     * are ordinary answers, and all of them are null.
     *
     * The order is the order the message itself imposes: undo the transfer-encoding, read the
     * octets under the declared charset, and only then, if the part was HTML, flatten the markup.
     */
    fun fromPart(raw: String, part: ImapTextPart): String? {
        val bytes = MimeParser.decodeBytes(decodable(raw, part.encoding), part.encoding)
        // trimEnd, because the fragment was cut at a byte boundary and the last character may have
        // been cut in half: the decoder replaces the orphan with U+FFFD, and a notification ending
        // in a black diamond looks like a corrupted message rather than a truncated one.
        val text = String(bytes, MimeParser.charsetNamed(part.charset)).trimEnd(REPLACEMENT)
        val readable = if (part.mime == "text/html") htmlToText(closedHtml(text)) else text
        return readable.takeIf { it.isNotBlank() }
    }

    /** U+FFFD, what a decoder puts where a byte sequence it could not read used to be. */
    private const val REPLACEMENT = '�'

    /**
     * As much of [raw] as the transfer-encoding can actually decode: the fragment was cut at an
     * octet count that knows nothing of where an encoded unit ends, and BOTH encodings that carry
     * one have a unit longer than a byte.
     *
     * base64 carries three octets per four characters, so a fragment cut at 8192 octets almost
     * never ends on a quantum boundary. The JVM decoder then rejects the WHOLE string,
     * `MimeParser.decodeBytes` falls back on the raw characters — and the preview shows the base64
     * itself. Dropping the trailing partial quantum costs at most two characters of the message and
     * is the difference between the opening line and a screenful of `SGVsbG8gd29ybGQ`.
     *
     * quoted-printable — the encoding of ordinary accented mail, so the commonest case of all —
     * fails more quietly: `MimeParser.decodeQuotedPrintable` needs `=` plus two characters to read
     * an escape, so a cut inside `=C3` emits the `=` and the `C` VERBATIM, and a cut on a trailing
     * soft break leaves a bare `=`. The preview then ends in `=C` or `=`, and nothing downstream
     * removes it. Dropping a trailing `=` that has fewer than two characters after it costs at most
     * one character; a complete `=3D` or `=\r\n` at the very end has two and stays.
     */
    private fun decodable(raw: String, encoding: String?): String = when {
        encoding.equals("base64", ignoreCase = true) -> {
            val packed = raw.filter { it != '\r' && it != '\n' }
            packed.take(packed.length / 4 * 4)
        }
        encoding.equals("quoted-printable", ignoreCase = true) -> {
            val escape = raw.lastIndexOf('=')
            if (escape >= 0 && raw.length - escape <= 2) raw.substring(0, escape) else raw
        }
        else -> raw
    }

    /**
     * An HTML fragment with its cut end removed, so that flattening it cannot expose markup.
     *
     * Two cuts matter, and neither can be handled by [htmlToText] — it is a flattener, and both of
     * these are damage the truncation did, not something a sender wrote:
     *
     *  1. a `<style>`/`<script>`/`<head>` block whose closing tag is past the cut. [htmlToText]
     *     removes those blocks by matching a PAIR of tags, so an unclosed one is not removed and
     *     its stylesheet is read as text — the first 8 KB of a newsletter is very often exactly
     *     that, and the preview would be a page of CSS;
     *  2. a tag cut in the middle (`<div class="cont`). The tag stripper matches `<…>`, so an
     *     opener with no `>` survives it verbatim.
     */
    private fun closedHtml(html: String): String {
        val cut = DANGLING_BLOCK.find(html)?.range?.first ?: html.length
        val head = html.substring(0, cut)
        val opener = head.lastIndexOf('<')
        return if (opener > head.lastIndexOf('>')) head.substring(0, opener) else head
    }

    /** A `<style>`/`<script>`/`<head>` opener with no closing tag after it — i.e. one the cut
     *  swallowed. `[\s\S]` rather than the DOTALL flag: the class is explicit about spanning
     *  lines, which is what a stylesheet does. */
    private val DANGLING_BLOCK = Regex("""(?i)<(script|style|head)\b(?![\s\S]*?</\1>)""")
}
