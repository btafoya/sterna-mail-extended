package app.sterna.core.imap

import java.util.Base64

    /**
     * Modified UTF-7, the encoding IMAP mailbox names travel in (RFC 3501 §5.1.3). A folder called
     * "Помеченные" is announced by `LIST` as `&BB8EPgQ8BDUERwQ1BD0EPQRLBDU-`, and the drawer used to
     * show that (Codeberg #101). Decode and encode are ONE pair: `SELECT` used to work precisely
     * because nothing was decoded, so decoding alone makes every non-ASCII folder readable and
     * unopenable. Above this line the app holds real Unicode — mixing the two representations is the
     * silent failure mode, a decoded id compared against a raw path matching nothing.
     *
     * `UTF8=ACCEPT` (RFC 6855) is deliberately NOT consulted: a server advertising it still speaks
     * modified UTF-7 until the client sends `ENABLE UTF8=ACCEPT`, so unconditional encoding is correct
     * everywhere and needs no negotiation.
     */

/** The base64 alphabet of modified UTF-7: standard, with `,` where base64 has `/`. */
private const val MODIFIED_BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+,"

/** Printable US-ASCII represents itself; everything else is shifted into base64. */
private fun Char.isDirect(): Boolean = code in 0x20..0x7E && this != '&'

private fun Char.isModifiedBase64(): Boolean = this in MODIFIED_BASE64

    /**
     * A Unicode mailbox name as it must appear on the wire. Pure ASCII (bar `&`) comes back untouched,
     * so an English folder still produces the exact bytes it always did; `&` becomes `&-`, and any run
     * of other characters is UTF-16BE base64 between `&` and `-`. The result is pure ASCII, so [quote]
     * remains a sufficient guard afterwards. UTF-16BE is written out by hand rather than through a
     * [Charsets] encoder: a name holding a lone surrogate would have the JVM substitute `?`,
     * irreversibly, and the folder would no longer open.
     */
fun encodeModifiedUtf7(name: String): String {
    if (name.all { it.isDirect() }) return name
    val out = StringBuilder(name.length + 8)
    var i = 0
    while (i < name.length) {
        val c = name[i]
        when {
            c == '&' -> { out.append("&-"); i++ }
            c.isDirect() -> { out.append(c); i++ }
            else -> {
                val start = i
                while (i < name.length && !name[i].isDirect() && name[i] != '&') i++
                out.append('&').append(base64Utf16(name, start, i)).append('-')
            }
        }
    }
    return out.toString()
}

    /**
     * A mailbox name as `LIST` announced it, turned back into Unicode. A malformed shift sequence
     * SURVIVES AS TEXT rather than vanishing: a name is an identifier, and dropping what we failed to
     * read would hand the app a path addressing a different mailbox. A shift ends at `-` (absorbed) or
     * at the first character outside the modified base64 alphabet (not absorbed), per RFC 2152 — the
     * hierarchy delimiter is in neither alphabet, so a whole path decodes in one pass.
     */
fun decodeModifiedUtf7(name: String): String {
    if ('&' !in name) return name
    val out = StringBuilder(name.length)
    var i = 0
    while (i < name.length) {
        val c = name[i]
        if (c != '&') {
            out.append(c)
            i++
            continue
        }
        var end = i + 1
        while (end < name.length && name[end].isModifiedBase64()) end++
        val chunk = name.substring(i + 1, end)
        val next = if (end < name.length && name[end] == '-') end + 1 else end
        when {
            // "&-" is a literal ampersand; a trailing "&" is one too.
            chunk.isEmpty() -> out.append('&')
            else -> {
                val decoded = decodeBase64Utf16(chunk)
                if (decoded == null) out.append(name, i, next) else out.append(decoded)
            }
        }
        i = next
    }
    return out.toString()
}

    /**
     * A mailbox path as the app will hold it: the decoding when it is faithful, the RAW WIRE FORM when
     * it is not. THE identity function for a folder, and the one every persisted id must pass through.
     *
     * Decoding is only allowed to happen when it can be undone. Modified UTF-7 has corners that do not
     * round-trip — a bare `&` that was never a shift (`R&D`, written by a version of this app that
     * sent folder names raw) and a shift whose base64 is not valid UTF-16 — and both re-encode as `&-`,
     * a DIFFERENT mailbox. So: decode, re-encode, keep the decoding only if it comes back byte for
     * byte. A name we cannot read is a nuisance; a name we cannot address is a broken folder.
     */
fun decodeMailboxPath(raw: String): String {
    val decoded = decodeModifiedUtf7(raw)
    return if (encodeModifiedUtf7(decoded) == raw) decoded else raw
}

/** UTF-16BE bytes of `name[from until to]`, base64 without padding, `/` written as `,`. */
private fun base64Utf16(name: String, from: Int, to: Int): String {
    val bytes = ByteArray((to - from) * 2)
    var b = 0
    for (i in from until to) {
        val code = name[i].code
        bytes[b++] = (code shr 8).toByte()
        bytes[b++] = (code and 0xFF).toByte()
    }
    return Base64.getEncoder().withoutPadding().encodeToString(bytes).replace('/', ',')
}

/** The text of one shift sequence, or null when it is not decodable (caller keeps it verbatim). */
private fun decodeBase64Utf16(chunk: String): String? {
    // A base64 run of length %4 == 1 cannot exist; padding is omitted on the wire, so add it back.
    val pad = when (chunk.length % 4) {
        0 -> ""
        2 -> "=="
        3 -> "="
        else -> return null
    }
    val bytes = runCatching {
        Base64.getDecoder().decode(chunk.replace(',', '/') + pad)
    }.getOrNull() ?: return null
    if (bytes.isEmpty() || bytes.size % 2 != 0) return null
    val out = StringBuilder(bytes.size / 2)
    var i = 0
    while (i < bytes.size) {
        out.append(((bytes[i].toInt() and 0xFF) shl 8 or (bytes[i + 1].toInt() and 0xFF)).toChar())
        i += 2
    }
    return out.toString()
}
