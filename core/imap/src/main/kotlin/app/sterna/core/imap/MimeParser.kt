package app.sterna.core.imap

import java.util.Base64

/** A file attachment found while parsing a MIME message. */
data class MimeAttachment(
    /** IMAP body section path (e.g. "2" or "2.1") for a later body[section] fetch. */
    val section: String,
    val name: String,
    val type: String,
    val size: Int,
    /** Content-Transfer-Encoding (base64 / quoted-printable / 7bit…). */
    val encoding: String,
    /** Content-ID (angle brackets stripped) when the part is an inline image referenced by `cid:`. */
    val cid: String? = null,
)

/** The displayable body of a message plus any file attachments. */
data class MimeBody(
    val html: String?,
    val text: String?,
    val attachments: List<MimeAttachment> = emptyList(),
        /** The source was past [MimeParser.MAX_BODY_CHARS] and was not parsed at all. The caller must
         *  say so rather than render an empty message, nothing here was read. */
    val tooLarge: Boolean = false,
)

/** How a message carries OpenPGP content. */
enum class CryptoKind { PGP_ENCRYPTED, PGP_SIGNED, PGP_INLINE }

    /**
     * Legacy inline OpenPGP, recognised on an already-decoded body: the two armour headers a message
     */
fun inlineArmorKind(text: String): CryptoKind? =
    if (text.contains("-----BEGIN PGP MESSAGE-----") ||
        text.contains("-----BEGIN PGP SIGNED MESSAGE-----")
    ) {
        CryptoKind.PGP_INLINE
    } else {
        null
    }

/**
 * The OpenPGP-relevant pieces of a raw message, extracted without any crypto:
 * what to decrypt (armor) or verify (exact signed bytes + detached signature).
 */
data class CryptoEnvelope(
    val kind: CryptoKind,
    /** ASCII-armored ciphertext (multipart/encrypted part 2, or the inline block). */
    val encryptedArmor: String? = null,
        /** The signed MIME entity of a multipart/signed, as a verbatim substring of the raw source
         *  (headers included, boundaries excluded). Signature verification is byte-exact, so this is
         *  never re-flowed or re-encoded. */
    val signedEntityRaw: String? = null,
    /** ASCII-armored detached signature (application/pgp-signature part). */
    val signatureArmor: String? = null,
    val inlineArmorOctets: InlineArmorOctets? = null,
)

    /**
     * Minimal MIME parser: extracts the best body (text/html → text/plain, decoding transfer-encoding
     */
object MimeParser {
    /** Bound multipart nesting so a maliciously deep message can't blow the stack. */
    private const val MAX_DEPTH = 24

    /** Bound sibling parts in one multipart so a message with a flood of tiny parts
     *  can't drive quadratic copying / huge allocation. */
    const val MAX_PARTS = 1024

        /** Largest message source we will parse (25 MiB). Walking a multipart copies the body at every
         *  level, so a bigger source is an out-of-memory risk before it is a message; past this the
         *  parse is refused and the caller reports it (see [MimeBody.tooLarge]). */
    const val MAX_BODY_CHARS = 25 * 1024 * 1024

    fun parseBody(raw: String): MimeBody = parseWithTextLeaf(raw).first

    private class TextLeaf(val body: String, val cte: String, val charset: java.nio.charset.Charset) {
        fun text(): String = decode(body, cte, charset)

        fun octets(): String = String(decodeBytes(body, cte), Charsets.ISO_8859_1)
    }

    private fun parseWithTextLeaf(raw: String): Pair<MimeBody, TextLeaf?> {
        if (raw.length > MAX_BODY_CHARS) return MimeBody(null, null, emptyList(), tooLarge = true) to null
        val attachments = mutableListOf<MimeAttachment>()
        val (html, textLeaf) = walk(raw, prefix = "", attachments = attachments, depth = 0)
        return MimeBody(html, textLeaf?.text(), attachments) to textLeaf
    }

    /** (html, text leaf) for [part], text read from that leaf and never re-selected; appends attachments. */
    private fun walk(
        part: String,
        prefix: String,
        attachments: MutableList<MimeAttachment>,
        depth: Int,
    ): Pair<String?, TextLeaf?> {
        val (headerText, body) = splitHeaders(part)
        val headers = parseHeaders(headerText)
        val contentType = headers["content-type"] ?: "text/plain"
        val mime = contentType.substringBefore(';').trim().lowercase()
        val cte = headers["content-transfer-encoding"]?.substringBefore(';')?.trim()?.lowercase() ?: "7bit"
        val disposition = headers["content-disposition"] ?: ""
            // Decoded, not raw: an attachment called "Отчёт.pdf" travels as an RFC 2231 parameter or an
            // RFC 2047 word, and both would reach the screen verbatim (Codeberg #101). Display name
            // only, what lands on disk is sanitised separately.
        val filename = mimeParamValue(disposition, "filename") ?: mimeParamValue(contentType, "name")

        if (mime.startsWith("multipart/")) {
            if (depth >= MAX_DEPTH) return null to null
            val boundary = paramOf(contentType, "boundary") ?: return null to null
            var html: String? = null
            var text: TextLeaf? = null
            splitMultipart(body, boundary).forEachIndexed { index, sub ->
                val childPrefix = if (prefix.isEmpty()) "${index + 1}" else "$prefix.${index + 1}"
                val (h, t) = walk(sub, childPrefix, attachments, depth + 1)
                if (html == null) html = h
                if (text == null) text = t
            }
            return html to text
        }

        val section = prefix.ifEmpty { "1" }
        val cid = headers["content-id"]?.trim()?.trim('<', '>')?.takeIf { it.isNotBlank() }
        val isAttachment = !filename.isNullOrBlank() || disposition.lowercase().contains("attachment")
        // An inline image with no filename/attachment disposition (referenced only by Content-ID)
        // would otherwise be dropped; keep it as a fetchable part so it can render / be forwarded.
        val isInlineImage = cid != null && mime.startsWith("image/")
        return when {
            isAttachment -> {
                attachments.add(MimeAttachment(section, filename ?: "attachment", mime, body.length, cte, cid))
                null to null
            }
            isInlineImage -> {
                attachments.add(MimeAttachment(section, filename ?: "image", mime, body.length, cte, cid))
                null to null
            }
            mime == "message/rfc822" -> {
                attachments.add(MimeAttachment(section, filename ?: "message.eml", mime, body.length, cte))
                null to null
            }
            mime == "text/html" -> decode(body, cte, charsetOf(contentType)) to null
            mime == "text/plain" -> null to TextLeaf(body, cte, charsetOf(contentType))
            // A calendar invite (text/calendar, e.g. Method:request) is usually an inline
            // part with no filename/disposition, so it would otherwise be lost here. Capture
            // it as a downloadable part (named invite.ics) so the reader can fetch + preview it.
            mime.startsWith("text/calendar") -> {
                attachments.add(MimeAttachment(section, filename ?: "invite.ics", mime, body.length, cte))
                null to null
            }
                // PGP/MIME control parts (version / detached signature) have no filename, so they would
                // otherwise vanish. Kept as typed parts: the reader uses their presence to detect crypto
                // uniformly across IMAP and JMAP, and they are filtered out of the visible list.
            mime == "application/pgp-encrypted" || mime == "application/pgp-signature" -> {
                attachments.add(
                    MimeAttachment(section, filename ?: mime.substringAfter('/'), mime, body.length, cte),
                )
                null to null
            }
            else -> null to null
        }
    }

    /** A top-level header value of a raw message (unfolded); [name] is case-insensitive. */
    fun headerOf(raw: String, name: String): String? =
        parseHeaders(headerBlockOf(raw))[name.lowercase()]

        /**
         * The header block of [part], and nothing else, exactly what `splitHeaders(part).first`
         */
    private fun headerBlockOf(part: String): String {
        val sep = Regex("\\r?\\n\\r?\\n").find(part) ?: return part
        return part.substring(0, sep.range.first)
    }

        /**
         * The same header, ready to show: [headerOf] put through [decodeWords]. It lives here because
         */
    fun decodedHeaderOf(raw: String, name: String): String? = decodeWords(headerOf(raw, name))

        /**
         * The same door as [decodedHeaderOf], for a value already separated from its message. A second
         */
    fun decodedHeaderValue(value: String?): String? = decodeWords(value)

        /**
         * Every top-level header field of a raw message, in original order with duplicates kept
         */
    fun rawHeaders(raw: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val sb = StringBuilder()
        fun flush() {
            val line = sb.toString()
            val colon = line.indexOf(':')
            if (colon > 0) {
                out.add(line.substring(0, colon).trim() to decodeHeaderBytes(line.substring(colon + 1).trim()))
            }
            sb.clear()
        }
        for (line in splitHeaders(raw).first.split(Regex("\\r?\\n"))) {
            if (line.isEmpty()) continue
            if (line[0] == ' ' || line[0] == '\t') {
                sb.append(' ').append(line.trim()) // folded continuation
            } else {
                if (sb.isNotEmpty()) flush()
                sb.append(line)
            }
        }
        if (sb.isNotEmpty()) flush()
        return out
    }

        /** The still-transfer-encoded body + Content-Transfer-Encoding of the part at [section]
         *  ("1", "2.1", …) inside a raw MIME entity, the same section paths [parseBody] assigns.
         *  Pulls attachments out of a decrypted entity. */
    fun partAt(raw: String, section: String): Pair<String, String>? {
        var current = raw
        val indices = section.split('.').map { it.toIntOrNull() ?: return null }
        for ((i, idx) in indices.withIndex()) {
            val (headerText, body) = splitHeaders(current)
            val contentType = parseHeaders(headerText)["content-type"] ?: "text/plain"
            val mime = contentType.substringBefore(';').trim().lowercase()
            if (mime.startsWith("multipart/")) {
                val boundary = paramOf(contentType, "boundary") ?: return null
                current = splitMultipart(body, boundary).getOrNull(idx - 1) ?: return null
            } else {
                // Leaf part: only addressable as the final ".1".
                if (idx != 1 || i != indices.lastIndex) return null
            }
        }
        val (headerText, body) = splitHeaders(current)
        val cte = parseHeaders(headerText)["content-transfer-encoding"]
            ?.substringBefore(';')?.trim()?.lowercase() ?: "7bit"
        return cte to body
    }

        /** Detect OpenPGP content in a raw RFC 5322 message (RFC 3156 PGP/MIME, plus legacy inline
         *  armor in a plain-text body). Returns null for ordinary mail. */
    fun detectCrypto(raw: String): CryptoEnvelope? {
        val (headerText, body) = splitHeaders(raw)
        val headers = parseHeaders(headerText)
        val contentType = headers["content-type"] ?: "text/plain"
        val mime = contentType.substringBefore(';').trim().lowercase()
        val protocol = paramOf(contentType, "protocol")?.lowercase()
        val boundary = paramOf(contentType, "boundary")

        if (mime == "multipart/encrypted" && boundary != null) {
            val parts = splitMultipart(body, boundary)
            val hasVersionPart = protocol == "application/pgp-encrypted" ||
                parts.any { partMime(it) == "application/pgp-encrypted" }
            if (hasVersionPart) {
                // The ciphertext part: application/octet-stream per RFC 3156 §4,
                // falling back to the second part for lax producers.
                val cipherPart = parts.firstOrNull { partMime(it) == "application/octet-stream" }
                    ?: parts.getOrNull(1)
                val armor = cipherPart?.let { decodedPartBody(it) }?.takeIf {
                    it.contains("-----BEGIN PGP MESSAGE-----")
                } ?: return null
                return CryptoEnvelope(CryptoKind.PGP_ENCRYPTED, encryptedArmor = armor)
            }
        }

        if (mime == "multipart/signed" && boundary != null) {
            val looksPgp = protocol == "application/pgp-signature" ||
                splitMultipart(body, boundary).any { partMime(it) == "application/pgp-signature" }
            if (looksPgp) {
                val signedRaw = rawFirstPart(body, boundary) ?: return null
                val sigPart = splitMultipart(body, boundary)
                    .firstOrNull { partMime(it) == "application/pgp-signature" } ?: return null
                val sigArmor = decodedPartBody(sigPart).takeIf {
                    it.contains("-----BEGIN PGP SIGNATURE-----")
                } ?: return null
                return CryptoEnvelope(
                    CryptoKind.PGP_SIGNED,
                    signedEntityRaw = signedRaw,
                    signatureArmor = sigArmor,
                )
            }
        }

        // Legacy inline PGP: an armored block inside the displayable text body.
        val (parsed, leaf) = parseWithTextLeaf(raw)
        val text = parsed.text ?: return null
        val armor = extractInlineArmor(text) ?: return null
        val octets = leaf?.let { part ->
            extractInlineArmor(part.octets())?.let { InlineArmorOctets(it, part.charset) }
        }
        return CryptoEnvelope(CryptoKind.PGP_INLINE, encryptedArmor = armor, inlineArmorOctets = octets)
    }

    /** The lowercased content type of a multipart child (its own headers). */
    private fun partMime(part: String): String {
        val (headerText, _) = splitHeaders(part)
        val ct = parseHeaders(headerText)["content-type"] ?: "text/plain"
        return ct.substringBefore(';').trim().lowercase()
    }

    /** A multipart child's body decoded per its Content-Transfer-Encoding (as text). */
    private fun decodedPartBody(part: String): String {
        val (headerText, body) = splitHeaders(part)
        val headers = parseHeaders(headerText)
        val cte = headers["content-transfer-encoding"]?.substringBefore(';')?.trim()?.lowercase() ?: "7bit"
        return decode(body, cte, Charsets.UTF_8)
    }

        /**
         * The first part of a multipart body as a verbatim substring: from just after the line break
         */
    private fun rawFirstPart(body: String, boundary: String): String? {
        val delimiter = "--$boundary"
        var open = body.indexOf(delimiter)
        while (open >= 0 && !isBoundaryLine(body, open, delimiter)) open = body.indexOf(delimiter, open + 1)
        if (open < 0) return null
        var start = open + delimiter.length
        while (start < body.length && body[start] != '\n') start++
        if (start >= body.length) return null
        start++
        var close = body.indexOf(delimiter, start)
        while (close >= 0 && !isBoundaryLine(body, close, delimiter)) close = body.indexOf(delimiter, close + 1)
        if (close < 0) return null
        // The CRLF (or LF) before the closing delimiter belongs to the boundary.
        var end = close - 1
        if (end > start && body[end - 1] == '\r') end--
        return if (end > start) body.substring(start, end) else null
    }

    /** Kept out of `splitMultipart`, which splits every multipart body and would cut them differently. */
    private fun isBoundaryLine(body: String, at: Int, delimiter: String): Boolean {
        if (at > 0 && body[at - 1] != '\n') return false
        var i = at + delimiter.length
        while (i < body.length && (body[i] == ' ' || body[i] == '\t')) i++
        return i == body.length || body[i] == '\r' || body[i] == '\n'
    }

    /** The first complete armored PGP block in [text], or null. */
    private fun extractInlineArmor(text: String): String? {
        for (kind in listOf("MESSAGE", "SIGNED MESSAGE")) {
            val begin = "-----BEGIN PGP $kind-----"
            val start = text.indexOf(begin)
            if (start < 0) continue
            // A cleartext-signed block ends with the signature's end line.
            val endMarker =
                if (kind == "SIGNED MESSAGE") "-----END PGP SIGNATURE-----" else "-----END PGP MESSAGE-----"
            val end = text.indexOf(endMarker, start)
            if (end < 0) continue
            return text.substring(start, end + endMarker.length)
        }
        return null
    }

        /**
         * Decode a fetched part body (body[section]) to raw bytes using its transfer-encoding.
         */
    fun decodeBytes(content: String, encoding: String?): ByteArray = when (encoding?.lowercase()) {
        "base64" -> runCatching {
            Base64.getMimeDecoder().decode(content.filter { it != '\r' && it != '\n' })
        }.getOrDefault(content.toByteArray(Charsets.ISO_8859_1))
        "quoted-printable" -> decodeQuotedPrintable(content, Charsets.ISO_8859_1).toByteArray(Charsets.ISO_8859_1)
        else -> content.toByteArray(Charsets.ISO_8859_1)
    }

    private fun splitHeaders(part: String): Pair<String, String> {
        val sep = Regex("\\r?\\n\\r?\\n").find(part)
        return if (sep == null) part to "" else part.substring(0, sep.range.first) to part.substring(sep.range.last + 1)
    }

    private fun parseHeaders(headerText: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val sb = StringBuilder()
        fun flush() {
            val line = sb.toString()
            val colon = line.indexOf(':')
            if (colon > 0) map[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            sb.clear()
        }
        for (raw in headerText.split(Regex("\\r?\\n"))) {
            if (raw.isEmpty()) continue
            if (raw[0] == ' ' || raw[0] == '\t') {
                sb.append(' ').append(raw.trim()) // folded continuation
            } else {
                if (sb.isNotEmpty()) flush()
                sb.append(raw)
            }
        }
        if (sb.isNotEmpty()) flush()
        return map
    }

    private fun paramOf(headerValue: String, name: String): String? {
        val m = Regex("$name\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(headerValue)
        return m?.groupValues?.get(1)?.trim()
    }

        /**
         * The charset a part declared, as a [java.nio.charset.Charset]: UTF-8 when it declared none,
         * and UTF-8 when it declared one this JVM does not know, the fallback that has always been
         * applied here, in one place.
         */
    fun charsetNamed(name: String?): java.nio.charset.Charset =
        runCatching { charset(name?.takeIf { it.isNotBlank() } ?: "utf-8") }.getOrDefault(Charsets.UTF_8)

    private fun charsetOf(contentType: String): java.nio.charset.Charset =
        charsetNamed(paramOf(contentType, "charset"))

    /**
     * The children of a multipart body, at most [MAX_PARTS] of them. What precedes the first occurrence
     * of the delimiter is dropped, as the IMAP server numbers no preamble; with no delimiter at all the
     * body stays one part rather than vanish.
     */
    private fun splitMultipart(body: String, boundary: String): List<String> {
        val delimiter = "--$boundary"
        val parts = mutableListOf<String>()
        val first = body.indexOf(delimiter)
        var start = if (first < 0) 0 else first + delimiter.length
        while (parts.size < MAX_PARTS) {
            val next = body.indexOf(delimiter, start)
            val end = if (next < 0) body.length else next
            var from = start
            while (from < end && (body[from] == '\r' || body[from] == '\n')) from++
            if (from < end && !body.startsWith("--", from)) parts.add(body.substring(from, end))
            if (next < 0) break
            start = next + delimiter.length
        }
        return parts
    }

        /**
         * A part's body as text: undo the transfer-encoding, then read the resulting bytes with the
         * part's declared [charset].
         */
    private fun decode(body: String, cte: String, charset: java.nio.charset.Charset): String = when (cte) {
        "base64" -> runCatching {
            String(Base64.getMimeDecoder().decode(body.filter { it != '\r' && it != '\n' }), charset)
        }.getOrDefault(body)
        "quoted-printable" -> decodeQuotedPrintable(body, charset)
        else -> if (isAsciiUnder(body, charset)) body else String(body.toByteArray(Charsets.ISO_8859_1), charset)
    }

        /**
         * Whether re-reading [body]'s octets under [charset] is guaranteed to return [body] itself, so
         */
    private fun isAsciiUnder(body: String, charset: java.nio.charset.Charset): Boolean =
        (charset == Charsets.UTF_8 || charset == Charsets.US_ASCII || charset == Charsets.ISO_8859_1) &&
            body.none { it.code >= 0x80 }

    private fun decodeQuotedPrintable(input: String, charset: java.nio.charset.Charset): String {
        val out = ArrayList<Byte>(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c == '=' && i + 1 < input.length && (input[i + 1] == '\r' || input[i + 1] == '\n') -> {
                    i++
                    if (i < input.length && input[i] == '\r') i++
                    if (i < input.length && input[i] == '\n') i++
                }
                c == '=' && i + 2 < input.length -> {
                    val byte = input.substring(i + 1, i + 3).toIntOrNull(16)
                    if (byte != null) {
                        out.add(byte.toByte()); i += 3
                    } else {
                        out.add(c.code.toByte()); i++
                    }
                }
                else -> { out.add(c.code.toByte()); i++ }
            }
        }
        return String(out.toByteArray(), charset)
    }
}
