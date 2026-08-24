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
 * read, `OutboxLogic.isReadyToSend` says whether it may go out at all, and
 * `accountDepartureIsProven` says whether a `null` from `credentials()` really means the account
 * left (`AccountDepartureProofTest` runs it on its four inputs).
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

    /**
     * ⛔ Pins THREE things in one sequence, because it is their ORDER that is the fix.
     *
     * 1. The parking runs on the row as it was READ, ahead of every other question.
     * 2. `SENDING` is written on the row BEFORE the message is handed to the server.
     * 3. A `null` from `credentials()` destroys nothing until the departure is PROVEN, and the
     *    proof is DELEGATED to `accountDepartureIsProven` — the one place a JVM test can execute
     *    the rule (`AccountDepartureProofTest`) — instead of being restated here as a `null` check.
     *    The unproven arm writes NOTHING on the row: no state, no attemptCount, no lastError. That
     *    is not the same contract as the parking above it, which does write the row back; here
     *    nothing was attempted, so a bump would spend one of `OutboxLogic.MAX_ATTEMPTS` the user
     *    never used and an overwrite would erase an earlier failure's reason.
     */
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
                "it after performSend, compiles. ⛔ AND a null from credentials() may destroy the " +
                "row only once accountDepartureIsProven says the account really left: the unproven " +
                "arm keeps the row, writes nothing on it, and returns a bare retry():",
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
                // ⛔ Both halves, handed to the one place a JVM test can execute the rule. Dropping
                // the first one makes an unreadable blob read as "every account left at once", and
                // this row is the only copy of the message left on the phone.
                "val departed = accountDepartureIsProven(",
                "accountsUnreadable = container.accountStore.accountsUnreadable(),",
                "accountStillListed = container.accountStore.accounts().any { it.id == item.accountId },",
                ")",
                "if (departed) {",
                "repo.deleteOutbox(id)",
                "return Result.success()",
                "}",
                // ⛔ A BARE retry, alone in its arm: no updateOutboxState, no attemptCount + 1, no
                // lastError. Nothing was attempted, so nothing may be spent or overwritten — and
                // MAX_ATTEMPTS counts deliveries, which this is not.
                "return Result.retry()",
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
            blockAt(lines, "override suspend fun doWork(): Result {", before = 0, size = 33),
        )
        assertTrue(
            "⛔ and the parking must sit ahead of the delivery in the file, not merely somewhere " +
                "in it: below performSend it would fire on the row of a message that has just left " +
                "for the second time, which is the duplicate itself",
            lines.indexOf("OutboxLogic.parkOnPickup(item.state)?.let { parked ->") <
                lines.indexOfFirst { "performSend" in it },
        )
    }

    /**
     * ⛔ Its own test, and the one that closes a window the block above cannot see through. That
     * block pins 33 lines; a `repo.deleteOutbox(id)` added ELSEWHERE in the file — in the catch, or
     * a line above `return try {` — falls outside it and used to pass. It is the mutation that
     * survived the same fix on the scheduled arm. So the destruction sites are counted: two, in
     * this order, and a third has to be argued for here before it can exist.
     */
    @Test fun `the queued row is destroyed in two places, and nowhere else`() {
        assertEquals(
            "⛔ a destruction of the outbox row was added, moved or removed. Once queued, this row " +
                "is the ONLY copy of the message: the proven-departure arm and the return of " +
                "performSend are the two places allowed to destroy it, in that order. Anything " +
                "else drops a message the user is still waiting to see leave — a delete a line " +
                "above `return try {` compiles and loses it on the first network failure",
            listOf(
                // 1. Proven departure: nothing can ever send this message again, so nothing is lost.
                "repo.deleteOutbox(id)",
                // 2. AFTER performSend returned. Moved above it, a throw would have destroyed the
                // only copy — and the catch below would then retry a row that no longer exists.
                "repo.deleteOutbox(id)",
            ),
            linesOf(WORKER).filter { "repo.deleteOutbox(" in it },
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
