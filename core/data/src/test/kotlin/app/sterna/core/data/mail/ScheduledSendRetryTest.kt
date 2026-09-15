package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executes the give-up decision of the scheduled-send worker. Giving up destroys the scheduled row,
 * the only copy of the message, so the budget is pinned by its outcomes and not by the constant.
 */
class ScheduledSendRetryTest {

    /** One failed delivery after another, each fed the count the previous retry stored. */
    @Test fun `three failures are retried and the fourth gives up`() {
        val outcomes = mutableListOf<Boolean>()
        var stored = 0
        while (outcomes.size < 10) {
            val retry = ScheduledSendRetry.shouldRetry(stored)
            outcomes += retry
            if (!retry) break
            stored += 1
        }
        assertEquals(
            "the scheduled-send budget changed: three delivery failures are retried and the " +
                "fourth gives up, notifies and destroys the row. Fewer retries destroy a message a " +
                "short outage would have let through; an endless one never tells the user",
            listOf(true, true, true, false),
            outcomes,
        )
    }

    /**
     * The symptom this rule was written for: runs that attempted nothing (account not proven gone)
     * store nothing, so the first real failure after them still reads zero and must retry.
     */
    @Test fun `a row with no failure stored is retried`() {
        assertTrue(
            "a row that has never failed a delivery gives up on its first failure: the only copy " +
                "of the message is destroyed on one transient error",
            ScheduledSendRetry.shouldRetry(0),
        )
    }

    @Test fun `a count at or past the budget is never retried again`() {
        assertEquals(
            "a row whose stored count reached the budget must give up, whatever lies beyond it",
            listOf(false, false, false),
            listOf(3, 4, 99).map { ScheduledSendRetry.shouldRetry(it) },
        )
    }
}
