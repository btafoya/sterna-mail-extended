package app.sterna.ui.compose

import android.text.format.DateUtils
import app.sterna.send.SendOutbox
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Executes [scheduledHoldLabel], the sentence the Undo snackbar shows after a schedule. The
 * formatter and the sentence are fakes: `DateUtils` does not run on the JVM.
 */
class ScheduledHoldLabelTest {

    @Test fun `the label is the chosen instant, formatted as the Scheduled list formats it`() {
        val seen = mutableListOf<Pair<Long, Int>>()
        val label = scheduledHoldLabel(
            sendAtMillis = SEND_AT,
            formatInstant = { millis, flags -> seen += millis to flags; "FORMATTED($millis)" },
            sentence = { "Scheduled for $it" },
        )
        assertEquals("Scheduled for FORMATTED($SEND_AT)", label)
        assertEquals(
            "the formatter must be asked once, for the instant handed in (never the clock, never a " +
                "preset's wording), with the Scheduled list's flags: date, time, abbreviated month",
            listOf(
                SEND_AT to (DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH),
            ),
            seen,
        )
    }

    @Test fun `two instants give two labels`() {
        val format: (Long, Int) -> String = { millis, _ -> "at $millis" }
        assertEquals("at 1", scheduledHoldLabel(1L, format) { it })
        assertEquals("at 2", scheduledHoldLabel(2L, format) { it })
    }

    @Test fun `the booked delay never falls inside the Undo window`() {
        assertEquals(SendOutbox.HOLD_MS, heldScheduleDelayMillis(0L))
        assertEquals(SendOutbox.HOLD_MS, heldScheduleDelayMillis(SendOutbox.HOLD_MS - 1))
        assertEquals(SendOutbox.HOLD_MS + 1, heldScheduleDelayMillis(SendOutbox.HOLD_MS + 1))
        assertEquals(HOUR, heldScheduleDelayMillis(HOUR))
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val SEND_AT = 1_790_000_000_000L
    }
}
