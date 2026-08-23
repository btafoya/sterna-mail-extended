package app.sterna.core.data.mail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Arming the delivery worker is NOT queueing the send, and [armOrNote] is where that is decided.
 *
 * By the time `MailRepository.enqueueSend` arms, the row is inserted and its payload staged: the
 * message is durable, it is in the Outbox, and `unfinishedOutbox` re-arms it at the next app start
 * (`SternaApplication`). So a scheduler that throws costs a DELAY, never the mail — while relaying
 * that throw tells the caller nothing was queued about a message that is queued, and the caller
 * acts on it: `quickReplyAct(Send, queued = false)` hands the text back and keeps the notification,
 * so the person sends the same mail again and the recipient gets it twice.
 *
 * ⛔ The neighbouring guard must NOT be confused with this one: a STAGING failure still destroys
 * the row and relays ([stageOrRollback], [EnqueueRollbackTest]) — an orphan with `attachmentsJson
 * "[]"` would be re-armed and sent amputated (#70). Staging failed means the row is bad; arming
 * failed means the row is good.
 *
 * What each assertion holds:
 *  - a throwing scheduler returns the id — the defect itself, restored the moment it re-throws;
 *  - the reason reaches [note] carrying the exception's own message, so the row can say why it is
 *    late instead of being silently late;
 *  - a CLEAN arming passes the item's own id and hold, whole: `(id, 0)` would fire a scheduled
 *    send or an undo window immediately, and a wrong id would arm somebody else's row;
 *  - a cancellation is re-thrown and noted NOWHERE ([rethrowIfCancelled], the house idiom): a
 *    cancelled coroutine is an instruction to stop, not a failure to record;
 *  - a [note] that throws still returns the id — recording the reason may not become a second way
 *    to fail the queueing it reports on.
 */
class ArmingIsNotQueueingTest {

    /** A distinctive row id and hold, so a swapped or defaulted argument cannot look right. */
    private val rowId = 4242L
    private val holdMs = 5000L

    private class Armed(val id: Long, val delay: Long)

    private class RecordingScheduler : OutboxScheduler {
        val calls = mutableListOf<Armed>()
        override fun schedule(id: Long, initialDelayMillis: Long) {
            calls += Armed(id, initialDelayMillis)
        }
    }

    private class ArmingFailed : IllegalStateException("WorkManager is not initialised")

    @Test fun `a scheduler that throws still reports the send as queued`() = runTest {
        var noted: String? = null
        val returned = armOrNote(rowId, holdMs, OutboxScheduler { _, _ -> throw ArmingFailed() }) { noted = it }

        assertEquals("a failed arming must still answer the row id — the send IS queued", rowId, returned)
        assertTrue(
            "the note must carry the arming failure's own message, or the row cannot say why it " +
                "is late; got: $noted",
            noted?.contains("WorkManager is not initialised") == true,
        )
    }

    @Test fun `a clean arming passes the row's own id and hold, and notes nothing`() = runTest {
        val scheduler = RecordingScheduler()
        var noted: String? = null
        val returned = armOrNote(rowId, holdMs, scheduler) { noted = it }

        assertEquals(rowId, returned)
        assertNull("a clean arming must leave no failure text on the row", noted)
        assertEquals("exactly one arming", 1, scheduler.calls.size)
        assertEquals("the id armed must be the row's own", rowId, scheduler.calls[0].id)
        assertEquals(
            "the delay armed must be the hold the caller asked for — 0 would fire a scheduled " +
                "send, or an undo window, immediately",
            holdMs,
            scheduler.calls[0].delay,
        )
    }

    @Test fun `no scheduler at all is not a failure`() = runTest {
        var noted: String? = null
        val returned = armOrNote(rowId, holdMs, null) { noted = it }

        assertEquals(rowId, returned)
        assertNull("a null scheduler is a test/headless build, not something to record", noted)
    }

    @Test fun `a cancellation is re-thrown and never recorded`() = runTest {
        var noted: String? = null
        var caught: Throwable? = null
        try {
            armOrNote(rowId, holdMs, OutboxScheduler { _, _ -> throw CancellationException("stopped") }) { noted = it }
        } catch (t: CancellationException) {
            caught = t
        }

        assertEquals("stopped", caught?.message)
        assertNull("a cancelled arming must write nothing on the row", noted)
    }

    @Test fun `a note that throws does not un-queue the send`() = runTest {
        val returned = armOrNote(rowId, holdMs, OutboxScheduler { _, _ -> throw ArmingFailed() }) {
            throw IllegalStateException("the database is closed")
        }

        assertEquals("writing the reason may not fail the queueing it reports on", rowId, returned)
    }

    @Test fun `the note names the failure, and falls back to its class when it has no message`() {
        val withMessage = unarmedNote(ArmingFailed())
        assertTrue(
            "the reason must be quoted; got: $withMessage",
            "WorkManager is not initialised" in withMessage,
        )
        assertTrue(
            "the note must say the item is queued but unarmed, and that the message is still " +
                "there — that is the whole diagnostic value; got: $withMessage",
            withMessage.length > "WorkManager is not initialised".length,
        )

        val blank = unarmedNote(IllegalStateException(" "))
        assertTrue(
            "a null or blank message must fall back to the exception's own class name, never to " +
                "an empty reason; got: $blank",
            "IllegalStateException" in blank,
        )
    }
}
