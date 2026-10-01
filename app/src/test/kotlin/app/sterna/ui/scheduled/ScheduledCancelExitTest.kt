package app.sterna.ui.scheduled

import app.sterna.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledCancelExitTest {

    @Test fun `filed in Drafts lands on the Drafts of the row's own account`() {
        assertEquals(
            ScheduledCancelExit.ToDrafts("acc-row"),
            scheduledCancelExit(ScheduledCancelReport.RETURNED_TO_DRAFTS, "acc-row"),
        )
    }

    @Test fun `not cancelled stays on the row, it is still the copy and still scheduled`() {
        assertEquals(
            "leaving towards Drafts here tells the user it is cancelled while it goes out at its hour",
            ScheduledCancelExit.StayNotCancelled,
            scheduledCancelExit(ScheduledCancelReport.NOT_CANCELLED, "acc-row"),
        )
    }

    @Test fun `account gone goes back to the list`() {
        assertEquals(
            ScheduledCancelExit.ToListAccountGone,
            scheduledCancelExit(ScheduledCancelReport.ACCOUNT_GONE, "acc-row"),
        )
    }

    @Test fun `a row already gone reads as no longer scheduled, never as sent`() {
        assertEquals(ScheduledCancelExit.NoLongerScheduled, scheduledCancelExit(null, "acc-row"))
    }

    @Test fun `each exit says its own sentence`() {
        assertEquals(R.string.scheduled_cancelled_to_drafts, scheduledCancelExitMessage(ScheduledCancelExit.ToDrafts("a")))
        assertEquals(R.string.scheduled_cancelled_no_account, scheduledCancelExitMessage(ScheduledCancelExit.ToListAccountGone))
        assertEquals(R.string.scheduled_cancel_failed, scheduledCancelExitMessage(ScheduledCancelExit.StayNotCancelled))
        assertEquals(
            "the worker also drops a row it hands to the Outbox, or whose account is gone, or out of attempts",
            R.string.scheduled_no_longer_scheduled,
            scheduledCancelExitMessage(ScheduledCancelExit.NoLongerScheduled),
        )
    }

    @Test fun `a row that disappears with nobody cancelling it closes the screen`() {
        assertTrue(scheduledSendClosesAsGone(rowGone = true, cancelling = false, answerPending = false))
    }

    @Test fun `a row still there never closes the screen`() {
        assertFalse(scheduledSendClosesAsGone(rowGone = false, cancelling = false, answerPending = false))
    }

    @Test fun `the row our own cancellation deleted waits for its own answer`() {
        assertFalse(
            "a successful cancellation deletes the row before it answers",
            scheduledSendClosesAsGone(rowGone = true, cancelling = true, answerPending = false),
        )
        assertFalse(
            "…and the answer is published before the row leaves the cancelling set",
            scheduledSendClosesAsGone(rowGone = true, cancelling = false, answerPending = true),
        )
    }

    @Test fun `the list switches to the row's account when it is another known one`() {
        assertEquals("acc-row", draftsLandingAccountSwitch("acc-row", "acc-current", listOf("acc-current", "acc-row")))
    }

    @Test fun `no switch when the row's account is already current`() {
        assertNull(draftsLandingAccountSwitch("acc-row", "acc-row", listOf("acc-row")))
    }

    @Test fun `no switch to an account the phone no longer knows`() {
        assertNull(draftsLandingAccountSwitch("acc-row", "acc-current", listOf("acc-current")))
    }
}
