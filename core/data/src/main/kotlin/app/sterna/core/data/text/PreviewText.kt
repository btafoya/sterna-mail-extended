package app.sterna.core.data.text

import app.sterna.core.jmap.model.withoutBidiAndControls

/**
 * A message's stored or freshly read `preview` as it may be shown, or null when nothing visible is
 * left: entities decoded, the invisible padding senders put after their first line removed (U+034F,
 * U+200B to U+200D, the byte order mark), then the bidi filter, last so an encoded `&#x202E;` is
 * caught too.
 *
 * Once per path, because the decoding is one pass: applied twice, `&amp;amp;` reads `&`. The cache
 * mappers (`EmailEntity.toEmail`, `FtsHit.toEmail`) call it on every row read, the notification
 * pre-pass on the openings it reads itself; nothing downstream of either may call it again.
 *
 * The server cuts a preview wherever its limit falls, mid-reference included, and may close it with
 * its own dots. A reference left unfinished there comes off first, and off the raw text: `&amp;zw` is
 * the text `&zw`. The dots stay, they say the text goes on; left alone once whatever stood in front
 * of them is gone, they are no preview. Dots that are the whole raw text are the sender's.
 *
 * No surface has to flatten for itself: every run of blanks comes out as one space and the ends
 * carry none. Removing the padding is what makes the long runs, so the folding follows it. It runs
 * on both sides of the bidi filter: that filter drops a carriage return, which would join two words,
 * and what it removes between two blanks leaves them side by side.
 */
fun previewForDisplay(raw: String): String? {
    val uncut = raw.replace(CUT_REFERENCE, "")
    val decoded = unescapeEntities(uncut)
    val unpadded = decoded.filterNot(::isInvisiblePadding)
    val shown = withBlankRunsFolded(withoutBidiAndControls(withBlankRunsFolded(unpadded)))
    return shown.takeUnless { it.isEmpty() || isRawMimePreview(it) || (it in CLOSING_DOTS && raw.trim() != it) }
}

/**
 * A lone `&`, a numeric reference begun, or a prefix of `zwnj`, `zwj` or `nbsp`, at the very end or
 * right before the dots that close the preview. No other name: `R&D` closing a preview is the
 * sender's text.
 */
private val CUT_REFERENCE =
    Regex("&(?:#(?:[0-9]{0,7}|[xX][0-9a-fA-F]{0,6})|z(?:w(?:nj?|j)?)?|n(?:b(?:sp?)?)?)?(?=(?:\\.\\.\\.|…)?$)")

/**
 * Whether [text] is the head of a MIME body rather than its words (#262): a boundary line, then a part
 * header, with or without a blank between them. The header has to follow the boundary token at once:
 * `--Bonjour, le serveur renvoie Content-Type: …` is a sentence, and a token of dashes alone a rule.
 *
 * A cached one is read again (`previewIdsToRead`) but stays in the column until a read replaces it,
 * since `pageOpenings` writes the cache's value for every row the read left out; the display hides it.
 */
fun isRawMimePreview(text: String): Boolean = RAW_MIME_HEAD.containsMatchIn(text)

private val RAW_MIME_HEAD = Regex(
    "^\\s*--(?=-*[^\\s;\"-])[^\\s;\"]+?\\s*Content-(?:Type|Transfer-Encoding|Disposition):",
    RegexOption.IGNORE_CASE,
)

private val CLOSING_DOTS = setOf("...", "…")

private fun isInvisiblePadding(c: Char): Boolean =
    c == '\u034F' || c in '\u200B'..'\u200D' || c == '\uFEFF'

/**
 * Tab to carriage return, and every Unicode space separator, U+00A0 included: a regex `\s` stops at
 * ASCII on the JVM and would leave the no-break spaces a server decoded standing in a row.
 */
private fun isBlankChar(c: Char): Boolean = c in '\t'..'\r' || Character.isSpaceChar(c)

private fun withBlankRunsFolded(s: String): String = buildString(s.length) {
    var pending = false
    for (c in s) {
        if (isBlankChar(c)) {
            pending = isNotEmpty()
        } else {
            if (pending) append(' ')
            pending = false
            append(c)
        }
    }
}
