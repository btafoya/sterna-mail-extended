package app.sterna.ui.compose

import app.sterna.R
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/** [schedulePresetLabel] run with the two clock formats a phone can be set to. */
class SchedulePresetLabelTest {
    private val utc = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2026, 8, 18, 10, 0, 0, 0, utc).toInstant().toEpochMilli()

    private fun format(pattern: String): (Long) -> String {
        val f = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return { f.format(java.util.Date(it)) }
    }

    private val words: (Int, String?) -> String = { res, arg ->
        val name = when (res) {
            R.string.schedule_in_1_hour -> "in1h"
            R.string.schedule_this_morning -> "morning"
            R.string.schedule_this_evening -> "evening"
            R.string.schedule_tomorrow_morning -> "tomorrowMorning"
            R.string.schedule_tomorrow_evening -> "tomorrowEvening"
            R.string.schedule_last_used -> "lastUsed"
            else -> "?"
        }
        if (arg == null) name else "$name($arg)"
    }

    private fun labels(time: (Long) -> String) =
        schedulePresetsAt(now, utc).map { (preset, millis) -> schedulePresetLabel(preset, millis, time, format("d MMM HH:mm"), words) }

    @Test fun `the absolute entries name the hour of their own instant, 24-hour clock`() {
        assertEquals(
            listOf("in1h", "evening(18:00)", "tomorrowMorning(08:00)", "tomorrowEvening(18:00)"),
            labels(format("HH:mm")),
        )
    }

    @Test fun `the same entries on a 12-hour clock`() {
        assertEquals(
            listOf("in1h", "evening(6:00 PM)", "tomorrowMorning(8:00 AM)", "tomorrowEvening(6:00 PM)"),
            labels(format("h:mm a")),
        )
    }

    @Test fun `the last pick reads as last used, with its date and hour`() {
        val at = ZonedDateTime.of(2026, 8, 18, 14, 30, 0, 0, utc).toInstant().toEpochMilli()
        assertEquals(
            "lastUsed(18 Aug 14:30)",
            schedulePresetLabel(SchedulePreset.LAST_PICKED, at, format("HH:mm"), format("d MMM HH:mm"), words),
        )
    }

    @Test fun `before 8 AM the list opens with this morning at today's hour`() {
        val early = ZonedDateTime.of(2026, 8, 18, 5, 0, 0, 0, utc).toInstant().toEpochMilli()
        assertEquals(
            listOf("in1h", "morning(08:00)", "evening(18:00)", "tomorrowMorning(08:00)", "tomorrowEvening(18:00)"),
            schedulePresetsAt(early, utc).map { (preset, millis) -> schedulePresetLabel(preset, millis, format("HH:mm"), format("d MMM HH:mm"), words) },
        )
    }
}
