package app.sterna.util

import app.sterna.appLocale
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Formatting of the ISO timestamps mail carries, in the device's language and time zone. Shared, so
 * the message header, a reply's attribution line and a list row all date a message the same way.
 * Pure, so it is unit-testable on the JVM.
 */
object MailDates {

    /** "4 Jul 2026, 09:12" in the app's language. */
    private val fullFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", appLocale)

    /** "09:12" — a message from today is placed by its clock time. */
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", appLocale)

    /** "4 Jul" — the year is implicit while it is the current one. */
    private val dayMonthFormatter = DateTimeFormatter.ofPattern("d MMM", appLocale)

    /**
     * "12/19/89" in en-US, "19/12/89" in fr: the language's own short date with the year cut to two
     * digits. Built once per language and kept — a list asks for one per visible row.
     */
    private val shortDateFormatters = ConcurrentHashMap<Locale, DateTimeFormatter>()

    private fun shortDateFormatter(locale: Locale): DateTimeFormatter =
        shortDateFormatters.getOrPut(locale) {
            val localized = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale,
            )
            DateTimeFormatter.ofPattern(withTwoDigitYear(localized), locale)
        }

    /**
     * [pattern] with its year field narrowed to two digits, everything else untouched. Four of the
     * nine languages spell the year in full, which nearly doubles the stamp and hands the cost back
     * to the sender's name. Only the year is ours to decide: the order of day and month and the
     * separator stay the language's, which is why the pattern is read from the locale rather than
     * written out per language.
     *
     * A year field is any run of `y` or `u`, of any length; runs inside a quoted literal are text.
     */
    private fun withTwoDigitYear(pattern: String): String = buildString {
        var i = 0
        var quoted = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\'' -> { quoted = !quoted; append(c); i++ }
                !quoted && (c == 'y' || c == 'u') -> {
                    while (i < pattern.length && pattern[i] == c) i++
                    append("yy")
                }
                else -> { append(c); i++ }
            }
        }
    }

    /** [iso] as a full local date + time, or "" when it is missing or unparseable. */
    fun formatFull(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String =
        formatWith(iso, fullFormatter, zone)

    /**
     * The stamp a list row carries, in three steps: today shows the time, an earlier day of the
     * current year the day and month, any other year a short numeric date. Dropping the year
     * indefinitely made a 2019 message read exactly like a 2026 one — the trap K-9 and Gmail avoid
     * by omitting the year for the current year only. The third step is numeric because the stamp
     * shares its line with the sender's name.
     *
     * [today] is the reference day in [zone] and not in UTC: near midnight the two disagree, and the
     * row must follow the reader's calendar. Both parameters are there so the turn of the year and
     * the language's order are testable.
     *
     * One wart, known and left alone: [locale] is read on every call, whereas [formatFull] froze
     * the language when this class loaded, so after a language change list rows follow at once and a
     * message's header does not until the process restarts.
     */
    fun formatListDate(
        iso: String?,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
        locale: Locale = appLocale,
    ): String {
        val zoned = zoned(iso, zone) ?: return ""
        val date = zoned.toLocalDate()
        val formatter = when {
            date == today -> timeFormatter.withLocale(locale)
            date.year == today.year -> dayMonthFormatter.withLocale(locale)
            else -> shortDateFormatter(locale)
        }
        return zoned.format(formatter)
    }

    /** [iso] rendered with [formatter] in [zone]; "" when missing or unparseable (never throws). */
    fun formatWith(
        iso: String?,
        formatter: DateTimeFormatter,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = zoned(iso, zone)?.format(formatter) ?: ""

    /**
     * [iso] in [zone], or null when it is missing or unreadable. Mail carries two timestamp shapes:
     * a plain instant, or one with an explicit offset.
     */
    private fun zoned(iso: String?, zone: ZoneId): ZonedDateTime? {
        if (iso.isNullOrBlank()) return null
        return runCatching { Instant.parse(iso) }
            .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
            .getOrNull()
            ?.atZone(zone)
    }
}
