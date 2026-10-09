package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source lint, read as text, and a last resort: what it pins happens inside `delete`, `archive`,
 * `moveToMailbox` and the sync passes, which need a server to be entered at all, so no JVM test
 * can execute these lines. [SwipeHoldsTheRowItEvictedTest] executes what can be executed, the
 * holding and the put-back; this pins the call sites that must read the held row rather than the
 * cache, and the inventory of cache writes that must not put a held row back.
 */
class SwipeInFlightWiringTest {

    /** The statements of [function], comments off and the body's own opening brace with them, so
     *  the first element is the first thing the function does. */
    private fun lines(function: String): List<String> =
        DaoQuerySource.mailFunctionBody("MailRepository", function)
            .lines().map { it.trim() }
            .filterNot {
                it.isBlank() || it == "{" || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
            }

    @Test fun `each mover reads its row through the flight, never straight from the cache`() {
        for (mover in listOf("delete", "archive", "moveToMailbox")) {
            val body = lines(mover)
            assertEquals(
                "$mover must take its row from movingRow: a swipe evicts the row before calling " +
                    "it, so a cache read answers null and takes four things down with it — the " +
                    "destination's badge is never credited, the `already in this folder` test goes " +
                    "blind, the opt-in mark-read-on-the-way-out stops firing, and the folder the " +
                    "JMAP branch names for its state advance is lost. Body was:\n" +
                    body.joinToString("\n"),
                emptyList<String>(),
                body.filter { "emailDao.emailsByIds(" in it || "emailDao.seenOf(" in it },
            )
            assertTrue(
                "$mover must ask movingRow for it. Body was:\n" + body.joinToString("\n"),
                body.any { "movingRow(credentials.id, emailId)" in it },
            )
        }
    }

    @Test fun `every count nudge of those movers says whether the source already left`() {
        val expected = mapOf(
            "delete" to listOf(
                "adjustCountsForRemoval(listOfNotNull(row), trash, sourceAlreadyLeft = moving.evicted)",
                "adjustCountsForRemoval(listOfNotNull(row), trash, sourceAlreadyLeft = moving.evicted)",
            ),
            "archive" to listOf(
                "adjustCountsForRemoval(listOfNotNull(row), dest, sourceAlreadyLeft = moving.evicted)",
                "adjustCountsForRemoval(listOfNotNull(row), target, sourceAlreadyLeft = moving.evicted)",
            ),
            "moveToMailbox" to listOf(
                "adjustCountsForRemoval(listOfNotNull(moved), targetMailboxId, sourceAlreadyLeft = moving.evicted)",
                "adjustCountsForRemoval(listOfNotNull(moved), targetMailboxId, sourceAlreadyLeft = moving.evicted)",
            ),
        )
        expected.forEach { (mover, nudges) ->
            assertEquals(
                "the IMAP arm and the JMAP arm of $mover must both carry the flag, whole: the " +
                    "source folder was decounted when the finger left, and decounting it again on " +
                    "the ack takes the message off its folder twice — a badge one short until the " +
                    "next sync, which is exactly the kind of drift no one reports.",
                nudges,
                lines(mover).filter { "adjustCountsForRemoval(" in it },
            )
        }
    }

    @Test fun `a message the server says is gone loses its way back before anything else`() {
        val body = lines("pruneServerGone")
        assertEquals(
            "pruneServerGone must forfeit the held row FIRST. `notFound` is the server saying the " +
                "message is not there any more: it is not a refused write, and the put-back of a " +
                "write that moved nothing must find nothing left to put back. Body was:\n" +
                body.joinToString("\n"),
            "forfeitInFlight(localAccountId, emailIds)",
            body.first(),
        )
    }

    /**
     * The inventory, and the reason it is a list and not a count: a new cache write is a decision
     * about a row in flight whether or not its author thought about one, and this is what makes
     * the decision be taken. It reads the arguments, so it can tell a guarded write from a bare
     * one; it says nothing about what any of these writes would actually do at run time.
     */
    @Test fun `every write into the email cache has been judged against the rows in flight`() {
        val calls = upsertCalls()
        assertTrue(
            "only ${calls.size} emailDao.upsertAll calls found in MailRepository — did the call " +
                "shape change? An empty scan would pass this rule while checking nothing.",
            calls.size >= 10,
        )
        assertEquals(
            "one write reaches the cache as a reference and not as a call, the page of an IMAP " +
                "folder read: it must be handed to `ImapFolderWrites` together with the sparing " +
                "that class applies before it writes, and nothing else may take that door. The " +
                "first sparing is the IMAP unread catch-up's, whose write is an insert and no " +
                "upsert: it is handed to `imapUnreadCache`, which applies it to each page before " +
                "the insert (executed in ImapUnreadBackfillTest).",
            listOf("sparingInFlight = ::sparingInFlight,", "sparingInFlight = ::sparingInFlight,", "upsertAll = emailDao::upsertAll,"),
            DaoQuerySource.mailSource("MailRepository").lines().map { it.trim() }
                .filter { "::sparingInFlight" in it || "emailDao::upsertAll" in it },
        )
        assertEquals(
            "these are the writes that may reach the cache WITHOUT sparing the rows a swipe is " +
                "holding, and each is on this list for its own reason: `putBack` IS the " +
                "put-back; the two `fetched` writes are the Undo's own restore, which exists to " +
                "bring a row back; `refiled` is a bulk move the app itself just made, with the " +
                "rows it computed, not a page any server sent. Anything else here is a page read " +
                "from the server: the message is still in the folder until the move lands, so the " +
                "page names it, and writing it puts the line back on screen a second after the " +
                "finger took it off.",
            listOf(
                "putBack",
                "refiled",
                "fetched",
                "fetched.map { it.toEntity(ctx.credentials.id, source) }",
            ),
            calls.filterNot { it.startsWith("sparingInFlight(") },
        )
    }

    /** The argument text of every `emailDao.upsertAll(…)` in `MailRepository`, whitespace squeezed
     *  so a call the formatter wrapped over three lines reads as one. */
    private fun upsertCalls(): List<String> {
        val source = DaoQuerySource.mailSource("MailRepository")
        val call = "emailDao.upsertAll("
        val out = mutableListOf<String>()
        var i = source.indexOf(call)
        while (i >= 0) {
            var depth = 1
            var j = i + call.length
            while (depth > 0) {
                when (source[j]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                j++
            }
            out += source.substring(i + call.length, j - 1).replace(Regex("""\s+"""), " ").trim()
            i = source.indexOf(call, j)
        }
        return out
    }
}
