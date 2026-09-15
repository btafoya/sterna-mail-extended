package app.sterna.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, same shape and same reason as [ScheduledSendFireWiringTest]: the worker needs a
 * `Context` and WorkManager, so its give-up decision is executed in `ScheduledSendRetryTest` and
 * only its wiring is pinned here, arguments included.
 */
class ScheduledSendFailureCountWiringTest {

    private fun linesOf(file: File): List<String> =
        file.readText().lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") || it.isEmpty() }

    private fun catchBlock(lines: List<String>): List<String> {
        val at = lines.indexOf("} catch (_: Throwable) {")
        assertTrue(
            "no line reads exactly '} catch (_: Throwable) {' in the worker:\n" + lines.joinToString("\n"),
            at >= 0,
        )
        return lines.drop(at)
    }

    /** Its own test: a failed assertion ends its method, so one rule would hide the other. */
    @Test fun `the worker never reads WorkManager's run count`() {
        assertEquals(
            "runAttemptCount is back in the worker. WorkManager raises it before EVERY doWork(), " +
                "whatever the previous run returned, and never resets it: the runs where the " +
                "account was not proven gone attempted nothing, yet they spend the budget, and the " +
                "first real failure afterwards gives up at once, destroying the only copy of the message",
            emptyList<String>(),
            linesOf(WORKER).filter { "runAttemptCount" in it },
        )
    }

    @Test fun `the catch decides on the failures stored on the row, and counts one before retrying`() {
        assertEquals(
            "the give-up decision must be handed the row's OWN failure count, through the rule " +
                "ScheduledSendRetryTest executes, and a retry must store that count plus one before " +
                "returning. The write sits in runCatching: a database that refuses to write still " +
                "retries, uncounted, so a storage failure can never open the path that destroys the row",
            listOf(
                "} catch (_: Throwable) {",
                "if (ScheduledSendRetry.shouldRetry(row.failedAttempts)) {",
                "runCatching { repo.sending.setScheduledSendFailedAttempts(id, row.failedAttempts + 1) }",
                "Result.retry()",
                "} else {",
            ),
            catchBlock(linesOf(WORKER)).take(5),
        )
    }

    /** Counting the order apart from the exact block, so a reshaped catch still has to honour it. */
    @Test fun `in the catch, the count is written before retry is returned`() {
        val catch = catchBlock(linesOf(WORKER))
        val write = catch.indexOfFirst { "repo.sending.setScheduledSendFailedAttempts(" in it }
        val retry = catch.indexOfFirst { it == "Result.retry()" }
        assertTrue("the catch no longer stores the failure count at all", write >= 0)
        assertTrue("the catch no longer returns Result.retry()", retry >= 0)
        assertTrue(
            "the failure count is written after Result.retry() is reached: the retry leaves " +
                "uncounted, and the row retries forever",
            write < retry,
        )
    }

    @Test fun `only the catch counts a failure`() {
        val lines = linesOf(WORKER)
        val handOver = lines.indexOfFirst { it == "repo.sending.enqueueSend(" }
        assertTrue("no line reads exactly 'repo.sending.enqueueSend(' any more", handOver >= 0)
        assertEquals(
            "[writes in the file, writes before the hand-over]: the failure count is written " +
                "exactly once, in the catch. Before the hand-over nothing has been attempted, the " +
                "unproven-departure arm writes nothing, and a count spent there is the very budget " +
                "this counter exists to keep",
            listOf(1, 0),
            listOf(
                lines.count { "repo.sending.setScheduledSendFailedAttempts(" in it },
                lines.take(handOver).count { "repo.sending.setScheduledSendFailedAttempts(" in it },
            ),
        )
    }

    private companion object {
        val WORKER = repoFile("app/src/main/kotlin/app/sterna/send/ScheduledSendWorker.kt")

        private fun repoFile(path: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, path) }
                .firstOrNull { it.isFile }
                ?: error(
                    "cannot find $path from ${File("").absolutePath} — this test reads sources as " +
                        "text and needs a working directory inside the checkout",
                )
    }
}
