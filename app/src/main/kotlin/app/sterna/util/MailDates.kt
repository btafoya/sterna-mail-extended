package app.sterna.util

import android.content.Context
import android.text.format.DateFormat
import app.sterna.appLocale
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Formatting of the ISO timestamps mail carries, in the device's language and time zone. */
object MailDates {

    /** Set once by the Application; lets the clock follow the device's 12/24-hour setting. Unset
     *  (unit tests) means 24-hour. */
    @Volatile
    var deviceContext: Context? = null

    /** The device's 12/24-hour preference, read per call so a change applies without a restart. */
    fun is24Hour(): Boolean = deviceContext?.let { DateFormat.is24HourFormat(it) } ?: true

    private val twelveHourPatterns = ConcurrentHashMap<Locale, String>()

    /** The language's own 12-hour clock, `ah:mm` in zh; null off the phone, which means `h:mm a`. */
    @Volatile
    internal var twelveHourSource: (Locale) -> String? =
        { locale -> deviceContext?.let { DateFormat.getBestDateTimePattern(locale, "hm") } }
        set(value) {
            field = value
            twelveHourPatterns.clear()
        }

    private fun clockPattern(locale: Locale, is24Hour: Boolean): String {
        if (is24Hour) return "HH:mm"
        twelveHourPatterns[locale]?.let { return it }
        val localized = twelveHourSource(locale) ?: return "h:mm a"
        // ICU patterns may carry letters java.time refuses; a refused one would throw at every row.
        val usable = localized.takeIf { runCatching { DateTimeFormatter.ofPattern(it) }.isSuccess } ?: "h:mm a"
        return twelveHourPatterns.putIfAbsent(locale, usable) ?: usable
    }

    /** Keyed by pattern only: the language is reapplied per call, so a change reaches it unrestarted. */
    private val patternFormatters = ConcurrentHashMap<String, DateTimeFormatter>()

    private fun formatter(pattern: String, locale: Locale): DateTimeFormatter =
        patternFormatters.getOrPut(pattern) { DateTimeFormatter.ofPattern(pattern) }.withLocale(locale)

    /** "4 Jul", the year is implicit while it is the current one. */
    private val dayMonthFormatter = DateTimeFormatter.ofPattern("d MMM", appLocale)

    /**
     * "12/19/89" in en-US, "19/12/89" in fr: the language's own short date with the year cut to two
     * digits. Built once per language and kept, a list asks for one per visible row.
     */
    private val shortDateFormatters = ConcurrentHashMap<Locale, DateTimeFormatter>()

    private fun shortDateFormatter(locale: Locale): DateTimeFormatter =
        shortDateFormatters.getOrPut(locale) {
            val localized = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale,
            )
            DateTimeFormatter.ofPattern(withTwoDigitYear(localized), locale)
        }

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
    fun formatFull(
        iso: String?,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = appLocale,
        is24Hour: Boolean = is24Hour(),
    ): String = formatWith(iso, formatter("d MMM yyyy, ${clockPattern(locale, is24Hour)}", locale), zone)

    /**
     * The stamp a list row carries, in three steps: today shows the time, an earlier day of the
     * of the current year the day and month ("4 Jul"), any other year a short numeric date
     * ("12/19/89"). Dropping the year indefinitely made a 2019 message read exactly like a 2026
     * one, the same trap K-9 and Gmail avoid by omitting the year for the current year only.
     */
    fun formatListDate(
        iso: String?,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
        locale: Locale = appLocale,
        is24Hour: Boolean = is24Hour(),
    ): String {
        val zoned = zoned(iso, zone) ?: return ""
        val date = zoned.toLocalDate()
        val formatter = when {
            date == today -> formatter(clockPattern(locale, is24Hour), locale)
            date.year == today.year -> dayMonthFormatter.withLocale(locale)
            else -> shortDateFormatter(locale)
        }
        return zoned.format(formatter)
    }

    /** Minutes since midnight as the list row shows a time; a stored value past the day wraps, never throws. */
    fun formatClock(minutesOfDay: Int, is24Hour: Boolean, locale: Locale = appLocale): String =
        LocalTime.ofSecondOfDay(Math.floorMod(minutesOfDay, 24 * 60) * 60L)
            .format(formatter(clockPattern(locale, is24Hour), locale))

    /** The event card's "when": the date alone for an all-day event, else start to end in [zone]. */
    fun formatEventSpan(
        startMillis: Long,
        endMillis: Long?,
        allDay: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = appLocale,
        is24Hour: Boolean = is24Hour(),
    ): String {
        val start = Instant.ofEpochMilli(startMillis).atZone(zone)
        if (allDay) return start.format(formatter("EEE d MMM yyyy", locale))
        val clock = clockPattern(locale, is24Hour)
        val dateTime = formatter("EEE d MMM yyyy, $clock z", locale)
        val startStr = start.format(dateTime)
        val end = endMillis?.let { Instant.ofEpochMilli(it).atZone(zone) } ?: return startStr
        val endStr = end.format(if (start.toLocalDate() == end.toLocalDate()) formatter(clock, locale) else dateTime)
        return "$startStr – $endStr"
    }

    /** [iso] rendered with [formatter] in [zone]; "" when missing or unparseable (never throws). */
    fun formatWith(
        iso: String?,
        formatter: DateTimeFormatter,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = zoned(iso, zone)?.format(formatter) ?: ""

    /** [iso] in [zone], or null when unreadable; mail carries a plain instant or one with an offset. */
    private fun zoned(iso: String?, zone: ZoneId): ZonedDateTime? {
        if (iso.isNullOrBlank()) return null
        return runCatching { Instant.parse(iso) }
            .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
            .getOrNull()
            ?.atZone(zone)
    }
}
