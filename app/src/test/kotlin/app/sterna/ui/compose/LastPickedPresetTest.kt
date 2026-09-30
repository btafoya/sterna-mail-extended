package app.sterna.ui.compose

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [lastPickedPresetMillis], the fifth "send later" entry: the wall time last set on the clock,
 * projected onto the next instant it names. Expectations are written as UTC instants, never
 * rebuilt with the rule under test.
 */
class LastPickedPresetTest {
    private val paris = ZoneId.of("Europe/Paris")

    private fun paris(month: Int, day: Int, h: Int, m: Int = 0, s: Int = 0) =
        ZonedDateTime.of(2026, month, day, h, m, s, 0, paris).toInstant().toEpochMilli()

    private fun utc(iso: String) = Instant.parse(iso).toEpochMilli()

    /** What the menu really draws beside it at [now]: the fixed instants, the setup of each case. */
    private fun fixedAt(now: Long) = schedulePresetsAt(now, paris).map { it.second }

    private fun project(minutes: Int?, now: Long) = lastPickedPresetMillis(minutes, now, paris, fixedAt(now))

    @Test fun `a time still ahead today is offered today`() {
        val now = paris(8, 18, 9)
        assertEquals("9:00 CEST, 14:30 picked, is today at 14:30 CEST", utc("2026-08-18T12:30:00Z"), project(14 * 60 + 30, now))
    }

    @Test fun `a time already gone today is offered tomorrow`() {
        val now = paris(8, 18, 15)
        assertEquals("15:00, 9:30 picked, is tomorrow at 9:30", utc("2026-08-19T07:30:00Z"), project(9 * 60 + 30, now))
    }

    @Test fun `a time equal to now to the minute is tomorrow, not now`() {
        val now = paris(8, 18, 9, 30)
        assertEquals(
            "9:30:00.000 is not strictly after 9:30:00.000: today would be a zero-delay send",
            utc("2026-08-19T07:30:00Z"),
            project(9 * 60 + 30, now),
        )
    }

    @Test fun `6 PM before 6 PM is hidden, This evening already says it`() {
        val now = paris(8, 18, 17)
        assertNull(project(18 * 60, now))
    }

    @Test fun `6 PM after 6 PM is hidden, Tomorrow evening already says it`() {
        val now = paris(8, 18, 19)
        assertNull(project(18 * 60, now))
    }

    @Test fun `8 AM is hidden, Tomorrow morning already says it`() {
        val now = paris(8, 18, 10)
        assertNull(project(8 * 60, now))
    }

    @Test fun `a time equal to no fixed entry is not hidden`() {
        // 17:00 on the 18th: In 1 hour is 18:00:00, This evening is 18:00, so 18:01 is kept.
        val now = paris(8, 18, 17)
        assertEquals(utc("2026-08-18T16:01:00Z"), project(18 * 60 + 1, now))
    }

    @Test fun `23_59 just before midnight is tonight`() {
        val now = paris(8, 18, 23, 58)
        assertEquals(utc("2026-08-18T21:59:00Z"), project(23 * 60 + 59, now))
    }

    @Test fun `midnight just before midnight is the next day at 0_00`() {
        val now = paris(8, 18, 23, 58)
        assertEquals(utc("2026-08-18T22:00:00Z"), project(0, now))
    }

    @Test fun `midnight just after midnight is the following midnight`() {
        val now = paris(8, 19, 0, 0, 30)
        assertEquals(utc("2026-08-19T22:00:00Z"), project(0, now))
    }

    @Test fun `2_30 on the spring-forward night in Paris lands where the wall clock does`() {
        // 29 March 2026, 2:00 CET jumps to 3:00 CEST: 2:30 does not exist and the zone rules move
        // it forward an hour, to 3:30 CEST = 1:30 UTC.
        val now = paris(3, 29, 1)
        assertEquals(utc("2026-03-29T01:30:00Z"), project(2 * 60 + 30, now))
    }

    @Test fun `tomorrow across the spring-forward keeps the wall time, not 24 hours`() {
        // 10:00 CET on the 28th, 9:00 picked: tomorrow 9:00 CEST is 7:00 UTC. Twenty-four hours of
        // millis added to today's 9:00 CET would answer 8:00 UTC, 10:00 on the wall.
        val now = paris(3, 28, 10)
        assertEquals(utc("2026-03-29T07:00:00Z"), project(9 * 60, now))
    }

    @Test fun `no time kept, no fifth entry`() {
        assertNull(project(null, paris(8, 18, 9)))
    }
}
