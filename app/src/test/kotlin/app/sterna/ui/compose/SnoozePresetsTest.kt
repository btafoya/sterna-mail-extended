package app.sterna.ui.compose

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The snooze menu is built from [snoozePresetsAt], handed the instant and the zone instead of
 * reading the clock, so 6 PM, Sunday and the clock changes are playable on the JVM.
 *
 * Its rule: the candidates strictly in the future, less any whose instant an entry above already
 * offers (at 5 PM sharp the hour is 6 PM; on Sunday next Monday is tomorrow). A lapsed evening is
 * dropped, never folded to tomorrow, since its label names today. "Next week" is the Monday of the
 * following ISO week, so on a Monday it is seven days away.
 */
class SnoozePresetsTest {
    private val utc = ZoneId.of("UTC")
    private val paris = ZoneId.of("Europe/Paris")
    private val hour = 3_600_000L

    private fun millis(zone: ZoneId, month: Int, day: Int, h: Int, m: Int = 0) =
        ZonedDateTime.of(2026, month, day, h, m, 0, 0, zone).toInstant().toEpochMilli()

    /** 2026-08-18 is a Tuesday; the next Monday is the 24th. */
    private fun aug(day: Int, h: Int, m: Int = 0) = millis(utc, 8, day, h, m)

    private fun presets(zone: ZoneId, at: Long) = snoozePresetsAt(at, zone)

    @Test fun calendarAssumptionsHold() {
        assertEquals(DayOfWeek.TUESDAY, LocalDate.of(2026, 8, 18).dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, LocalDate.of(2026, 8, 23).dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, LocalDate.of(2026, 3, 29).dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, LocalDate.of(2026, 10, 25).dayOfWeek)
        // Both are the clock-change days in Paris: 23 and 25 hours long.
        assertEquals(23 * hour, millis(paris, 3, 30, 0) - millis(paris, 3, 29, 0))
        assertEquals(25 * hour, millis(paris, 10, 26, 0) - millis(paris, 10, 25, 0))
    }

    @Test fun beforeFivePmOffersAllFourInOrder() {
        assertEquals(
            listOf(
                SnoozePreset.IN_1_HOUR to aug(18, 11),
                SnoozePreset.THIS_EVENING to aug(18, 18),
                SnoozePreset.TOMORROW_MORNING to aug(19, 8),
                SnoozePreset.NEXT_WEEK to aug(24, 8),
            ),
            presets(utc, aug(18, 10)),
        )
    }

    @Test fun betweenFiveAndSixPmKeepsBothTheHourAndTheEvening() {
        assertEquals(
            listOf(
                SnoozePreset.IN_1_HOUR to aug(18, 18, 30),
                SnoozePreset.THIS_EVENING to aug(18, 18),
                SnoozePreset.TOMORROW_MORNING to aug(19, 8),
                SnoozePreset.NEXT_WEEK to aug(24, 8),
            ),
            presets(utc, aug(18, 17, 30)),
        )
    }

    @Test fun atFivePmTheHourIsSixPmSoTheEveningGoesAsADuplicate() {
        assertEquals(
            listOf(
                SnoozePreset.IN_1_HOUR to aug(18, 18),
                SnoozePreset.TOMORROW_MORNING to aug(19, 8),
                SnoozePreset.NEXT_WEEK to aug(24, 8),
            ),
            presets(utc, aug(18, 17)),
        )
    }

    @Test fun afterSixPmTheEveningIsAbsentAndNeverTomorrowSixPm() {
        val got = presets(utc, aug(18, 19))
        assertEquals(
            listOf(
                SnoozePreset.IN_1_HOUR to aug(18, 20),
                SnoozePreset.TOMORROW_MORNING to aug(19, 8),
                SnoozePreset.NEXT_WEEK to aug(24, 8),
            ),
            got,
        )
        assertEquals("no entry may snooze to tomorrow 6 PM", emptyList<Pair<SnoozePreset, Long>>(), got.filter { it.second == aug(19, 18) })
    }

    @Test fun exactlySixPmIsNotStrictlyFutureSoTheEveningGoes() {
        assertEquals(
            listOf(
                SnoozePreset.IN_1_HOUR to aug(18, 19),
                SnoozePreset.TOMORROW_MORNING to aug(19, 8),
                SnoozePreset.NEXT_WEEK to aug(24, 8),
            ),
            presets(utc, aug(18, 18)),
        )
    }

    @Test fun onSundayMondayEightAmIsOfferedOnceUnderTomorrow() {
        assertEquals(
            listOf(
                SnoozePreset.IN_1_HOUR to aug(23, 11),
                SnoozePreset.THIS_EVENING to aug(23, 18),
                SnoozePreset.TOMORROW_MORNING to aug(24, 8),
            ),
            presets(utc, aug(23, 10)),
        )
    }

    @Test fun onMondayNextWeekIsTheFollowingMonday() {
        assertEquals(
            listOf(
                SnoozePreset.IN_1_HOUR to aug(24, 11),
                SnoozePreset.THIS_EVENING to aug(24, 18),
                SnoozePreset.TOMORROW_MORNING to aug(25, 8),
                SnoozePreset.NEXT_WEEK to aug(31, 8),
            ),
            presets(utc, aug(24, 10)),
        )
    }

    @Test fun springForwardSundayInParis() {
        // 01:30 CET, half an hour before the clocks jump from 02:00 to 03:00.
        val now = millis(paris, 3, 29, 1, 30)
        assertEquals(
            listOf(
                SnoozePreset.IN_1_HOUR to now + hour,
                SnoozePreset.THIS_EVENING to millis(paris, 3, 29, 18),
                SnoozePreset.TOMORROW_MORNING to millis(paris, 3, 30, 8),
            ),
            presets(paris, now),
        )
        assertEquals(
            listOf(
                SnoozePreset.IN_1_HOUR to millis(paris, 3, 29, 18),
                SnoozePreset.TOMORROW_MORNING to millis(paris, 3, 30, 8),
            ),
            presets(paris, millis(paris, 3, 29, 17)),
        )
    }

    @Test fun fallBackSundayInParis() {
        // 02:30 CEST, the first of the two 02:30 of the night the clocks go back.
        val now = millis(paris, 10, 25, 2, 30)
        assertEquals(
            listOf(
                SnoozePreset.IN_1_HOUR to now + hour,
                SnoozePreset.THIS_EVENING to millis(paris, 10, 25, 18),
                SnoozePreset.TOMORROW_MORNING to millis(paris, 10, 26, 8),
            ),
            presets(paris, now),
        )
        assertEquals(
            listOf(
                SnoozePreset.IN_1_HOUR to millis(paris, 10, 25, 20),
                SnoozePreset.TOMORROW_MORNING to millis(paris, 10, 26, 8),
            ),
            presets(paris, millis(paris, 10, 25, 19)),
        )
    }

    @Test fun theZoneArgumentDecidesNotTheJvmDefault() {
        // 16:00 UTC on this summer date is 18:00 in Paris: still evening in UTC, no longer in Paris.
        val instant = aug(18, 16)
        assertEquals(aug(18, 18), presets(utc, instant).single { it.first == SnoozePreset.THIS_EVENING }.second)
        assertEquals(emptyList<Pair<SnoozePreset, Long>>(), presets(paris, instant).filter { it.first == SnoozePreset.THIS_EVENING })
    }
}
