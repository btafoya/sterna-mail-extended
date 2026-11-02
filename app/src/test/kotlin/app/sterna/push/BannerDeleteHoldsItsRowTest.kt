package app.sterna.push

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The banner's Delete holds its row in flight (#252), executed on [deleteHoldingItsRow] with the
 * three calls recorded in the order they were made. [BannerWriteWorker] runs in no JVM test, so
 * its hand-off to this function is pinned by source at the end, arguments included.
 */
class BannerDeleteHoldsItsRowTest {

    private val calls = mutableListOf<String>()

    private suspend fun run(
        act: BannerDeleteAct = BannerDeleteAct.MoveToTrash,
        flight: String? = "flight",
        delete: suspend () -> Unit = {},
    ): Boolean = deleteHoldingItsRow(
        act = act,
        begin = { calls += "begin"; flight },
        delete = { calls += "delete"; delete() },
        end = { held, restore -> calls += "end($held, restore=$restore)" },
    )

    @Test fun `the flight is open before the write leaves, and closed for good once it landed`() = runBlocking {
        val mayDismiss = run()
        assertEquals(
            "the flight must be open BEFORE the server write: opened after it, or not at all, a " +
                "sync page read before the move writes the row back into the inbox",
            listOf("begin", "delete", "end(flight, restore=false)"),
            calls,
        )
        assertEquals("a delete that moved the message takes the banner down", true, mayDismiss)
    }

    @Test fun `a write that threw puts the row back, and the throw still reaches the caller`() = runBlocking {
        val refused = IOException("no route to host")
        try {
            run(delete = { throw refused })
            fail("the failure must reach the worker, whose retries and give-up are what follow it")
        } catch (e: IOException) {
            assertSame(refused, e)
        }
        assertEquals(
            "a write that moved nothing must hand the row back: closed with restore=false, the " +
                "message is still in its folder at the server and gone from the list",
            listOf("begin", "delete", "end(flight, restore=true)"),
            calls,
        )
    }

    @Test fun `a write cut off by cancellation still closes its flight, putting the row back`() = runBlocking {
        val writing = CompletableDeferred<Unit>()
        val job = launch {
            deleteHoldingItsRow(
                act = BannerDeleteAct.MoveToTrash,
                begin = { calls += "begin"; "flight" },
                delete = { calls += "delete"; writing.complete(Unit); awaitCancellation() },
                // A suspension point, as the repository's put-back has: it only runs to its end
                // on a cancelled job when the closing is shielded from the cancellation.
                end = { held, restore -> yield(); calls += "end($held, restore=$restore)" },
            )
        }
        writing.await()
        job.cancelAndJoin()
        assertEquals(
            "a flight left open keeps its key in the repository for the life of the process",
            listOf("begin", "delete", "end(flight, restore=true)"),
            calls,
        )
    }

    @Test fun `with nothing to hold, the delete still goes out and there is no flight to close`() = runBlocking {
        val mayDismiss = run(flight = null)
        assertEquals(listOf("begin", "delete"), calls)
        assertEquals(true, mayDismiss)
    }

    @Test fun `doing nothing opens no flight, evicts nothing, and leaves the banner up`() = runBlocking {
        val mayDismiss = run(act = BannerDeleteAct.DoNothing)
        assertEquals(emptyList<String>(), calls)
        assertEquals(false, mayDismiss)
    }

    @Test fun `the worker hands its delete to the flight, with the repository's own begin and end`() {
        val body = bodyOf(WORKER, "doWork")
        assertEquals(
            "every line of the worker's run naming the repository, in order and with no exception: " +
                "the delete's decision read again from the cache, the mark-read write, the " +
                "flight opened and closed through `beginRemoval`/`endRemoval` for the account and " +
                "the message queued, and ONE `repo.delete`, inside the lambda. A `repo.delete` " +
                "anywhere else, guarded or not, is a delete that goes out with no flight open",
            listOf(
                "val repo = container.mailRepository",
                "val reads = bannerDeleteReadsFromCache(repo, accountId, emailId)",
                "BannerWrite.MARK_READ -> repo.setRead(credentials, emailId, true)",
                "begin = { repo.beginRemoval(accountId, emailId) },",
                "delete = { repo.delete(credentials, emailId) },",
                "end = { flight, restore -> repo.endRemoval(flight, restore) },",
            ),
            codeLinesNaming(body, "repo").filter { Regex("""\brepo\b""").containsMatchIn(it) },
        )
        assertEquals(
            "the flight is the worker's from end to end, in the one process that opened it: a " +
                "flight's key lives in that process's memory, and no other may close it",
            listOf("BannerWrite.DELETE -> deleteHoldingItsRow("),
            codeLinesNaming(body, "deleteHoldingItsRow("),
        )
    }

    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    private fun bodyOf(file: File, name: String): String {
        val source = file.readText()
        val fn = Regex("""\bfun\s+$name\s*\(""").find(source)
            ?: error("${file.name} has no function named '$name', was it renamed?")
        val open = source.indexOf('{', fn.range.last)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
            i++
        }
        error("Unbalanced braces in ${file.name}.$name")
    }

    companion object {
        private const val WORKER_PATH = "app/src/main/kotlin/app/sterna/push/BannerWriteWorker.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, WORKER_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }

        private val WORKER: File by lazy { File(root, WORKER_PATH) }
    }
}
