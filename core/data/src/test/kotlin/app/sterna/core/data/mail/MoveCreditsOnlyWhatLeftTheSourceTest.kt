package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A batch IMAP move must credit — and un-index — only the messages the session can PROVE left the
 * source folder.
 *
 * The defect: `UID MOVE` naming a uid that is already gone (moved or destroyed from another client
 * since the list was drawn) is a NO-OP the server accepts with a plain `OK`. `imapMoveGroup` read
 * "the command did not throw" as "the whole batch moved", so the row went AND its `email_fts` row
 * went with it — and on IMAP nothing ever re-indexes a message the cache no longer holds
 * (`EmailFtsDao.seedFromEmails` only rewrites rows whose message IS cached, and there is no crawl
 * to find it again). Offline search for that message was gone for good, from a message the server
 * never touched.
 *
 * ⭐ The decision is [imapMoveOutcome], a pure function, and the cases below RUN it. That is the
 * point: `MailRepository` needs Room, a `Context` and an `Application`, so a split left inline in
 * `imapMoveGroup` is a split no test in this module can execute — and a rule stated only in prose
 * beside the code is a rule a mutation walks straight through (the same reason written at
 * `UidValidity.imapDestroyRoutes`).
 *
 * The wiring half is a SOURCE LINT for that same reason, and it pins WHOLE LINES compared as a
 * LIST, never `in` / `contains`: a substring rule is blind to anything appended to what it matched,
 * and "the same call with the other argument" is exactly the shape of the mutation that matters —
 * `deleteFromCacheAndIndex(credentials.id, uidToId.values)` is the shipped defect entire.
 */
class MoveCreditsOnlyWhatLeftTheSourceTest {

    // ---- the decision, executed ---------------------------------------------------------------

    private val batch = linkedMapOf(11L to "acc|INBOX|11", 12L to "acc|INBOX|12", 13L to "acc|INBOX|13")

    /**
     * ⛔ The case the defect is made of. An empty `confirmedGone` is "the server proved nothing",
     * NEVER "nothing moved" — and the two are answered the same way here because the irreversible
     * direction decides: a row kept for a message that DID move is put right by the next sync, an
     * index row taken from a message that did NOT move is put right by nothing.
     */
    @Test fun `a move that proved nothing credits nothing`() {
        val outcome = imapMoveOutcome(batch, emptySet())

        assertEquals(
            "an OK with no proof credited the whole batch: those ids get un-indexed, and nothing " +
                "on IMAP ever re-indexes a message the cache no longer holds",
            emptyList<String>(), outcome.gone,
        )
        assertEquals(
            "the unproven ids must FAIL — that is what makes the caller re-query, so a message " +
                "that never moved comes back instead of vanishing from a list it never left",
            listOf("acc|INBOX|11", "acc|INBOX|12", "acc|INBOX|13"), outcome.unproven,
        )
    }

    /** The ordinary case: the server named every source uid it emptied. */
    @Test fun `a move that proved every uid credits every id and fails none`() {
        val outcome = imapMoveOutcome(batch, setOf(11L, 12L, 13L))

        assertEquals(listOf("acc|INBOX|11", "acc|INBOX|12", "acc|INBOX|13"), outcome.gone)
        assertEquals(emptyList<String>(), outcome.unproven)
    }

    /**
     * The mixed batch — one stale uid among live ones, which is how the defect actually reaches a
     * user: a selection drawn before another client moved one of its messages.
     *
     * ⚠ And the ARRIVAL ORDER is kept on both sides: these lists are what the caller credits, what
     * it fails, and what its own caller re-queries and shows.
     */
    @Test fun `a partly proved move splits exactly, keeping arrival order`() {
        val wide = linkedMapOf(
            9L to "id-a", 4L to "id-b", 7L to "id-c", 2L to "id-d", 5L to "id-e",
        )

        val outcome = imapMoveOutcome(wide, setOf(2L, 9L, 7L))

        assertEquals(listOf("id-a", "id-c", "id-d"), outcome.gone)
        assertEquals(listOf("id-b", "id-e"), outcome.unproven)
    }

    /**
     * A proved uid this batch never handed in invents no id. One gesture can be cut into several
     * batches (one per source folder, one per numbering), and the answer is about the ids that were
     * HANDED IN — an id conjured from a uid belonging to another batch would be credited, and
     * un-indexed, by a call that never carried it.
     */
    @Test fun `a proof about a uid this batch never sent invents no id`() {
        val outcome = imapMoveOutcome(mapOf(12L to "acc|INBOX|12"), setOf(11L, 12L, 99L))

        assertEquals(listOf("acc|INBOX|12"), outcome.gone)
        assertEquals(emptyList<String>(), outcome.unproven)
    }

    /** Nothing handed in, nothing answered — no uid of the proof leaks into either list. */
    @Test fun `an empty batch answers empty on both sides`() {
        val outcome = imapMoveOutcome(emptyMap(), setOf(11L, 12L))

        assertEquals(emptyList<String>(), outcome.gone)
        assertEquals(emptyList<String>(), outcome.unproven)
    }

    // ---- the wiring: which ids each half of the success branch is HANDED ----------------------

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    private fun codeLinesOf(body: String): List<String> =
        body.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    private fun codeLinesNaming(body: String, needle: String): List<String> =
        codeLinesOf(body).filter { needle in it }

    /**
     * ⛔ THE LINE THE WHOLE FIX IS: the un-indexing takes the PROVED ids and nothing else.
     *
     * The shipped defect was `deleteFromCacheAndIndex(credentials.id, uidToId.values)` — the whole
     * batch, on an `OK` that may have moved none of it. No test in this module can watch the call
     * (the repository is not constructible here), so the ARGUMENT is pinned as a whole line: the
     * mutation that matters is not "the call disappeared", it is "the call kept the old argument".
     *
     * ⛔ And `evictAlreadyThere` is NOT the answer for the other half: that is "this page is no
     * longer cached" for a message that stayed put by the user's own doing. An unproven id's rows
     * must not be touched AT ALL — nothing is known about it, so nothing may be done to it.
     */
    @Test fun `the batch move un-indexes only the ids the server proved gone`() {
        val body = bodyOf("imapMoveGroup")
        assertEquals(
            "imapMoveGroup must un-index the PROVED ids only. Handing it the whole batch " +
                "un-indexes messages a no-op `UID MOVE` never touched, and nothing on IMAP ever " +
                "re-indexes a message the cache no longer holds. Body was:\n$body",
            listOf("deleteFromCacheAndIndex(credentials.id, outcome.gone)"),
            codeLinesNaming(body, "deleteFromCacheAndIndex("),
        )
        assertEquals(
            "and the drawer-count nudge must count the same ids — the filter is what keeps the " +
                "two in step (INV-COUNT). Body was:\n$body",
            listOf("adjustCountsForRemoval(rows.filter { it.id in outcome.gone }, dest)"),
            codeLinesNaming(body, "adjustCountsForRemoval("),
        )
        assertEquals(
            "no id of a batch move may be evicted as a no-op: an unproven id is UNKNOWN, not " +
                "\"it stayed\", and its rows must be left exactly as they are. Body was:\n$body",
            emptyList<String>(),
            codeLinesNaming(body, "evictAlreadyThere("),
        )
    }

    /**
     * The split itself, and where it is READ FROM: `ImapMoved.confirmedGone`, the source-side
     * answer of the very move that just ran. Not `landed.uids` — an empty `uids` is ambiguous
     * ("this server states no destination UIDs at all" and "this message did not move" are the
     * same `emptyMap()`), which is why the third field exists.
     */
    @Test fun `the batch move asks the pure split, and asks it what the move reported`() {
        val body = bodyOf("imapMoveGroup")
        assertEquals(
            "imapMoveGroup must take its split from imapMoveOutcome, over the uids the move " +
                "itself reported gone from the source. Body was:\n$body",
            listOf("val outcome = imapMoveOutcome(uidToId, landed.confirmedGone)"),
            codeLinesNaming(body, "imapMoveOutcome("),
        )
    }

    /**
     * ⛔ And the ORDER: the verdict is computed BEFORE anything is dropped. Kotlin enforces it as
     * long as the un-indexing reads `outcome`, which is the whole reason the split is a `val` read
     * by both halves rather than two expressions; this states it so a rearrangement that computes
     * the verdict after the delete cannot pass silently.
     */
    @Test fun `the verdict is reached before any row is dropped`() {
        val lines = codeLinesOf(bodyOf("imapMoveGroup"))
        val split = lines.indexOfFirst { "imapMoveOutcome(" in it }
        val dropped = lines.indexOfFirst { "deleteFromCacheAndIndex(" in it }
        assertTrue("imapMoveGroup no longer computes a split at all", split >= 0)
        assertTrue("imapMoveGroup no longer drops anything at all", dropped >= 0)
        assertTrue(
            "the rows are dropped before the move's verdict is known — that IS the defect. " +
                "Body was:\n${lines.joinToString("\n")}",
            split < dropped,
        )
    }

    /**
     * Every `failed +=` of the batch mover, as a closed list. Three legitimate ones, and each says
     * a different thing: an id whose uid cannot be parsed never went out, an id the move reported
     * nothing about is unknown, and a move that did not go out fails whole.
     *
     * ⛔ A fourth would be a fourth reason to fail, and `BulkResult` carries no cause: the caller
     * re-queries the lot either way.
     */
    @Test fun `everything the batch move does not credit, it fails`() {
        val body = bodyOf("imapMoveGroup")
        assertEquals(
            "imapMoveGroup must fail exactly what it does not credit: unparseable ids, ids the " +
                "move proved nothing about, and the whole group when the move did not go out. " +
                "Body was:\n$body",
            listOf(
                "failed += ids.filter { ImapMailService.uidOf(it) == null }",
                "failed += outcome.unproven",
                ".onFailure { failed += uidToId.values }",
            ),
            codeLinesNaming(body, "failed +="),
        )
    }
}
