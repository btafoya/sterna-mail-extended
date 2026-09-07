package app.sterna.push

import app.sterna.core.data.mail.PreviewSource
import app.sterna.core.data.settings.NotificationContent
import app.sterna.core.imap.ImapTextPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * What the notification pre-pass DOES, executed — not read.
 *
 * Everything it decides is here: whether it runs at all, how many messages it may read, how long
 * the whole of it may take, what it does when a read fails, and — the one that can lose mail —
 * that it happens BEFORE the first notification is posted and can never reach the caller as an
 * exception. The clock, the numbering and the fetch are handed in, so every one of those is a
 * verdict this test watches rather than a line it reads.
 *
 * The numbers are written out (6, 4000) rather than taken from the object: a test that asks the
 * shipped code what its own cap is agrees with any cap.
 */
class NotificationPreviewGatheringTest {

    // ---- the pre-pass: whether it runs, how much it reads -------------------------------

    /**
     * Nothing at all happens unless the reader asked for the body position. Not a fetch, not
     * even the numbering read: someone who chose "no details", or who is nursing a battery, must
     * not pay a round trip per message for a line they will never be shown.
     */
    @Test fun `the three positions that show no body ask the server for nothing`() = runBlocking {
        NotificationContent.entries.filter { it != NotificationContent.BODY_PREVIEW }.forEach { content ->
            val server = FakeServer(previews = { "an opening line" })
            val gathered = server.gather(content, ids(3))
            assertEquals("$content came back with previews", emptyMap<String, String>(), gathered)
            assertEquals("$content went to the server", emptyList<Long>(), server.fetched)
            assertEquals("$content read the folder numbering", 0, server.numberingReads)
        }
    }

    /** At the body position, each message's opening comes back under its own id. */
    @Test fun `the openings come back keyed by the message they belong to`() = runBlocking {
        val server = FakeServer(previews = { "opening of ${it.uid}" })
        assertEquals(
            mapOf("m1" to "opening of 1", "m2" to "opening of 2"),
            server.gather(NotificationContent.BODY_PREVIEW, ids(2)),
        )
    }

    /**
     * Six messages per pass, and no more. Past the sixth the reader is looking at the group
     * summary — which lists six — and not at the messages, so a seventh round trip is spent on a
     * line nobody opens. A burst of fifty arrivals is fifty round trips on one radio wake-up.
     */
    @Test fun `at most six messages of one pass are read`() = runBlocking {
        val server = FakeServer(previews = { "opening" })
        val gathered = server.gather(NotificationContent.BODY_PREVIEW, ids(50))
        assertEquals("the first six, in the order they will be announced", (1L..6L).toList(), server.fetched)
        assertEquals(6, gathered.size)
    }

    /**
     * A message with no readable text at all (one PDF, a calendar invitation) costs no round trip,
     * so it must not eat one of the six either — otherwise a folder of attachments would spend the
     * whole allowance on messages that have nothing to show.
     */
    @Test fun `a message with no readable text does not consume one of the six`() = runBlocking {
        val ids = ids(20)
        // Only the even ones have a text part.
        val sources = ids.filterIndexed { index, _ -> index % 2 == 1 }
            .associateWith { source(it.drop(1).toLong()) }
        val server = FakeServer(previews = { "opening" })
        server.gather(NotificationContent.BODY_PREVIEW, ids, sources)
        assertEquals(listOf(2L, 4L, 6L, 8L, 10L, 12L), server.fetched)
    }

    /**
     * And a pass where NOTHING has readable text touches nothing at all — the numbering included.
     * This is the ordinary JMAP pass: its rows carry their own preview, so it conveys no source,
     * and it must not pay a single read for the position being set to the body.
     */
    @Test fun `a pass with nothing to read touches nothing`() = runBlocking {
        val server = FakeServer(previews = { "opening" })
        val gathered = server.gather(NotificationContent.BODY_PREVIEW, ids(3), sources = emptyMap())
        assertEquals(emptyMap<String, String>(), gathered)
        assertEquals(emptyList<Long>(), server.fetched)
        assertEquals("the folder numbering must not be read for a pass with nothing to read", 0, server.numberingReads)
    }

    // ---- the pre-pass: the clock ---------------------------------------------------------

    /**
     * FOUR SECONDS FOR THE WHOLE PRE-PASS, not for each message: each read is given what is
     * LEFT, and when nothing is left the pass posts with what it has. Mail that has already
     * arrived is waiting behind this.
     */
    @Test fun `the four seconds are the whole pre-pass, and each read gets what is left`() = runBlocking {
        val server = FakeServer(previews = { "opening" }, costMs = 1_500)
        server.gather(NotificationContent.BODY_PREVIEW, ids(6))
        assertEquals(
            "each read must be handed the remaining budget, and the pass must stop when it is spent",
            listOf(4_000, 2_500, 1_000),
            server.budgets,
        )
    }

    /**
     * And never a budget of zero: `budgetMs = 0` is `ImapBudget.NO_BUDGET`, i.e. `soTimeout = 0`,
     * i.e. block until the OS gives up — on a server that accepts the connection and then says
     * nothing, that is minutes, holding the account's mutex, in a background service. The exhausted
     * budget must stop the pass, not be passed on.
     */
    @Test fun `an exhausted budget stops the pass instead of being handed on`() = runBlocking {
        val server = FakeServer(previews = { "opening" }, costMs = 4_000)
        server.gather(NotificationContent.BODY_PREVIEW, ids(6))
        assertEquals("the first read spends the whole budget", listOf(4_000), server.budgets)
        assertTrue("no read may be given a budget of 0 or less", server.budgets.all { it > 0 })
    }

    /** A clock that jumps FORWARD between two reads cannot hand the next one more than the budget. */
    @Test fun `a clock that jumps cannot widen the budget`() = runBlocking {
        val server = FakeServer(previews = { "opening" }, costMs = -60_000)
        server.gather(NotificationContent.BODY_PREVIEW, ids(3))
        assertTrue("no read may be given more than the four seconds", server.budgets.all { it <= 4_000 })
    }

    /**
     * The four seconds start at the FIRST READ, not when the budget is made. A delivery pass
     * refreshes the account's folders first — seconds of network — and a budget armed before that
     * would already be spent by the time the first opening line is asked for.
     */
    @Test fun `the four seconds start at the first read, not when the budget is made`() = runBlocking {
        val server = FakeServer(previews = { "opening" })
        server.advanceClock(30_000)
        server.gather(NotificationContent.BODY_PREVIEW, ids(1))
        assertEquals(listOf(4_000), server.budgets)
    }

    // ---- the pre-pass: one account, several folders ---------------------------------------

    /**
     * THE SIX ARE THE ACCOUNT'S, NOT EACH FOLDER'S. A delivery pass diffs the inbox AND every
     * watched folder, calling the pre-pass once per folder: a count of its own per folder would
     * give an account watching three extras 4 × 6 = 24 reads on ONE radio wake-up, under that
     * account's single mutex. The second folder here inherits what the first one left.
     */
    @Test fun `the six reads are shared by the folders of one account pass`() = runBlocking {
        val server = FakeServer(previews = { "opening" })
        server.gather(NotificationContent.BODY_PREVIEW, ids(4))
        server.gather(NotificationContent.BODY_PREVIEW, ids(4, from = 11))
        assertEquals(
            "the inbox reads four, the watched folder gets the two that are left and stops",
            listOf(1L, 2L, 3L, 4L, 11L, 12L),
            server.fetched,
        )
    }

    /** And so are the four seconds: the second folder gets what the first one did not spend. */
    @Test fun `the four seconds are shared by the folders of one account pass`() = runBlocking {
        val server = FakeServer(previews = { "opening" }, costMs = 1_500)
        server.gather(NotificationContent.BODY_PREVIEW, ids(2))
        server.gather(NotificationContent.BODY_PREVIEW, ids(3, from = 11))
        assertEquals(
            "the watched folder inherits the remaining second, not a fresh four",
            listOf(4_000, 2_500, 1_000),
            server.budgets,
        )
        assertEquals(listOf(1L, 2L, 11L), server.fetched)
    }

    /** A folder reached with nothing left reads nothing at all — and asks the server nothing. */
    @Test fun `a folder reached with the budget spent reads nothing`() = runBlocking {
        val server = FakeServer(previews = { "opening" })
        server.gather(NotificationContent.BODY_PREVIEW, ids(6))
        val gathered = server.gather(NotificationContent.BODY_PREVIEW, ids(3, from = 11))
        assertEquals(emptyMap<String, String>(), gathered)
        assertEquals((1L..6L).toList(), server.fetched)
    }

    // ---- the pre-pass: failure --------------------------------------------------------

    /**
     * A failed read costs its own preview and nothing else — not the pass, not the other
     * previews, and above all not the exception that would skip the caller's baseline.
     */
    @Test fun `a read that blows up costs its own preview and no other`() = runBlocking {
        val server = FakeServer(previews = { if (it.uid == 2L) error("connection reset") else "opening ${it.uid}" })
        assertEquals(
            mapOf("m1" to "opening 1", "m3" to "opening 3"),
            server.gather(NotificationContent.BODY_PREVIEW, ids(3)),
        )
    }

    /** A cancellation is not a failure — the pass itself is gone — and must go through. */
    @Test fun `a cancelled pass is not swallowed`() {
        val server = FakeServer(previews = { throw CancellationException("push service stopping") })
        try {
            runBlocking { server.gather(NotificationContent.BODY_PREVIEW, ids(3)) }
            fail("a cancellation must propagate, not be turned into 'no preview'")
        } catch (expected: CancellationException) {
            assertEquals("push service stopping", expected.message)
        }
    }

    /**
     * THE WHOLE pre-pass stops at the first renumbering, not just the message that hit it.
     * The refused SELECT is invisible from here — `fetchPreview` swallows it — but the
     * invalidation RECORDS the new numbering as it goes, so the NEXT message would sail through
     * the check carrying a UID from the old numbering and put the opening of somebody else's mail
     * on the lock screen (Codeberg #99).
     */
    @Test fun `a renumbered folder stops the pre-pass entirely`() = runBlocking {
        val server = FakeServer(previews = { null })
        server.renumberAfterFirstRead = true
        val gathered = server.gather(NotificationContent.BODY_PREVIEW, ids(5))
        assertEquals("nothing may be read under the new numbering", listOf(1L), server.fetched)
        assertEquals(emptyMap<String, String>(), gathered)
    }

    /** A numbering that becomes unreadable stops it too: that side costs previews, not truth. */
    @Test fun `a numbering that stops being readable stops the pre-pass too`() = runBlocking {
        val server = FakeServer(previews = { "opening" })
        server.numberingFailsAfterFirstRead = true
        server.gather(NotificationContent.BODY_PREVIEW, ids(5))
        assertEquals(listOf(1L), server.fetched)
    }

    /**
     * And a numbering that cannot be read FROM THE VERY FIRST READ stops it before a single
     * fetch — the case the "after the first read" test above cannot see, because a first read that
     * succeeds gives the comparison something to differ from. With nothing to compare against,
     * every later read compares equal and the loop runs to the end: the guard reads as armed and
     * is inert.
     */
    @Test fun `a numbering unreadable from the very first read reads nothing`() = runBlocking {
        val server = FakeServer(previews = { "opening" })
        server.numberingUnreadableFromTheStart = true
        val gathered = server.gather(NotificationContent.BODY_PREVIEW, ids(5))
        assertEquals("not one message may be read without a numbering to hold it to", emptyList<Long>(), server.fetched)
        assertEquals(emptyMap<String, String>(), gathered)
    }

    /**
     * And the folder whose numbering the SERVER never states (no UIDVALIDITY at the SELECT):
     * `recorded` is null, so this comparison has nothing to hold — and `onMailbox` cannot refuse
     * anything either, since it has no expected value to check against. BOTH guards are down at
     * once, which makes this the one path by which the opening of another message could reach a
     * lock screen (Codeberg #99). It reads nothing at all.
     */
    @Test fun `a folder whose numbering the server never states reads nothing`() = runBlocking {
        val server = FakeServer(previews = { "opening" })
        server.numberingAbsent = true
        val gathered = server.gather(NotificationContent.BODY_PREVIEW, ids(5))
        assertEquals("an unverifiable numbering is not a verified one", emptyList<Long>(), server.fetched)
        assertEquals(emptyMap<String, String>(), gathered)
    }

    // ---- the order: gather first, post second ------------------------------------------

    /**
     * THE ONE THAT CAN LOSE MAIL. Every opening is gathered BEFORE the first notification is
     * posted, in ONE pre-pass — never a round trip between two posts.
     *
     * The caller advances the folder's baseline after its posting loop. A fetch inside that loop
     * puts a network failure in the middle of a half-announced batch: the baseline never moves,
     * the next pass sees every message as new again, and a re-announcement MOVES MAIL server-side
     * through `unarchiveThreadsOnReply`. The trace below is what tells "gathered first" from
     * "gathered along the way" — a per-message gather interleaves, and this test reads the order.
     */
    @Test fun `every opening is gathered before the first notification is posted`() = runBlocking {
        val trace = mutableListOf<String>()
        NotificationPreviews.postGatheredFirst(
            listOf("a", "b", "c"),
            keyOf = { it },
            gather = { trace += "gather"; mapOf("b" to "opening of b") },
            post = { message, preview -> trace += "post $message=${preview ?: "-"}" },
        )
        assertEquals(
            listOf("gather", "post a=-", "post b=opening of b", "post c=-"),
            trace,
        )
    }

    /**
     * And a gathering that blows up whole still posts every notification and comes back
     * normally, so the caller reaches the baseline it advances afterwards.
     */
    @Test fun `a gathering that fails outright still posts every notification`() = runBlocking {
        val trace = mutableListOf<String>()
        NotificationPreviews.postGatheredFirst(
            listOf("a", "b"),
            keyOf = { it },
            gather = { trace += "gather"; error("the numbering read threw") },
            post = { message, preview -> trace += "post $message=${preview ?: "-"}" },
        )
        assertEquals(listOf("gather", "post a=-", "post b=-"), trace)
    }

    /** A cancellation there is the caller going away, and takes the pass with it. */
    @Test fun `a cancelled gathering posts nothing`() {
        val trace = mutableListOf<String>()
        try {
            runBlocking {
                NotificationPreviews.postGatheredFirst(
                    listOf("a", "b"),
                    keyOf = { it },
                    gather = { throw CancellationException("gone") },
                    post = { message, _ -> trace += "post $message" },
                )
            }
            fail("a cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals(emptyList<String>(), trace)
        }
    }

    // ---- the bench --------------------------------------------------------------------

    private fun ids(count: Int, from: Int = 1) = (from until from + count).map { "m$it" }

    private fun source(uid: Long) = PreviewSource(uid, ImapTextPart("1", "text/plain", null, "utf-8"))

    /**
     * A server that answers previews, a clock that only moves when a read is paid for, and a
     * folder numbering that can change under the pass — the three things [NotificationPreviews]
     * is given from the outside.
     */
    private inner class FakeServer(
        val previews: (PreviewSource) -> String?,
        /** How long each read takes; negative moves the clock BACKWARDS (a clock correction). */
        val costMs: Long = 0,
    ) {
        val fetched = mutableListOf<Long>()
        val budgets = mutableListOf<Int>()
        var numberingReads = 0
        var renumberAfterFirstRead = false
        var numberingFailsAfterFirstRead = false
        var numberingUnreadableFromTheStart = false
        var numberingAbsent = false
        private var clock = 100_000L
        private var uidValidity = 7L

        /**
         * ONE budget for the whole of this fake account's pass: every [gather] below shares it, as
         * the folders of one delivery pass do.
         */
        val budget = NotificationPreviews.PreviewBudget { clock }

        /** Move the wall clock without paying for a read (the folder refresh, an idle stretch). */
        fun advanceClock(ms: Long) { clock += ms }

        suspend fun gather(
            content: NotificationContent,
            ids: List<String>,
            sources: Map<String, PreviewSource> = ids.associateWith { source(it.drop(1).toLong()) },
        ): Map<String, String> = NotificationPreviews.gather(
            content = content,
            ids = ids,
            sources = sources,
            budget = budget,
            numbering = numbering@{
                numberingReads++
                if (numberingUnreadableFromTheStart) error("database is locked")
                if (numberingAbsent) return@numbering null
                if (numberingFailsAfterFirstRead && fetched.isNotEmpty()) error("database is locked")
                if (renumberAfterFirstRead && fetched.isNotEmpty()) uidValidity + 1 else uidValidity
            },
            fetch = { source, budgetMs ->
                fetched += source.uid
                budgets += budgetMs
                clock += costMs
                previews(source)
            },
        )
    }
}
