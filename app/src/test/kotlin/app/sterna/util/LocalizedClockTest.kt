package app.sterna.util

import app.sterna.core.data.calendar.ParsedEvent
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 12-hour clock takes its marker and its order from the language: zh and hu put the marker
 * before the time. `formatMinutes` and `formatEventWhen` are private to their screens, so they are
 * reached by reflection, which runs the shipped function rather than a copy of its branches.
 */
class LocalizedClockTest {

    private val paris = ZoneId.of("Europe/Paris")
    private val iso = "2026-07-04T13:05:00Z"
    private val chinese = Locale.SIMPLIFIED_CHINESE

    private val formatMinutes = Class.forName("app.sterna.ui.settings.SettingsScreenKt")
        .getDeclaredMethod("formatMinutes", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        .apply { isAccessible = true }

    private val formatEventWhen = Class.forName("app.sterna.ui.message.MessageScreenKt")
        .getDeclaredMethod("formatEventWhen", ParsedEvent::class.java, Boolean::class.javaPrimitiveType)
        .apply { isAccessible = true }

    private fun quietHour(minutes: Int, is24Hour: Boolean) = formatMinutes.invoke(null, minutes, is24Hour) as String

    private fun eventWhen(start: String, end: String?, is24Hour: Boolean, allDay: Boolean = false): String {
        val event = ParsedEvent(
            title = null,
            startMillis = ZonedDateTime.parse(start).toInstant().toEpochMilli(),
            endMillis = end?.let { ZonedDateTime.parse(it).toInstant().toEpochMilli() },
            allDay = allDay, location = null, organizer = null, attendeeCount = 0, recurs = false,
            description = null, method = null, status = null,
        )
        return formatEventWhen.invoke(null, event, is24Hour) as String
    }

    private fun <T> inParis(locale: Locale, source: ((Locale) -> String?)? = null, block: () -> T): T {
        val restoreLocale = Locale.getDefault()
        val restoreZone = TimeZone.getDefault()
        val restoreSource = MailDates.twelveHourSource
        return try {
            Locale.setDefault(locale)
            TimeZone.setDefault(TimeZone.getTimeZone(paris))
            if (source != null) MailDates.twelveHourSource = source
            block()
        } finally {
            MailDates.twelveHourSource = restoreSource
            TimeZone.setDefault(restoreZone)
            Locale.setDefault(restoreLocale)
        }
    }

    @Test fun theFullDateAndTodaysRowPutTheChineseMarkerBeforeTheTime() = inParis(chinese, { "ah:mm" }) {
        val full = MailDates.formatFull(iso, paris, chinese, is24Hour = false)
        assertTrue(full, full.endsWith(", 下午3:05"))
        val today = MailDates.formatListDate(iso, paris, LocalDate.of(2026, 7, 4), chinese, is24Hour = false)
        assertEquals("下午3:05", today)
    }

    @Test fun theTwentyFourHourClockIgnoresTheLanguagesTwelveHourPattern() = inParis(chinese, { "ah:mm" }) {
        assertEquals("15:05", MailDates.formatListDate(iso, paris, LocalDate.of(2026, 7, 4), chinese, is24Hour = true))
        assertEquals("4 Jul 2026, 15:05", MailDates.formatFull(iso, paris, Locale.ENGLISH, is24Hour = true))
    }

    @Test fun aPatternJavaTimeRefusesFallsBackToTheEnglishOrder() = inParis(Locale.ENGLISH, { "h:mm {" }) {
        assertEquals("4 Jul 2026, 3:05 PM", MailDates.formatFull(iso, paris, Locale.ENGLISH, is24Hour = false))
    }

    @Test fun quietHoursFollowTheClockSetting() = inParis(Locale.ENGLISH) {
        assertEquals("15:05", quietHour(15 * 60 + 5, true))
        assertEquals("00:00", quietHour(0, true))
        assertEquals("3:05 PM", quietHour(15 * 60 + 5, false))
        assertEquals("9:30 AM", quietHour(9 * 60 + 30, false))
        assertEquals("12:00 AM", quietHour(0, false))
    }

    @Test fun quietHoursPutTheChineseMarkerBeforeTheTime() = inParis(chinese, { "ah:mm" }) {
        assertEquals("下午3:05", quietHour(15 * 60 + 5, false))
        assertEquals("上午9:30", quietHour(9 * 60 + 30, false))
    }

    @Test fun theEventCardFollowsTheClockSetting() = inParis(Locale.ENGLISH) {
        val start = "2026-07-04T15:05:00+02:00"
        val sameDay = eventWhen(start, "2026-07-04T16:30:00+02:00", is24Hour = true)
        assertTrue(sameDay, sameDay.startsWith("Sat 4 Jul 2026, 15:05 ") && sameDay.endsWith(" – 16:30"))
        val twelve = eventWhen(start, "2026-07-04T16:30:00+02:00", is24Hour = false)
        assertTrue(twelve, twelve.startsWith("Sat 4 Jul 2026, 3:05 PM ") && twelve.endsWith(" – 4:30 PM"))
        val nextDay = eventWhen(start, "2026-07-05T09:00:00+02:00", is24Hour = false)
        assertTrue(nextDay, nextDay.contains(" – Sun 5 Jul 2026, 9:00 AM "))
        assertEquals("Sat 4 Jul 2026", eventWhen("2026-07-04T00:00:00+02:00", null, is24Hour = false, allDay = true))
    }

    @Test fun theEventCardPutsTheChineseMarkerBeforeTheTime() = inParis(chinese, { "ah:mm" }) {
        val span = eventWhen("2026-07-04T15:05:00+02:00", "2026-07-04T16:30:00+02:00", is24Hour = false)
        assertTrue(span, span.contains(", 下午3:05 ") && span.endsWith(" – 下午4:30"))
    }
}
