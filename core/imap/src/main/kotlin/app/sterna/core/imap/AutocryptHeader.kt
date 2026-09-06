package app.sterna.core.imap

    /**
     * Folding for the `Autocrypt:` header field (Autocrypt Level 1 §2.1), and nothing else.
     *
     * Nothing else in this repo folds a header: the other fields are short. This one carries a whole
     * public key — an RSA-4096 transferable key is ~1 550 base64 characters — against RFC 5322
     * §2.1.1's cap of 998 octets a line, so unfolded it is malformed: a strict relay refuses the
     * message, or the key arrives truncated and encryption to that address becomes impossible.
     *
     * Not a general header folder: [OutgoingMime.headerSafe] is the ONE CR/LF sink and must stay
     * one, while this INSERTS `"\r\n "`. Sanitise first, fold second, never the other way round. The
     * continuation always starts with a SPACE, which is what makes it an `FWS` and not a new field —
     * and what keeps [SmtpClient]'s `DATA` loop from dot-stuffing it.
     */
object AutocryptHeader {

    /** The field name, written once so the folder's budget and the emitted header cannot drift. */
    const val NAME = "Autocrypt"

    /** RFC 5322 §2.1.1: a line is at most 998 octets, the CRLF not counted. */
    private const val LINE_LIMIT = 998

        /**
         * [value] with `"\r\n "` inserted as often as RFC 5322 §2.1.1 requires, and no more — returned
         * untouched when `"$NAME: $value"` already fits on one line. The "and no more" is the shape of
         * it: the four benches carry ed25519 keys (~544 base64 characters), which fit, so a folder
         * wrapping at 76 columns would look identical on every bench we own and differ on exactly the
         * message that matters. The budget is counted in OCTETS, splitting left to right and never
         * inside a UTF-8 character.
         *
         * The value is `addr=…; keydata=<base64>` and the first break falls at octet 987, while an
         * addr-spec is capped at 256 octets (RFC 5321 §4.5.3.1) — so a break can only land inside the
         * base64 keydata, where Autocrypt Level 1 §2.1 says whitespace is ignored.
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

        /** The index just past the last character of `value` that fits in [budget] octets starting at
         *  [from], never splitting a surrogate pair. Astral characters are counted as 6 octets rather
         *  than 4: over-counting shortens a line, and a short line is legal. */
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
