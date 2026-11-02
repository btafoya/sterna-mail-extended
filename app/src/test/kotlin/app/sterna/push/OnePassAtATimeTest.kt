package app.sterna.push

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every wake of an IDLE launches a pass, and with `EXPUNGE` counted our own deletes wake it too:
 * ten deletes in a row must not become ten passes racing on one notification baseline (#278).
 */
class OnePassAtATimeTest {

    /** A pass that holds until its gate opens, counting how many run at once. */
    private class Gated {
        val gates = List(20) { CompletableDeferred<Unit>() }
        var started = 0
        var inFlight = 0
        var maxInFlight = 0

        suspend fun pass() {
            val mine = started++
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                gates[mine].await()
            } finally {
                inFlight--
            }
        }

        fun release(index: Int) = gates[index].complete(Unit)
    }

    private fun TestScope.passes() = OnePassAtATime(this)

    @Test
    fun `a wake during a pass buys exactly one more pass, after it`() = runTest {
        val gated = Gated()
        val passes = passes()

        passes.request("login") { gated.pass() }
        advanceUntilIdle()
        passes.request("login") { gated.pass() }
        advanceUntilIdle()
        assertEquals("the second wake started a pass while the first still ran", 1, gated.started)

        gated.release(0)
        advanceUntilIdle()
        assertEquals("the wake that came during the pass was lost", 2, gated.started)

        gated.release(1)
        advanceUntilIdle()
        assertEquals("a pass ran that nobody asked for", 2, gated.started)
    }

    @Test
    fun `ten wakes during a pass buy one more pass, not ten`() = runTest {
        val gated = Gated()
        val passes = passes()

        passes.request("login") { gated.pass() }
        advanceUntilIdle()
        repeat(10) { passes.request("login") { gated.pass() } }
        advanceUntilIdle()
        gated.release(0)
        advanceUntilIdle()
        gated.release(1)
        advanceUntilIdle()

        assertEquals(2, gated.started)
        assertEquals("two passes of one login ran at once", 1, gated.maxInFlight)
    }

    @Test
    fun `wakes landing before the first pass even started still run one pass at a time`() = runTest {
        val gated = Gated()
        val passes = passes()

        repeat(10) { passes.request("login") { gated.pass() } }
        advanceUntilIdle()
        (0 until 10).forEach { gated.release(it) }
        advanceUntilIdle()

        assertEquals("two passes of one login ran at once", 1, gated.maxInFlight)
        assertEquals(2, gated.started)
    }

    @Test
    fun `once the passes are over, the next wake starts a new one`() = runTest {
        val gated = Gated()
        val passes = passes()

        passes.request("login") { gated.pass() }
        advanceUntilIdle()
        gated.release(0)
        advanceUntilIdle()
        passes.request("login") { gated.pass() }
        advanceUntilIdle()

        assertEquals("the key stayed taken after its pass ended", 2, gated.started)
        gated.release(1)
    }

    @Test
    fun `the pass waiting is the latest one asked for`() = runTest {
        val ran = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val passes = passes()

        passes.request("login") { ran += "first"; gate.await() }
        advanceUntilIdle()
        passes.request("login") { ran += "stale" }
        passes.request("login") { ran += "latest" }
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("first", "latest"), ran)
    }

    @Test
    fun `two logins do not wait on each other`() = runTest {
        val gated = Gated()
        val passes = passes()

        passes.request("alex") { gated.pass() }
        passes.request("sam") { gated.pass() }
        advanceUntilIdle()

        assertEquals(2, gated.maxInFlight)
        gated.release(0)
        gated.release(1)
    }

    @Test
    fun `a pass that throws does not keep its key taken`() = runTest {
        val scope = CoroutineScope(
            StandardTestDispatcher(testScheduler) + SupervisorJob() + CoroutineExceptionHandler { _, _ -> },
        )
        val passes = OnePassAtATime(scope)
        var after = 0

        passes.request("login") { error("boom") }
        advanceUntilIdle()
        passes.request("login") { after++ }
        advanceUntilIdle()

        assertEquals(1, after)
    }
}
