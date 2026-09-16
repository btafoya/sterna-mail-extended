package app.sterna.ui.message

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * [readMarkCleanup], executed: the decision that stands between a message marked read and the
 * destruction of its banner. Offline, the banner is the only thing left saying this mail arrived,
 * because `setRead` writes the cache after the round trip and a throw leaves neither server nor
 * cache touched. Destroying it there costs the notification for good: the next sync restores the
 * unread dot, silently, and no second banner is ever posted for mail already seen by the notifier.
 */
class ReadMarkCleanupTest {

    /** A cancellation raised deeper than the call, to check the decision looks at the type and not
     *  at one exact class. */
    private class PageGone : CancellationException("the pager moved on")

    // -- the nominal path, which must keep working ------------------------------------------------

    @Test fun `a write that returned lets the banner go`() {
        assertEquals(ReadMarkCleanup.DISMISS, readMarkCleanup(Result.success(Unit)))
    }

    // -- the failures, none of which may clear anything -------------------------------------------

    @Test fun `an unreachable server keeps the banner and is written down`() {
        assertEquals(
            ReadMarkCleanup.KEEP_AND_LOG,
            readMarkCleanup(Result.failure(UnknownHostException("jmap.example.test"))),
        )
    }

    @Test fun `a timeout keeps the banner and is written down`() {
        assertEquals(
            ReadMarkCleanup.KEEP_AND_LOG,
            readMarkCleanup(Result.failure(SocketTimeoutException("timeout"))),
        )
    }

    @Test fun `a refusal from the server keeps the banner and is written down`() {
        assertEquals(
            ReadMarkCleanup.KEEP_AND_LOG,
            readMarkCleanup(Result.failure(IllegalStateException("STORE failed: NO [OVERQUOTA]"))),
        )
    }

    @Test fun `no throwable but a cancellation ever clears a banner`() {
        listOf(
            IOException("broken pipe"),
            UnknownHostException("jmap.example.test"),
            SocketTimeoutException("timeout"),
            IllegalStateException("STORE failed"),
            IllegalArgumentException("no such mailbox"),
            RuntimeException(),
            Error("keystore is gone"),
        ).forEach { thrown ->
            assertNotEquals(
                "a write that threw wrote nothing, so $thrown may not take the banner with it",
                ReadMarkCleanup.DISMISS,
                readMarkCleanup(Result.failure(thrown)),
            )
        }
    }

    // -- the cancellation, which is not a failure -------------------------------------------------

    @Test fun `a cancelled write keeps the banner and says nothing`() {
        assertEquals(
            ReadMarkCleanup.KEEP_AND_RETHROW,
            readMarkCleanup(Result.failure(CancellationException("cleared mid-write"))),
        )
    }

    @Test fun `a cancellation raised further down is still a cancellation`() {
        assertEquals(ReadMarkCleanup.KEEP_AND_RETHROW, readMarkCleanup(Result.failure(PageGone())))
    }
}
