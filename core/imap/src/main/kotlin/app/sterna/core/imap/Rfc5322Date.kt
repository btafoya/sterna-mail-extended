package app.sterna.core.imap

import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The sender's `Date:` header (RFC 5322 §3.3) as epoch millis, `0L` when nothing legal is there.
 * `0L` is the caller's "no date", as [ImapMessage.dateMillis] states it. The day of week is
 * accepted and never checked: senders contradict their own date and the header stays legal.
 */
internal fun rfc5322DateMillis(raw: String?): Long {
    if (raw.isNullOrBlank()) return 0L
    // Every blank collapses to one space first, so the grammar below never spells the width of a
    // blank: the padding of a one-digit day, a tab, and the CRLF of a folded header all arrive
    // here. A comment leaves a space behind it because RFC 5322 §3.2.2 lets one sit against the
    // token that follows, and dropping it whole is what keeps a trailing `(MSK)` from voiding the
    // header. A comment holding a nested comment is not handled and reads as no date at all.
    val cleaned = raw.replace(Regex("\\s+"), " ").replace(Regex(" ?\\([^)]*\\) ?"), " ").trim()
    val m = RFC5322_DATE.matchEntire(cleaned) ?: return runCatching {
        OffsetDateTime.parse(cleaned).toInstant().toEpochMilli()
    }.getOrDefault(0L)
    val g = m.groupValues
    // A month name outside the twelve leaves `month` at zero, which `OffsetDateTime.of` refuses,
    // so the unreadable header answers `0L` through the same path as an impossible calendar date.
    val month = IMAP_MONTHS.indexOfFirst { it.equals(g[2], ignoreCase = true) } + 1
    return runCatching {
        OffsetDateTime.of(
            rfc5322Year(g[3]), month, g[1].toInt(),
            g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt(), 0,
            rfc5322Zone(g[7]),
        ).toInstant().toEpochMilli()
    }.getOrDefault(0L)
}

/** Day of week optional, day and hour of either width, seconds optional, zone numeric with or
 *  without its colon or alphabetic. Applied to a value whose blanks are already one space each. */
private val RFC5322_DATE = Regex(
    """(?:[A-Za-z]{3},? )?(\d{1,2}) ([A-Za-z]{3}) (\d{2,4}) (\d{1,2}):(\d{2})(?::(\d{2}))? ([+-]\d{2}:?\d{2}|[A-Za-z]+)""",
)

/**
 * A three-digit year is 1900 plus its value, per RFC 5322 §4.3. The two-digit window is 1970-2069
 * and not the RFC's own 1950-2049: no mail was sent in the fifties, and a year read before the
 * epoch comes out negative, which `ImapMailService` turns back into the dateless row this reading
 * exists to stop. Left to the parser, `26` reads as the year 26 in silence and the row sinks.
 */
private fun rfc5322Year(digits: String): Int {
    val n = digits.toInt()
    return when (digits.length) {
        2 -> if (n <= 69) 2000 + n else 1900 + n
        3 -> 1900 + n
        else -> n
    }
}

/**
 * An alphabetic zone the table below does not name is RFC 5322 §4.3's "offset unknown", spelled
 * `-0000`: the military letters were published with the wrong sign, so none of them may be
 * believed. `java.time` holds no offset distinct from `ZoneOffset.UTC` for it, so an unlisted zone
 * reads as UTC and can be hours out, `CEST` by two, without the row saying so.
 */
private fun rfc5322Zone(zone: String): ZoneOffset =
    if (zone.first() == '+' || zone.first() == '-') ZoneOffset.of(zone)
    else OBSOLETE_ZONES[zone.uppercase(java.util.Locale.ENGLISH)] ?: ZoneOffset.UTC

private val OBSOLETE_ZONES = mapOf(
    "UT" to 0, "GMT" to 0,
    "EST" to -5, "EDT" to -4, "CST" to -6, "CDT" to -5,
    "MST" to -7, "MDT" to -6, "PST" to -8, "PDT" to -7,
).mapValues { ZoneOffset.ofHours(it.value) }
