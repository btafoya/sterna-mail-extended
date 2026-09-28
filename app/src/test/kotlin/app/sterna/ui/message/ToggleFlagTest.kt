package app.sterna.ui.message

import app.sterna.core.jmap.model.Email
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Test
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * The star the reader toggles (#232). The header reads `_messages`, the action bar reads `_state`:
 * both must take the same optimistic value. `MessageViewModel` is not instantiable on the JVM, so the
 * value is tested by running [withFlag] and the wiring by reading the body of `toggleFlag`.
 */
class ToggleFlagTest {

    @Test
    fun `starring sets the flagged keyword and keeps the others`() {
        val email = Email(id = "1", keywords = mapOf("\$seen" to true))

        val starred = email.withFlag(true)

        assertTrue(starred.isFlagged)
        assertEquals(mapOf("\$seen" to true, "\$flagged" to true), starred.keywords)
    }

    @Test
    fun `unstarring removes the flagged keyword instead of storing false`() {
        val email = Email(id = "1", keywords = mapOf("\$seen" to true, "\$flagged" to true))

        val unstarred = email.withFlag(false)

        assertFalse(unstarred.isFlagged)
        assertEquals(mapOf("\$seen" to true), unstarred.keywords)
    }

    @Test
    fun `toggleFlag gives the header of the open message the same value as the action bar`() {
        val body = toggleFlagBody()

        assertTrue(
            "toggleFlag no longer sets the action bar's value from `current.withFlag(flagged)`",
            "_state.value = MessageState.Loaded(current.withFlag(flagged))" in body,
        )
        val update = Regex("""updateMessage\(current\.id\)\s*\{([^}]*)\}""").find(body)
        assertTrue(
            "toggleFlag does not call updateMessage(current.id) { … }: the header keeps the old star",
            update != null,
        )
        assertTrue(
            "toggleFlag's updateMessage does not set the header from `withFlag(flagged)`",
            "header = it.header.withFlag(flagged)" in update!!.groupValues[1],
        )
    }

    @Test
    fun `toggleFlag writes the star on the app scope, which Back does not cancel`() {
        val body = toggleFlagBody()

        assertFalse(
            "toggleFlag launches on viewModelScope: Back cancels the write before the server and the cache",
            "viewModelScope" in body,
        )
        val submit = Regex("""(?<![\w.])ReaderStarWrites\.submit\(appScope,\s*credentials\.id to current\.id,\s*flagged\)""").find(body)
        assertTrue(
            "toggleFlag does not hand its write to ReaderStarWrites.submit(appScope, credentials.id to current.id, flagged)",
            submit != null,
        )
        assertFalse(
            "MessageViewModel declares its own `ReaderStarWrites`: a new view model would bring a new lock",
            Regex("""(val|var)\s+ReaderStarWrites\b""").containsMatchIn(source.readText()),
        )
        assertTrue(
            "ReaderStarWrites is no longer an object: each holder would bring its own lock",
            Regex("""\bobject ReaderStarWrites\s*:\s*LatestWriteWins<""").containsMatchIn(gateSource.readText()),
        )
        assertTrue(
            "toggleFlag does not read `credentials()` at the tap, before ReaderStarWrites.submit",
            Regex("""val credentials = credentials\(\) \?: return\n""").containsMatchIn(body.substring(0, submit!!.range.first)),
        )
        assertFalse(
            "toggleFlag reads `credentials()` inside the write: a queued write takes the account of the page swiped to",
            "credentials()" in body.substring(submit.range.last),
        )
        assertTrue(
            "toggleFlag's write no longer sends the value handed out by the gate to repo.setFlagged",
            Regex("""repo\.setFlagged\(credentials,\s*current\.id,\s*wanted\)""").containsMatchIn(body),
        )
    }

    @Test
    fun `two taps whose writes are dispatched out of order end on the last tap`() {
        val dispatcher = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val gate = LatestWriteWins<String, Boolean>()
        val written = mutableListOf<Boolean>()

        gate.submit(scope, "m1", true) { written += it }
        gate.submit(scope, "m1", false) { written += it }
        dispatcher.runLastThenRest()

        assertEquals("the server and the cache must end on the second tap", false, written.last())
    }

    @Test
    fun `a tap during a slow write is written after it, never before`() {
        val dispatcher = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val gate = LatestWriteWins<String, Boolean>()
        val network = CompletableDeferred<Unit>()
        val written = mutableListOf<Boolean>()

        gate.submit(scope, "m1", true) { network.await(); written += it }
        dispatcher.drain()
        gate.submit(scope, "m1", false) { written += it }
        dispatcher.drain()
        network.complete(Unit)
        dispatcher.drain()

        assertEquals("the server and the cache must end on the second tap", listOf(true, false), written)
    }

    @Test
    fun `a write for one message never takes the value wanted for another`() {
        val dispatcher = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val gate = LatestWriteWins<String, Boolean>()
        val written = mutableListOf<Pair<String, Boolean>>()

        gate.submit(scope, "x", true) { written += "x" to it }
        gate.submit(scope, "y", false) { written += "y" to it }
        dispatcher.runLastThenRest()

        assertEquals(setOf("y" to false, "x" to true), written.toSet())
    }

    /** Runs dispatched blocks only when told to, so a test can choose their order. */
    private class ManualDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }

        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }

        fun runLastThenRest() {
            queue.removeLast().run()
            drain()
        }
    }

    /** The text of `fun toggleFlag()`, up to the next member at class level. */
    private fun toggleFlagBody(): String {
        val source = source.readText()
        val start = source.indexOf("fun toggleFlag()")
        assertTrue("MessageViewModel has no toggleFlag() any more", start > 0)
        val end = NEXT_MEMBER.find(source, start + 1)?.range?.first ?: source.length
        return source.substring(start, end)
    }

    private companion object {
        val NEXT_MEMBER = Regex("""\n {4}(private )?(suspend )?fun \w""")

        val source: File = locate("MessageViewModel.kt")
        val gateSource: File = locate("LatestWriteWins.kt")

        fun locate(name: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "app/src/main/kotlin/app/sterna/ui/message/$name") }
                .firstOrNull { it.isFile }
                ?: error("cannot locate $name from ${File("").absolutePath}")
    }
}
