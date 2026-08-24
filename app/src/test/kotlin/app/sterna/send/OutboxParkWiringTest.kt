package app.sterna.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ⚠ SOURCE LINT. `OutboxWorker` is a `CoroutineWorker` reaching for `AppContainer` (Room,
 * WorkManager, a `Context`), and this module has neither Robolectric nor instrumented tests: a
 * `when` written inside `doWork` is unreachable from every test we own. The decision itself is
 * EXECUTED in `core/data` — `OutboxLogic.parkOnPickup` says what to do with the row that was just
 * read, `OutboxLogic.isReadyToSend` says whether it may go out at all.
 *
 * What those two cannot see is whether the worker asks them, and in which ORDER. That is what is
 * pinned here, as WHOLE LINES in sequence: a `contains` is blind to a lengthened line and to a line
 * moved inside a condition, and moving one line is exactly the mutation that reopens this defect.
 */
class OutboxParkWiringTest {

    private fun linesOf(file: File): List<String> =
        file.readText().lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") || it.isEmpty() }

    private fun blockAt(lines: List<String>, anchor: String, before: Int, size: Int): List<String> {
        val at = lines.indexOf(anchor)
        assertTrue("no line reads exactly:\n  $anchor\nthe file holds:\n" + lines.joinToString("\n"), at >= 0)
        return lines.subList(at - before, at - before + size)
    }

    @Test fun `a delivery the worker finds already begun is parked, before anything else happens to it`() {
        // ⛔ THE ORDER IS THE FIX. The parking must run on the row as it was READ, before
        // isReadyToSend, before the FAILED guard and before a single byte goes to the server.
        // Pushed down past isReadyToSend it is dead code today (a SENDING row is refused there and
        // the worker returns), and pushed down past the delivery it parks a message that has just
        // been sent for the second time — which is the bench measurement: reply, kill the process
        // 1.2 s later, reopen, and the recipient receives the same message twice, 46 s apart, with
        // two Message-Ids and an empty outbox left behind.
        val lines = linesOf(WORKER)
        assertEquals(
            "OutboxWorker.doWork must park the row it has just read BEFORE it asks whether the row " +
                "may go out, and hand the state back through OutboxLogic.parkOnPickup rather than " +
                "deciding one of its own — AND it must write SENDING down before handing the " +
                "message to the server. That write is the whole premise of the parking: without an " +
                "OutboxState.SENDING on the row before the delivery, nothing records that a " +
                "delivery ever STARTED, a killed run is found QUEUED at the next pickup, " +
                "parkOnPickup never fires, and the message is delivered a second time — the " +
                "recipient gets it twice and nothing can recall it. Deleting that line, or moving " +
                "it after performSend, compiles:",
            listOf(
                "override suspend fun doWork(): Result {",
                "val id = inputData.getLong(KEY_ID, -1L)",
                "if (id < 0) return Result.success()",
                "val container = (applicationContext as android.app.Application).container",
                "val repo = container.mailRepository",
                "val item = repo.outboxItem(id) ?: return Result.success() // sent, cancelled or deleted",
                // ⛔ The row is written back with its OWN attemptCount and lastError. Nothing was
                // attempted and nothing failed here: bumping the count would spend a retry the user
                // never used, and overwriting lastError would erase the reason of an earlier,
                // genuine failure that is still printed on the row.
                "OutboxLogic.parkOnPickup(item.state)?.let { parked ->",
                "repo.updateOutboxState(id, parked, item.attemptCount, item.lastError)",
                // ⛔ success(), not retry(): there is nothing left to attempt. A retry would bring
                // WorkManager back on a row that is now parked, forever.
                "return Result.success()",
                "}",
                "if (!OutboxLogic.isReadyToSend(item.state, item.notBeforeMillis, System.currentTimeMillis())) {",
                "if (item.state == OutboxState.HELD) {",
                "Outbox.enqueue(applicationContext, id, item.notBeforeMillis - System.currentTimeMillis())",
                "}",
                "return Result.success()",
                "}",
                "if (item.state == OutboxState.FAILED) return Result.success() // no auto-retry once parked",
                "val credentials = container.accountStore.credentials(item.accountId) ?: run {",
                "repo.deleteOutbox(id) // account removed — drop the queued send",
                "return Result.success()",
                "}",
                // ⛔ SENDING, on the row, BEFORE the message leaves. This is the fact parkOnPickup
                // reads at the next pickup and the only proof this phone ever has that a delivery
                // was begun: a run killed one line below leaves SENDING written, and that is what
                // gets parked instead of sent again. Its own attemptCount and lastError are carried
                // through — nothing has failed at this point.
                "repo.updateOutboxState(id, OutboxState.SENDING, item.attemptCount, item.lastError)",
                "return try {",
                "repo.performSend(credentials, item)",
                // ⛔ and the row leaves the outbox only AFTER the send returned: deleted before, a
                // failure would have destroyed the only copy of the message.
                "repo.deleteOutbox(id)",
                "Result.success()",
            ),
            blockAt(lines, "override suspend fun doWork(): Result {", before = 0, size = 26),
        )
        assertTrue(
            "⛔ and the parking must sit ahead of the delivery in the file, not merely somewhere " +
                "in it: below performSend it would fire on the row of a message that has just left " +
                "for the second time, which is the duplicate itself",
            lines.indexOf("OutboxLogic.parkOnPickup(item.state)?.let { parked ->") <
                lines.indexOfFirst { "performSend" in it },
        )
    }

    @Test fun `the worker names no parked state of its own`() {
        val lines = linesOf(WORKER)
        assertEquals(
            "⛔ OutboxState.INTERRUPTED must not be written in the worker: the decision belongs to " +
                "OutboxLogic.parkOnPickup, which is the one place a JVM test can execute it. A " +
                "state named here is a rule nothing can run",
            emptyList<String>(),
            lines.filter { "OutboxState.INTERRUPTED" in it },
        )
    }

    private companion object {
        val WORKER = repoFile("app/src/main/kotlin/app/sterna/send/OutboxWorker.kt")

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
