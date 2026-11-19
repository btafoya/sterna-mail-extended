package app.sterna.core.jmap.model

import java.io.ByteArrayOutputStream

/**
 * The key a `cid:` URL names among a message's inline images: percent-decoded, since the WebView
 * hands `cid:<x>` over as `cid:%3Cx%3E`, then trimmed of spaces and angle brackets the way
 * [partContentId] trims a part's header. The identifier itself stays case-sensitive. Null when [url]
 * is not a `cid:` URL.
 */
fun cidUrlContentId(url: String): String? {
    if (!url.regionMatches(0, "cid:", 0, 4, ignoreCase = true)) return null
    return percentDecode(url.substring(4)).trim().trim('<', '>')
}

/** A part's `Content-ID` as the key its image is served under, or null when it has none. */
fun partContentId(cid: String?): String? = cid?.trim()?.trim('<', '>')?.takeIf { it.isNotEmpty() }

/**
 * Every Content-ID [html] points at through a `cid:` URL, in an `<img>` or anywhere else (a CSS
 * `url(cid:…)` counts), each as [cidUrlContentId] reads it. A URL ends at a quote, a blank, `)` or
 * `>`; a `>` ending one written `cid:<x>` costs nothing, the brackets being trimmed anyway.
 *
 * A `cid:` glued to a preceding letter or digit (`acid:`) is not a URL. Missing a citation lists an
 * image the body then draws as a hole; inventing one hides a photo the body never shows, leaving it
 * nowhere on screen, so the scan errs towards missing.
 */
fun citedContentIds(html: String?): Set<String> {
    if (html.isNullOrEmpty()) return emptySet()
    val cited = mutableSetOf<String>()
    var from = 0
    while (true) {
        val at = html.indexOf("cid:", from, ignoreCase = true)
        if (at < 0) break
        from = at + 4
        if (at > 0 && html[at - 1].isLetterOrDigit()) continue
        var end = from
        while (end < html.length && html[end] !in URL_ENDS && !html[end].isWhitespace()) end++
        cidUrlContentId(html.substring(at, end))?.takeIf { it.isNotEmpty() }?.let(cited::add)
        from = end
    }
    return cited
}

/**
 * Whether a part is an image of the body rather than a file to list: an image whose Content-ID the
 * message's HTML cites. One with a Content-ID the HTML never names (Gmail gives every attached photo
 * one) is shown nowhere else than in the attachment list, so it must be listed.
 */
fun isBodyImage(type: String?, cid: String?, cited: Set<String>): Boolean =
    type?.startsWith("image/") == true && partContentId(cid)?.let { it in cited } == true

private val URL_ENDS = charArrayOf('"', '\'', ')', '>')

/** `%XX` sequences decoded as UTF-8; a malformed escape is kept as written. `+` stays a `+`. */
private fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val out = ByteArrayOutputStream(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        val hi = if (c == '%' && i + 2 < s.length) Character.digit(s[i + 1], 16) else -1
        val lo = if (hi >= 0) Character.digit(s[i + 2], 16) else -1
        if (hi >= 0 && lo >= 0) {
            out.write(hi * 16 + lo)
            i += 3
        } else {
            val bytes = c.toString().toByteArray(Charsets.UTF_8)
            out.write(bytes, 0, bytes.size)
            i++
        }
    }
    return out.toString(Charsets.UTF_8.name())
}
