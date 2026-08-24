package app.sterna.core.data.mail

import app.sterna.core.data.db.ScheduledSendEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ⭐ **What a startup books for the `scheduled_sends` table**, executed — the shipped
 * [scheduledSendJobs] / [scheduledSendDelayMillis], driven over shipped rows.
 *
 * A scheduled send is the queue that waits the LONGEST — hours, by construction — so it is the one
 * most likely to outlive the WorkManager job that was supposed to fire it: the jobs and the table
 * are two different files, and the composer writes the row and books the job as two operations. The
 * row then sits in the "Scheduled" screen with an hour in the past and nothing behind it, for good.
 * ⛔ NOT a restore — `allowBackup` is false and `sterna.db` is excluded from backup and transfer.
 *
 * ⛔ Every couple below is written out in full, and the function is EXECUTED rather than read: a
 * test that recomputed "is this row due?" before deciding what to expect would be a copy of the
 * rule, and the shipped rule could be inverted under it without a single assertion moving.
 */
class ScheduledSendRearmTest {

    @Test fun `a startup books one job per scheduled row, overdue ones included`() {
        val jobs = scheduledSendJobs(
            listOf(
                row(id = 1L, sendAtMillis = NOW - 5_000L),
                row(id = 2L, sendAtMillis = NOW),
                row(id = 3L, sendAtMillis = NOW + 90_000L),
            ),
            NOW,
        )

        // ⛔ THE OVERDUE ROW IS IN THE LIST, at delay zero — that is the decision of this seam and
        // the whole reason it exists. Dropping it (or hiding it behind a "too old to bother"
        // threshold) is what leaves the screen announcing a send that will never leave: the row is
        // the ONLY copy of the message, nothing else ever comes and asks about it, and the user is
        // told an hour that has already passed. Late is honest; never is a lie.
        // ⛔ And the ORDER and the COUNT are pinned together: three rows in, three jobs out. A
        // filter that quietly kept two would still satisfy a per-element check.
        assertEquals(
            listOf(
                ScheduledSendJob(1L, 0L),
                ScheduledSendJob(2L, 0L),
                ScheduledSendJob(3L, 90_000L),
            ),
            jobs,
        )
        // Nothing waiting, nothing booked — the re-arm must be free on the overwhelmingly common
        // start where no message is scheduled at all.
        assertEquals("no rows, no work", emptyList<ScheduledSendJob>(), scheduledSendJobs(emptyList(), NOW))
        // A negative delay is what WorkManager would be handed for a lapsed hour; coerced here so
        // the send goes AT ONCE instead of the request being built on a nonsense number.
        assertEquals("a delay is never negative", 0L, scheduledSendDelayMillis(NOW - 1_000L, NOW))
        // …and a future hour is carried through untouched: coercing everything to zero would fire
        // every scheduled message the moment the app is opened.
        assertEquals(1_000L, scheduledSendDelayMillis(NOW + 1_000L, NOW))
    }

    private fun row(id: Long, sendAtMillis: Long) = ScheduledSendEntity(
        id = id,
        accountId = "acc-1",
        recipients = "someone@example.test",
        subject = "later",
        textBody = "body",
        htmlBody = null,
        fromName = null,
        fromEmail = null,
        inReplyTo = null,
        references = null,
        sendAtMillis = sendAtMillis,
    )

    private companion object {
        const val NOW = 1_760_000_000_000L
    }
}
