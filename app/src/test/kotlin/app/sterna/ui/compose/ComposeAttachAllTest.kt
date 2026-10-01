package app.sterna.ui.compose

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ComposeAttachAllTest {

    private val status = MutableStateFlow<String?>(null)
    private val landed = mutableListOf<String>()
    private val seenWhileAttaching = mutableListOf<String?>()
    private val said = mutableListOf<AttachAllWord>()
    private var online = true

    private fun say(word: AttachAllWord): String {
        said += word
        return when (word) {
            is AttachAllWord.OneFailed -> "one:${word.failure.error.message}"
            is AttachAllWord.SomeFailed -> "some:${word.failures.size}/${word.total}"
        }
    }

    private suspend fun run(items: List<String>, attachOne: suspend (String) -> Unit) =
        attachInSequence(items, status, ATTACHING, online = { online }, attachOne = { item ->
            seenWhileAttaching += status.value
            attachOne(item)
        }, say = ::say)

    @Test fun `rows follow the pick even when the first file is the slowest`() = runTest {
        run(listOf("big.mov", "a.jpg", "b.jpg")) { item ->
            if (item == "big.mov") delay(10_000)
            landed += item
        }
        assertEquals(listOf("big.mov", "a.jpg", "b.jpg"), landed)
        assertNull("everything landed: the status line says nothing", status.value)
        assertEquals(listOf(ATTACHING, ATTACHING, ATTACHING), seenWhileAttaching)
    }

    @Test fun `a failure in the middle does not stop the next files and keeps the single sentence`() = runTest {
        run(listOf("a.jpg", "huge.iso", "c.jpg")) { item ->
            if (item == "huge.iso") throw IOException("too large")
            landed += item
        }
        assertEquals(listOf("a.jpg", "c.jpg"), landed)
        assertEquals("one:too large", status.value)
        assertEquals(listOf(ATTACHING, ATTACHING, ATTACHING), seenWhileAttaching)
    }

    @Test fun `two failures are told as a count of the pick`() = runTest {
        run(listOf("bad1", "a.jpg", "bad2")) { item ->
            if (item.startsWith("bad")) throw IOException(item)
            landed += item
        }
        assertEquals(listOf("a.jpg"), landed)
        assertEquals("some:2/3", status.value)
        val word = said.single() as AttachAllWord.SomeFailed
        assertEquals(listOf("bad1", "bad2"), word.failures.map { it.error.message })
    }

    @Test fun `an empty pick calls nothing and leaves the status line as it was`() = runTest {
        status.value = "an earlier line"
        run(emptyList()) { landed += it }
        assertTrue(seenWhileAttaching.isEmpty())
        assertTrue(said.isEmpty())
        assertEquals("an earlier line", status.value)
    }

    @Test fun `cancelled midway leaves no attaching line and no failure word`() = runTest {
        val job = launch {
            run(listOf("a.jpg", "hang", "c.jpg")) { item ->
                if (item == "hang") awaitCancellation()
                landed += item
            }
        }
        runCurrent()
        assertEquals(ATTACHING, status.value)
        job.cancel()
        job.join()
        assertEquals(listOf("a.jpg"), landed)
        assertTrue("a cancellation is not a failure, said: $said", said.isEmpty())
        assertNull(status.value)
    }

    @Test fun `the word is chosen from the failure count`() {
        assertNull(attachAllWord(emptyList(), 4))
        val only = AttachFailure(IOException("x"), online = true)
        assertEquals(AttachAllWord.OneFailed(only), attachAllWord(listOf(only), 4))
        val two = listOf(AttachFailure(IOException("y"), true), AttachFailure(IOException("z"), false))
        assertEquals(AttachAllWord.SomeFailed(two, 4), attachAllWord(two, 4))
    }

    @Test fun `a line another path writes during a successful pick is left standing`() = runTest {
        run(listOf("a.jpg", "b.jpg")) { item ->
            if (item == "b.jpg") status.value = VANISHED
            landed += item
        }
        assertEquals(listOf("a.jpg", "b.jpg"), landed)
        assertEquals(VANISHED, status.value)
    }

    @Test fun `the connection is read when the file fails, not when the pick ends`() = runTest {
        run(listOf("bad", "a.jpg")) { item ->
            if (item == "bad") throw IOException("unreachable")
            online = false
            landed += item
        }
        val word = said.single() as AttachAllWord.OneFailed
        assertEquals("online when bad failed, offline only by the end", true, word.failure.online)
    }

    @Test fun `each failure keeps its own reading of the connection`() = runTest {
        run(listOf("bad1", "bad2")) { item ->
            online = item != "bad1"
            throw IOException(item)
        }
        val word = said.single() as AttachAllWord.SomeFailed
        assertEquals(listOf(false, true), word.failures.map { it.online })
    }

    private companion object {
        const val ATTACHING = "Attaching…"
        const val VANISHED = "Attachments that are no longer available on this phone have been removed."
    }
}
