package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ⚠ THIS TEST READS SOURCE TEXT — the last resort, in exactly [SendDraftDestructionWiringTest]'s
 * form and for its reason: `MailRepository.enqueueSend` needs Room, a file system and a socket
 * (fifteen constructor dependencies), so no JVM test can run the wiring below. The DECISION is
 * executed by [ArmingIsNotQueueingTest]; this file pins ONLY where it is plugged in, whole lines —
 * `contains` inside a line is blind to any mutation that lengthens it.
 *
 * What it exists for: `enqueueSend` used to end on `outboxScheduler?.schedule(id, holdMs)` outside
 * every guard, after the row was already durable. A throw there travelled to the caller, which
 * reads it as "nothing was queued" (`QuickReply` → `queued = false` → the text is handed back and
 * the notification kept) for a message that IS in the Outbox and WILL be sent by the startup
 * re-arm. The person sends it again; the recipient gets it twice.
 *
 * Each line below, changed alone, brings that back while every executable test stays green:
 *  - the `armOrNote(...)` call reverted to a bare `outboxScheduler?.schedule(id, holdMs)`;
 *  - `armOrNote(id, holdMs, outboxScheduler)` given something other than the row's own id or the
 *    caller's hold — `0` fires a scheduled send or an undo window at once;
 *  - a second, naked `schedule(` left beside the guarded one, which would re-throw anyway;
 *  - `stageOrRollback(rollback = { deleteOutbox(id) })` weakened or removed, which is the OTHER
 *    half of the frontier and must not move: staging failed means the row is bad and must die
 *    (#70), arming failed means the row is good and must live.
 */
class ArmingWiringTest {

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    /** The code lines of [body] naming [needle] — comments dropped, so prose can neither satisfy
     *  a rule nor break one. Whole lines: the assertions compare them, never search inside them. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        codeLinesOf(body).filter { needle in it }

    /** Every code line of [body], comments and blanks dropped — for the one rule below that is an
     *  INVENTORY rather than a search: a needle can only see lines it was told to look for. */
    private fun codeLinesOf(body: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    @Test fun `queueing arms through the decision, with the row's own id and hold`() {
        assertEquals(
            "enqueueSend must arm through armOrNote — the function a test can execute — and hand " +
                "it the row id, the caller's hold and the injected scheduler. Any other delay " +
                "(0) fires a scheduled send or an undo window immediately; any other id arms " +
                "another row.",
            listOf("return armOrNote(id, holdMs, outboxScheduler) { note ->"),
            codeLinesNaming(bodyOf("enqueueSend"), "armOrNote("),
        )
    }

    @Test fun `the reason is written on the row, and touches nothing else`() {
        assertEquals(
            "the note must land in the row's lastError and change NOTHING else. " +
                "updateOutboxState would overwrite the state, turning a HELD row (scheduled " +
                "send, undo window) into a QUEUED one that leaves at once; a new state or a new " +
                "string would put an error on a screen for a message that is merely late. " +
                "lastError is printed only for FAILED rows (outboxStateShowsReason), so a " +
                "QUEUED/HELD row keeps saying 'waiting', which is true.",
            listOf("outboxDao.byId(id)?.let { outboxDao.update(it.copy(lastError = note)) }"),
            codeLinesNaming(bodyOf("enqueueSend"), "lastError"),
        )
        assertEquals(
            "queueing may not touch the row's state when arming fails",
            emptyList<String>(),
            codeLinesNaming(bodyOf("enqueueSend"), "updateOutboxState("),
        )
    }

    @Test fun `no naked arming survives beside the guarded one`() {
        assertEquals(
            "enqueueSend must contain no bare scheduler call: an unguarded schedule() relays its " +
                "throw to the caller, which reads it as 'nothing queued' about a durable row",
            emptyList<String>(),
            codeLinesNaming(bodyOf("enqueueSend"), "outboxScheduler?.schedule("),
        )
    }

    @Test fun `the staging guard on the other side of the frontier has not moved`() {
        assertEquals(
            "a staging failure must still delete the row and relay: an orphan carrying " +
                "attachmentsJson \"[]\" would be re-armed at the next startup and sent amputated " +
                "(#70). This is the opposite half of the rule armOrNote adds, and the branch that " +
                "adds one must not soften the other.",
            listOf("stageOrRollback(rollback = { deleteOutbox(id) }) {"),
            codeLinesNaming(bodyOf("enqueueSend"), "stageOrRollback("),
        )
    }

    /**
     * ⭐ The one rule here that is an INVENTORY, and the reason it had to be: every other assertion
     * in this file filters the body by a needle, so it is blind to a line that names none of them.
     * Adding `deleteOutbox(id)` inside the note lambda is exactly that line — it leaves all four
     * searches green while destroying the row the caller has just been told is queued. A quick
     * reply's text lives in no draft and no autosave (`QuickReply`), so the notification is
     * dismissed and the text is gone: worse than the defect this branch closes.
     *
     * So the tail is pinned WHOLE, closing braces included. It fails on any line added, removed or
     * reworded after the row is durable — which is the only stretch of `enqueueSend` where a
     * mistake can no longer be rolled back.
     */
    @Test fun `nothing else happens after the row is durable`() {
        val lines = codeLinesOf(bodyOf("enqueueSend"))
        val from = lines.indexOfFirst { it.startsWith("return armOrNote(") }
        check(from >= 0) { "enqueueSend no longer ends by arming through armOrNote" }
        assertEquals(
            "the tail of enqueueSend is pinned whole: past the insert and the staging, the row is " +
                "durable and the caller has been told it is queued, so anything added here acts on " +
                "a message the user believes is on its way. A destroy slipped in would take the " +
                "text away silently — a quick reply's text exists nowhere else.",
            listOf(
                "return armOrNote(id, holdMs, outboxScheduler) { note ->",
                "android.util.Log.w(\"MailRepository\", \"outbox \$id: \$note\")",
                "outboxDao.byId(id)?.let { outboxDao.update(it.copy(lastError = note)) }",
                "}",
                "}",
            ),
            lines.drop(from),
        )
    }
}
