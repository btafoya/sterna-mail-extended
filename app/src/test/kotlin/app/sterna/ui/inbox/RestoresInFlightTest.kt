package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a gesture does when the message it is about to write is still being moved back by an Undo,
 * executed. `InboxViewModel` cannot be built on the JVM, so the wait is its own type and this runs
 * it with stand-ins: a restore is a job parked on a deferred the test completes, a gesture is a
 * coroutine that settles and then says it resolved. That the view model's gestures ask before
 * they read the cache is pinned as text by `RestoresInFlightWiringLintTest`.
 *
 * Delete, archive, move and swipe are one case here, not four: they differ in the view model by
 * where the call sits, which only the lint can see.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoresInFlightTest {

    private val journal = mutableListOf<String>()
    private val restores = RestoresInFlight()

    private fun key(id: String, account: String = "acc") = EmailKey(account, id)

    private fun CoroutineScope.gesture(vararg keys: EmailKey): Job = launch {
        restores.settled(keys.toList())
        journal += "gesture resolves"
    }

    /** A scope that dies the way `viewModelScope` does: a child's failure takes nothing else down. */
    private fun TestScope.screen() = CoroutineScope(
        StandardTestDispatcher(testScheduler) + SupervisorJob() + CoroutineExceptionHandler { _, _ -> },
    )

    @Test fun `a gesture on a message being restored resolves nothing until the restore returns`() = runTest {
        val server = CompletableDeferred<Unit>()
        val restore = launch {
            server.await()
            journal += "restore lands"
        }
        restores.hold(listOf(key("m1"), key("m2")), restore)

        val gesture = gesture(key("m9"), key("m2"))
        runCurrent()

        assertEquals(
            "the restore is still out, so m2 is on screen, absent from the cache and in the bin at " +
                "the server: a gesture resolving now reads an empty cache, falls back to the drawn " +
                "row, and its write crosses the restore's. One held key among the gesture's is " +
                "enough to hold it whole. Journal was:\n" + journal.joinToString("\n"),
            emptyList<String>(),
            journal,
        )

        server.complete(Unit)
        gesture.join()

        assertEquals(
            "and once the restore is back the gesture goes, after it and not before",
            listOf("restore lands", "gesture resolves"),
            journal,
        )
    }

    @Test fun `a restore that throws frees the gesture waiting on it`() = runTest {
        val server = CompletableDeferred<Unit>()
        val restore = screen().launch {
            server.await()
            error("the server refused the restore")
        }
        restores.hold(listOf(key("m1")), restore)

        val gesture = gesture(key("m1"))
        runCurrent()
        assertEquals("held while the restore is out", emptyList<String>(), journal)

        server.complete(Unit)
        runCurrent()

        assertEquals(
            "a failed restore is over: the message is wherever the server left it, and the next " +
                "gesture must go on that state instead of waiting for a return that is not coming",
            listOf("gesture resolves"),
            journal,
        )
        assertEquals("the gesture ended on its own, it was not cut off", false, gesture.isCancelled)
    }

    @Test fun `a restore cancelled with its screen frees the gesture waiting on it`() = runTest {
        val screen = screen()
        val restore = screen.launch { awaitCancellation() }
        restores.hold(listOf(key("m1")), restore)

        val gesture = gesture(key("m1"))
        runCurrent()
        assertEquals("held while the restore is out", emptyList<String>(), journal)

        screen.cancel()
        runCurrent()

        assertEquals(
            "a restore cut off with its scope will never report: nothing may wait on it for ever",
            listOf("gesture resolves"),
            journal,
        )
        assertEquals(false, gesture.isCancelled)
    }

    @Test fun `a restore over before it is held holds nothing`() = runTest {
        val restore = launch { journal += "restore lands" }
        restore.join()
        restores.hold(listOf(key("m1")), restore)

        gesture(key("m1"))
        runCurrent()

        assertEquals(
            "the view model launches on an immediate dispatcher, so a restore with nothing to send " +
                "is finished by the time it is handed over",
            listOf("restore lands", "gesture resolves"),
            journal,
        )
    }

    @Test fun `a key no restore holds does not wait`() = runTest {
        val restore = screen().launch { awaitCancellation() }
        restores.hold(listOf(key("m1")), restore)
        val before = testScheduler.currentTime

        gesture(key("m2"), key("m1", account = "other"))
        runCurrent()

        assertEquals(
            "another message is not held, and neither is the same id in another account: two " +
                "accounts of one server share message ids (#31). Waiting for every restore would " +
                "pass the tests above and stall every unrelated gesture",
            listOf("gesture resolves"),
            journal,
        )
        assertEquals("and it costs no time at all", before, testScheduler.currentTime)
        restore.cancel()
    }

    @Test fun `an earlier restore ending does not free a key a later one still holds`() = runTest {
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        restores.hold(listOf(key("m1")), launch { first.await() })
        restores.hold(listOf(key("m1")), launch { second.await() })

        val gesture = gesture(key("m1"))
        first.complete(Unit)
        runCurrent()

        assertEquals(
            "the key is released by the restore that holds it now, not by whichever ends first",
            emptyList<String>(),
            journal,
        )

        second.complete(Unit)
        gesture.join()
        assertEquals(listOf("gesture resolves"), journal)
    }
}
