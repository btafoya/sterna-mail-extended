package app.sterna.core.imap

/**
 * Folding for the `Autocrypt:` header field (Autocrypt Level 1 §2.1), and nothing else.
 *
 * ⭐ **Why a folder exists at all, here and only here.** Nothing in this repo folded a header
 * before: [OutgoingMime]'s fields are addresses, ids and an encoded subject, all short, and the one
 * folded header in the codebase is written by hand ([PgpMime]'s `multipart/signed` Content-Type).
 * This field carries a whole public key: an RSA-4096 transferable key is ~1 550 base64 characters,
 * and RFC 5322 §2.1.1 caps a line at **998 octets** excluding the CRLF. Unfolded, such a header is
 * malformed — a strict relay refuses the message, or the key is delivered truncated and the
 * correspondent's encryption to that address becomes impossible.
 *
 * ⛔ **Not a general header folder, deliberately.** [OutgoingMime.headerSafe] is the ONE CR/LF sink
 * for header values, and it must stay one: this function does the exact opposite — it *inserts*
 * `"\r\n "` on purpose. The two are ordered, never mixed: sanitise first, fold second, and never
 * put a folded value back through the sink (it would come out flat, and over-long again).
 *
 * ⛔ The continuation always starts with **a space**. That is what makes an unfolded continuation
 * a `FWS` and not a new header field, and it is also what keeps [SmtpClient]'s `DATA` loop safe:
 * that loop splits on `"\r\n"` and dot-stuffs a line beginning with `.`; a continuation beginning
 * with a space can be neither a header nor a stuffed line.
 */
object AutocryptHeader {

    /** The field name, written once so the folder's budget and the emitted header cannot drift. */
    const val NAME = "Autocrypt"

    /** RFC 5322 §2.1.1: a line is at most 998 octets, the CRLF not counted. */
    private const val LINE_LIMIT = 998

    /**
     * [value] with `"\r\n "` inserted as often as RFC 5322 §2.1.1 requires, and no more — the
     * value is returned untouched when `"$NAME: $value"` already fits on one line.
     *
     * That "and no more" is the whole shape of it: the four benches carry ed25519 keys (~544 base64
     * characters), which fit, so a folder that wrapped at 76 columns like a MIME body would be
     * indistinguishable from this one on any bench we own and different on exactly the message that
     * matters. Folding only when the line would be illegal keeps the common header a single line
     * that a human, a log and a `grep` can all read.
     *
     * The budget is counted in **octets**, not characters: the first line has to hold `"$NAME: "`
     * as well, a continuation its leading space. Splitting is left to right and never inside a
     * UTF-8 character.
     *
     * ⭐ **Where a break can land, and why that is safe.** The value is `addr=…; keydata=<base64>`.
     * The first break falls at octet 987, and an addr-spec that a mail server will accept is capped
     * at 256 octets by RFC 5321 §4.5.3.1, so a break can only ever land inside the base64 keydata —
     * where Autocrypt Level 1 §2.1 says whitespace is ignored by the parser. Nothing here inspects
     * the value to find that out: it is a property of the value, stated so a reader can check it.
     */
    fun fold(value: String): String {
        val firstBudget = LINE_LIMIT - (NAME.length + 2) // "Autocrypt: "
        val contBudget = LINE_LIMIT - 1 // the continuation's leading space
        var cut = advance(value, 0, firstBudget)
        if (cut >= value.length) return value
        val out = StringBuilder(value.substring(0, cut))
        while (cut < value.length) {
            val next = advance(value, cut, contBudget)
            // A budget that fits nothing would loop forever; one octet always fits a 997 budget,
            // so this can only happen if the constants above are broken.
            check(next > cut) { "Autocrypt folding made no progress at $cut" }
            out.append("\r\n ").append(value, cut, next)
            cut = next
        }
        return out.toString()
    }

    /**
     * The index just past the last character of `value` that fits in [budget] octets starting at
     * [from], never splitting a surrogate pair. Astral characters are counted as 3 octets each
     * half, i.e. 6 instead of 4: over-counting shortens a line, and a short line is legal.
     */
    private fun advance(value: String, from: Int, budget: Int): Int {
        var used = 0
        var i = from
        while (i < value.length) {
            val c = value[i].code
            val octets = when {
                c < 0x80 -> 1
                c < 0x800 -> 2
                else -> 3
            }
            if (used + octets > budget) break
            used += octets
            i++
        }
        if (i > from && i < value.length && value[i - 1].isHighSurrogate()) i--
        return i
    }
}
