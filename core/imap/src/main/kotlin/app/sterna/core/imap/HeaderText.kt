package app.sterna.core.imap

import java.util.Base64

    /**
     * Turning header bytes into text a human can read: RFC 2047 encoded-words, RFC 2231 parameter
     * values, and the anti-spoofing filter every decoded string passes through. File-level rather than
     * private to `ImapSession` because there is exactly ONE encoded-word decoder in this app and both
     * readers need it — attachment filenames used to show their raw `=?…?=` (Codeberg #101).
     */

/** One RFC 2047 encoded-word: `=?charset?B|Q?text?=`. */
private val ENCODED_WORD = Regex("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=")

    /**
     * Decode RFC 2047 encoded-words in a header. A word whose charset is unknown, or whose payload
     * will not decode, is KEPT VERBATIM: a malformed subject must survive as text, not disappear.
     *
     * WHITESPACE BETWEEN TWO ADJACENT ENCODED-WORDS IS DROPPED (RFC 2047 §6.2, Codeberg #123): the
     * separator a sender must insert to stay under 75 octets is syntax, not text, and keeping it made
     * "Réunion de rentrée" read "Réunion de ren trée". Hence a single scan and not a `replace {}` — a
     * per-word callback never sees what separates two words. Both conditions are checked here: the gap
     * is entirely linear whitespace, and it is flanked by an encoded-word on BOTH sides. A word that
     * failed to decode still counts as a word, §6.2 ignoring the separator at PARSE time. Only the
     * SOURCE gap is examined; a space a sender encoded INSIDE a word is content. [decodeHeaderBytes]
     * runs FIRST, an encoded-word being pure ASCII and passing through it untouched.
     */
internal fun decodeWords(text: String?): String? {
    if (text == null) return null
    val source = decodeHeaderBytes(text)
    val out = StringBuilder(source.length)
    var cursor = 0
    var afterWord = false
    for (m in ENCODED_WORD.findAll(source)) {
        val gap = source.substring(cursor, m.range.first)
        val isSeparator = afterWord && gap.isNotEmpty() && gap.all(::isLinearWhitespace)
        if (!isSeparator) out.append(gap)
        out.append(decodeWord(m))
        cursor = m.range.last + 1
        afterWord = true
    }
    out.append(source, cursor, source.length)
    return stripBidiAndControls(out.toString()).trim()
}

/** RFC 5322 linear whitespace: a space, a tab, or the CRLF of a folded header line. */
private fun isLinearWhitespace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\r' || c == '\n'

/** One matched encoded-word as text — or, if it will not decode, exactly as it was sent. */
private fun decodeWord(m: MatchResult): String {
    val cs = charsetOrUtf8(m.groupValues[1].substringBefore('*'))
    val enc = m.groupValues[2].uppercase()
    val data = m.groupValues[3]
    return runCatching {
        val bytes = if (enc == "B") {
            Base64.getMimeDecoder().decode(data)
        } else {
            decodeQ(data)
        }
        String(bytes, cs)
    }.getOrDefault(m.value)
}

    /**
     * Turn a header the parser handed over as a byte container into text, for the case RFC 2047 exists
     * to avoid and senders use anyway: 8-bit bytes sitting raw in a `Subject` or a display name. A
     * header declares no charset, so valid UTF-8 is taken as UTF-8 (RFC 6532) and anything else keeps
     * its ISO-8859-1 reading, which never destroys an octet. This has to happen BEFORE
     * [stripBidiAndControls], which removes U+0080–U+009F — precisely where UTF-8 continuation bytes
     * land when read one per char.
     */
internal fun decodeHeaderBytes(text: String): String {
    if (text.none { it.code >= 0x80 }) return text
    // A string holding real text rather than octets (chars past U+00FF) is not a byte container
    // and has nothing to reinterpret — never round-trip it, ISO-8859-1 would write '?' over it.
    if (text.any { it.code > 0xFF }) return text
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    return runCatching {
        decoder.decode(java.nio.ByteBuffer.wrap(text.toByteArray(Charsets.ISO_8859_1))).toString()
    }.getOrDefault(text)
}

    /**
     * Remove control characters and Unicode bidi overrides from a decoded header before it is shown: a
     * crafted display name can otherwise make "moc.knab@troppus" read as a bank address. NEVER hand
     * this raw non-ASCII bytes read one per char — it strips U+0080–U+009F, where UTF-8 continuation
     * bytes land, and the damage cannot be undone.
     */
internal fun stripBidiAndControls(s: String): String = s.filterNot { c ->
    val code = c.code
    (code in 0x00..0x08) || (code in 0x0B..0x1F) || (code in 0x7F..0x9F) ||
        (code in 0x202A..0x202E) || (code in 0x2066..0x2069) || code == 0x200F || code == 0x200E
}

internal fun decodeQ(data: String): ByteArray {
    val out = ArrayList<Byte>(data.length)
    var i = 0
    while (i < data.length) {
        when (val c = data[i]) {
            '_' -> { out.add(' '.code.toByte()); i++ }
            '=' -> {
                val hex = data.substring(i + 1, (i + 3).coerceAtMost(data.length))
                val byte = if (hex.length == 2) hex.toIntOrNull(16) else null
                if (byte != null) { out.add(byte.toByte()); i += 3 }
                else { out.add('='.code.toByte()); i++ } // dangling/invalid escape: keep literal '='
            }
            else -> { out.add(c.code.toByte()); i++ }
        }
    }
    return out.toByteArray()
}

/** Longest decoded parameter value kept. A filename is a label, not a payload. */
private const val MAX_PARAM_CHARS = 1024

/** Continuation sections read for one parameter (RFC 2231 §3), enough for any real filename. */
private const val MAX_PARAM_SECTIONS = 64

    /**
     * The value of MIME parameter [name] in [headerValue], decoded and safe to display — the
     * "Отчёт.pdf" an attachment is really called (Codeberg #101). Three shapes are in the wild and all
     * three are handled: `filename="plain.pdf"`; `filename="=?utf-8?B?…?="`, an RFC 2047 word where
     * the standard allows none but many senders emit one; and RFC 2231's `filename*=UTF-8''%D0%9E…`,
     * reassembled in index order and percent-decoded against the charset the first section declares.
     * Unknown charset falls back to UTF-8, the result is capped, and it goes through
     * [stripBidiAndControls] — an RTL override reads "…gpj.exe" as "…exe.jpg". NOT a path: what
     * lands on disk is sanitised separately.
     */
internal fun mimeParamValue(headerValue: String, name: String): String? {
    if (headerValue.isBlank()) return null
    val wanted = name.lowercase()
    val plain = mutableListOf<String>()
    // section index -> (value, extended?)
    val sections = sortedMapOf<Int, Pair<String, Boolean>>()
    var extendedSingle: String? = null

    for ((key, value) in headerParams(headerValue)) {
        val extended = key.endsWith("*")
        val base = if (extended) key.dropLast(1) else key
        when {
            base == wanted && !extended -> plain += value
            base == wanted && extended -> extendedSingle = value
            base.startsWith("$wanted*") -> {
                val index = base.removePrefix("$wanted*").toIntOrNull() ?: continue
                if (index in 0 until MAX_PARAM_SECTIONS) sections[index] = value to extended
            }
        }
    }

    val raw = when {
        sections.isNotEmpty() -> joinSections(sections)
        extendedSingle != null -> decodeExtended(extendedSingle)
        plain.isNotEmpty() -> decodeWords(plain.first())
        else -> null
    } ?: return null
    return stripBidiAndControls(raw).trim().take(MAX_PARAM_CHARS).takeIf { it.isNotEmpty() }
}

    /**
     * Reassemble RFC 2231 continuations. Only the FIRST section carries `charset'lang'`. Sections are
     * decoded as BYTES and joined at the end, never per section: a multi-byte character may straddle
     * the split, and decoding each part alone would turn it into two replacement characters.
     */
private fun joinSections(sections: Map<Int, Pair<String, Boolean>>): String? {
    var cs = Charsets.UTF_8
    val bytes = java.io.ByteArrayOutputStream()
    var first = true
    for ((_, entry) in sections) {
        val (value, extended) = entry
        var text = value
        if (extended && first) {
            val parts = value.split('\'', limit = 3)
            if (parts.size == 3) {
                cs = charsetOrUtf8(parts[0])
                text = parts[2]
            }
        }
        val chunk = if (extended) percentDecode(text) else text.toByteArray(cs)
        bytes.write(chunk, 0, chunk.size)
        first = false
    }
    return String(bytes.toByteArray(), cs).takeIf { it.isNotEmpty() }
}

/** One `charset'lang'percent-encoded-text` value (RFC 2231 §4). */
private fun decodeExtended(value: String): String {
    val parts = value.split('\'', limit = 3)
    if (parts.size < 3) return decodeWords(value) ?: value
    return String(percentDecode(parts[2]), charsetOrUtf8(parts[0]))
}

/** A named charset, or UTF-8 — an unknown one must not cost us the value (see [decodeWords]). */
private fun charsetOrUtf8(name: String): java.nio.charset.Charset =
    runCatching { charset(name) }.getOrDefault(Charsets.UTF_8)

private fun percentDecode(text: String): ByteArray {
    val out = java.io.ByteArrayOutputStream(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '%' && i + 3 <= text.length) {
            val hex = text.substring(i + 1, i + 3)
            val byte = hex.toIntOrNull(16)
            if (byte != null) {
                out.write(byte)
                i += 3
                continue
            }
        }
        // Not an escape: the character's own bytes (parameters are ASCII, but be literal about it).
        out.write(c.toString().toByteArray(Charsets.UTF_8))
        i++
    }
    return out.toByteArray()
}

    /**
     * Split a header value into its `name=value` parameters, lowercased names, quotes and backslash
     * escapes removed. Tokenised rather than matched with one regex per name, because `filename`
     * CONTAINS `name` and a regex for `name=` finds it inside `filename=`. Semicolons inside a quoted
     * value do not split either.
     */
private fun headerParams(headerValue: String): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    val segment = StringBuilder()
    var quoted = false
    var i = 0
    fun flush() {
        val text = segment.toString().trim()
        segment.clear()
        val eq = text.indexOf('=')
        if (eq <= 0) return
        val key = text.substring(0, eq).trim().lowercase()
        var value = text.substring(eq + 1).trim()
        if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
            value = value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        }
        out += key to value
    }
    while (i < headerValue.length) {
        val c = headerValue[i]
        when {
            c == '\\' && quoted && i + 1 < headerValue.length -> { segment.append(c).append(headerValue[i + 1]); i++ }
            c == '"' -> { quoted = !quoted; segment.append(c) }
            c == ';' && !quoted -> flush()
            else -> segment.append(c)
        }
        i++
    }
    flush()
    return out
}
