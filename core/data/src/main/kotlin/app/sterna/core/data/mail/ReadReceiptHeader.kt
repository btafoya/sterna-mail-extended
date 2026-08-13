package app.sterna.core.data.mail

/**
 * Reads RFC 8098's `Disposition-Notification-To:` — the addresses the sender named as the place a
 * read receipt would be sent to.
 *
 * Pure string handling, like [UnsubscribeHeader]: no `android.net.Uri`, no repository, so the
 * whole decision runs in a JVM unit test. Both protocols feed it the SAME text — JMAP's
 * `header:Disposition-Notification-To:asText` property and IMAP's `MimeParser.headerOf` — so a
 * malformed header cannot be read two different ways depending on the server.
 *
 * ⛔ It decides NO policy. Not whether a receipt should be offered, not whether the address is the
 * sender's, not whether it is one of this phone's own accounts, not whether two entries are the
 * same person. It answers one question and only that: **which addresses does this header name?**
 * Everything else belongs to the caller that will one day offer the banner.
 */
object ReadReceiptHeader {

    /**
     * The addresses of [header], in the order the sender wrote them, or an EMPTY list when there
     * is none to be had — absent, blank, a group with no members, `<>`, a word that is not an
     * address.
     *
     * ⭐ Empty is the ONLY way of saying "nothing here". A later reader is specified to hold
     * `require(recipients.isNotEmpty())` before it offers anything, so this function must never
     * throw and never return a placeholder: an empty list means "no banner", and that is all.
     *
     * Duplicates are kept as written. Collapsing them would be a decision about what to do with
     * the header, and this is where that is not made.
     */
    fun recipients(header: String?): List<String> {
        if (header.isNullOrBlank()) return emptyList()
        return splitTopLevel(header.replace(FOLDING, " ")).mapNotNull { addressIn(it) }
    }

    /**
     * The whitespace a FOLDED header left behind (RFC 5322 §2.2.3: CRLF + WSP), turned into one
     * space rather than removed: unlike a URI, an address list is written WITH spaces, and gluing
     * `Ann Lee` to the next line's `<ann@example.org>` would make one unreadable token of them.
     * JMAP's `asText` normally arrives unfolded; IMAP's does after `MimeParser.headerOf`. Both are
     * handled here anyway, because this is the one place that can.
     */
    private val FOLDING = Regex("[ \t]*\r?\n[ \t]*")

    /** A `<…>` route-addr, the last one in a token — `Ann <old@x> <ann@example.org>` is malformed
     *  either way, and the last is what an address parser reaches. */
    private val ANGLED = Regex("<([^<>]*)>")

    /**
     * Split an address list on the separators that sit OUTSIDE quotes and outside `<…>`.
     *
     * Both `,` and `;` separate: `;` closes RFC 5322 §3.4 group syntax, and a group's members are
     * comma-separated inside it, so treating the two alike turns `Team: a@x, b@y;` into three
     * tokens of which the empty one is dropped later.
     *
     * ⛔ The quote tracking is not decoration: `"Doe, John" <j@example.org>` is one address, and a
     * naive `split(',')` makes it two — of which the first names no address at all.
     */
    private fun splitTopLevel(header: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var escaped = false
        var depth = 0
        for (c in header) {
            when {
                escaped -> { current.append(c); escaped = false }
                c == '\\' && quoted -> { current.append(c); escaped = true }
                c == '"' -> { quoted = !quoted; current.append(c) }
                quoted -> current.append(c)
                c == '<' -> { depth++; current.append(c) }
                c == '>' -> { if (depth > 0) depth--; current.append(c) }
                (c == ',' || c == ';') && depth == 0 -> { out.add(current.toString()); current.clear() }
                else -> current.append(c)
            }
        }
        out.add(current.toString())
        return out
    }

    /**
     * The one address a single token names, or null when it names none.
     *
     * ⭐ The quoted display name is removed FIRST, before anything else is looked at. A display
     * name may legally contain anything — including `<x@elsewhere.example>` — and looking for the
     * brackets in the raw token would let whoever wrote that name choose where the receipt goes.
     * The order is the whole protection, not the removal.
     *
     * Then a `<…>` wins outright, that being what angle brackets are for; failing that, the last
     * word carrying an `@`. Either way the obsolete source route of RFC 5322 §4.4
     * (`<@relay,@hop:mailbox@domain>`) and a group label (`Team:a@x`) are cut at the last colon,
     * which an ordinary address does not contain.
     */
    private fun addressIn(token: String): String? {
        val unnamed = token.replace(QUOTED, " ")
        val angled = ANGLED.findAll(unnamed).lastOrNull()?.groupValues?.get(1)
        val candidate = (angled ?: unnamed.split(' ', '\t').lastOrNull { '@' in it })
            ?.substringAfterLast(':')
        return candidate?.trim()?.trim('"')?.takeIf { isAddress(it) }
    }

    /** A quoted display name, backslash escapes included, as one unit to be thrown away. */
    private val QUOTED = Regex("\"(?:\\\\.|[^\"\\\\])*\"")

    /**
     * Whether [value] is usable as a destination: something before the `@`, something after it,
     * no whitespace, and none of the DELIMITERS that can only be there because the header was
     * malformed and something was left stuck to the address.
     *
     * Deliberately that and nothing more. This is not a validator — an over-strict one would drop
     * a perfectly deliverable address (no dot in `postmaster@localhost`, a UTF-8 domain, a tagged
     * local part) and the sender's request with it. What it rules out is the leftovers of a
     * malformed header: `<>`, `<ann@example.org` with no closing bracket, `a@b,c@d` inside one
     * pair of brackets, a bare word, `Team:` on its own. None of those is an address, and handing
     * one back means offering to send a receipt to a destination nobody named.
     */
    private fun isAddress(value: String): Boolean {
        if (value.isEmpty() || value.any { it.isWhitespace() || it in DELIMITERS }) return false
        val at = value.lastIndexOf('@')
        return at > 0 && at < value.length - 1
    }

    /** The characters that separate addresses or wrap them; never part of one, here. */
    private const val DELIMITERS = "<>,;\""
}
