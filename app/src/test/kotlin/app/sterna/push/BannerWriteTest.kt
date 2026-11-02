package app.sterna.push

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * A banner's Mark as read and Delete, executed on the three decisions the receiver and
 * [BannerWriteWorker] are built from: what the tap does to the banner, in what order, and what a
 * queued write does on each run.
 */
class BannerWriteTest {

    private val calls = mutableListOf<String>()

    private suspend fun tap(tap: BannerTap, dismiss: suspend () -> Unit = {}) = carryOutBannerTap(
        tap,
        dismiss = { calls += "dismiss"; dismiss() },
        enqueue = { calls += "enqueue(${it.key})" },
    )

    @Test fun `a delete that would move nothing keeps its banner and queues nothing`() = runBlocking {
        val decided = bannerTap(BannerWrite.DELETE, signedIn = true, BannerDeleteAct.DoNothing)
        assertEquals(
            "already in Trash, or no Trash known (#171): taking the banner down would say the " +
                "message was deleted while it stays where it is",
            BannerTap.KeepBanner,
            decided,
        )
        tap(decided)
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun `a delete whose decision was never read keeps its banner`() {
        assertEquals(BannerTap.KeepBanner, bannerTap(BannerWrite.DELETE, signedIn = true, deleteAct = null))
    }

    @Test fun `a signed-out account keeps its banner, for both buttons`() {
        assertEquals(BannerTap.KeepBanner, bannerTap(BannerWrite.DELETE, signedIn = false, BannerDeleteAct.MoveToTrash))
        assertEquals(BannerTap.KeepBanner, bannerTap(BannerWrite.MARK_READ, signedIn = false, deleteAct = null))
    }

    @Test fun `an action the receiver does not know keeps its banner`() {
        assertEquals(BannerTap.KeepBanner, bannerTap(BannerWrite.ofAction("app.sterna.action.OTHER"), true, null))
    }

    @Test fun `a delete that moves and a mark as read take the banner down, then queue their write`() = runBlocking {
        val delete = bannerTap(BannerWrite.DELETE, signedIn = true, BannerDeleteAct.MoveToTrash)
        assertEquals(BannerTap.HandOver(BannerWrite.DELETE), delete)
        tap(delete)
        val read = bannerTap(BannerWrite.MARK_READ, signedIn = true, deleteAct = null)
        assertEquals(BannerTap.HandOver(BannerWrite.MARK_READ), read)
        tap(read)
        assertEquals(
            "the banner falls first and the write is queued after it, once per tap: a write not " +
                "queued is a message that stays in the inbox with its banner gone",
            listOf("dismiss", "enqueue(delete)", "dismiss", "enqueue(read)"),
            calls,
        )
    }

    @Test fun `a dismissal that throws after the cancel still queues the write`() = runBlocking {
        val refused = IllegalStateException("summary")
        try {
            tap(BannerTap.HandOver(BannerWrite.DELETE), dismiss = { throw refused })
            fail("the throw must still reach onReceive's log line")
        } catch (e: IllegalStateException) {
            assertSame(refused, e)
        }
        assertEquals(listOf("dismiss", "enqueue(delete)"), calls)
    }

    private suspend fun attempt(attempt: Int, write: suspend () -> Unit): BannerWriteOutcome = attemptBannerWrite(
        attempt = attempt,
        write = { calls += "write"; write() },
        failed = { calls += "failed(${it.javaClass.simpleName})" },
        giveUp = { calls += "repost" },
    )

    @Test fun `a write that lands is done, on any attempt, and never puts the banner back`() = runBlocking {
        val outcomes = (0 until BANNER_WRITE_ATTEMPTS).map { attempt(it) {} }
        assertEquals(List(BANNER_WRITE_ATTEMPTS) { BannerWriteOutcome.Done }, outcomes)
        assertEquals(
            "a banner reposted after a write that landed is a message announced again for nothing",
            List(BANNER_WRITE_ATTEMPTS) { "write" },
            calls,
        )
    }

    @Test fun `a write that fails before the last attempt is retried, the banner still down`() = runBlocking {
        val outcomes = (0 until BANNER_WRITE_ATTEMPTS - 1).map { attempt(it) { throw IOException() } }
        assertEquals(List(BANNER_WRITE_ATTEMPTS - 1) { BannerWriteOutcome.Retry }, outcomes)
        assertEquals(List(BANNER_WRITE_ATTEMPTS - 1) { listOf("write", "failed(IOException)") }.flatten(), calls)
    }

    @Test fun `the third failed attempt gives up and puts the banner back, once`() = runBlocking {
        assertEquals("three attempts, as for a held-back destroy", 3, BANNER_WRITE_ATTEMPTS)
        val outcome = attempt(2) { throw IOException() }
        assertEquals(
            "given up for good: the row is back in the list, and without its banner the user has " +
                "no hold left on it",
            BannerWriteOutcome.GaveUp,
            outcome,
        )
        assertEquals(listOf("write", "failed(IOException)", "repost"), calls)
    }

    @Test fun `a failure on a run counted past the last attempt still gives up`() = runBlocking {
        assertEquals(
            "WorkManager raises its count at every start, a run it stopped included",
            BannerWriteOutcome.GaveUp,
            attempt(BANNER_WRITE_ATTEMPTS + 1) { throw IOException() },
        )
    }

    @Test fun `a run stopped by WorkManager is neither a failure nor a give-up`() = runBlocking {
        val stopped = CancellationException("constraints lost")
        try {
            attempt(BANNER_WRITE_ATTEMPTS - 1) { throw stopped }
            fail("a cancellation must reach WorkManager, which reschedules the run")
        } catch (e: CancellationException) {
            assertSame(stopped, e)
        }
        assertEquals(listOf("write"), calls)
    }

    @Test fun `a queued delete still able to move goes ahead`() {
        assertEquals(null, queuedDeleteUnwritten(cachedRole = "inbox", hasCachedTrash = true))
        assertEquals(
            "a row the cache has lost is the ordinary path, as at the tap",
            null,
            queuedDeleteUnwritten(cachedRole = null, hasCachedTrash = false),
        )
    }

    @Test fun `a queued delete whose message reached the Trash is done, with no banner given back`() {
        assertEquals(BannerUnwritten.Drop, queuedDeleteUnwritten(cachedRole = "trash", hasCachedTrash = true))
        assertEquals(BannerUnwritten.Drop, queuedDeleteUnwritten(cachedRole = "trash", hasCachedTrash = false))
    }

    @Test fun `a queued delete that lost its Trash since the tap gives the banner back`() {
        assertEquals(
            "the message is still in its folder, and its banner is already down: finishing in " +
                "silence leaves the user nothing that says the Delete never went",
            BannerUnwritten.GiveBannerBack,
            queuedDeleteUnwritten(cachedRole = "inbox", hasCachedTrash = false),
        )
    }

    @Test fun `a queued write without credentials gives the banner back unless the account is gone`() {
        assertEquals(
            "an account removed since the tap has no message left to announce",
            BannerUnwritten.Drop,
            bannerUnwrittenWithoutCredentials(accountKnown = false),
        )
        assertEquals(
            "an account still there whose secret did not read: the message stays where it was, " +
                "and its banner is the user's only hold on it",
            BannerUnwritten.GiveBannerBack,
            bannerUnwrittenWithoutCredentials(accountKnown = true),
        )
    }

    @Test fun `the queued work is named per account, per message and per write`() {
        assertEquals("banner-delete-a1-e1", bannerWriteWorkName("a1", "e1", BannerWrite.DELETE))
        assertNotEquals(
            "two accounts of one server can share an email id (#31)",
            bannerWriteWorkName("a1", "e1", BannerWrite.DELETE),
            bannerWriteWorkName("a2", "e1", BannerWrite.DELETE),
        )
        assertNotEquals(
            "a queued Mark as read must not keep out a Delete of the same message",
            bannerWriteWorkName("a1", "e1", BannerWrite.MARK_READ),
            bannerWriteWorkName("a1", "e1", BannerWrite.DELETE),
        )
    }

    @Test fun `the receiver's actions and the queued keys map to the same writes`() {
        assertEquals(BannerWrite.MARK_READ, BannerWrite.ofAction(NotificationActionReceiver.ACTION_MARK_READ))
        assertEquals(BannerWrite.DELETE, BannerWrite.ofAction(NotificationActionReceiver.ACTION_DELETE))
        assertEquals(null, BannerWrite.ofAction(NotificationActionReceiver.ACTION_REPLY))
        BannerWrite.entries.forEach { assertEquals(it, BannerWrite.ofKey(it.key)) }
    }
}
