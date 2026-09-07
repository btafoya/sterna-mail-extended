package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a move brings back about the SOURCE folder: which of the UIDs handed in are proved to have
 * left it — [ImapMoved.confirmedGone].
 *
 * Why it matters one layer up: a `UID MOVE` naming a UID that is already gone (the message was
 * moved or destroyed from another client since the list was drawn) is a NO-OP the server accepts
 * with a plain `OK`. A caller that counts the whole batch as moved on a non-throwing command
 * therefore reports moved mail that never moved. Nothing here decides anything about that: this
 * module states what it can prove and refuses to guess.
 *
 * The proof is a DIFFERENCE, not a reading: the same `UID SEARCH UID <set>` is sent once before
 * the move and once after it, and only a UID the source named before and no longer names after is
 * confirmed gone. The probe after, alone, answers the same "no" for the message we moved and for
 * the message that had already left — and upstairs the second reading drops the row AND its FTS
 * entry, which nothing on IMAP reindexes.
 *
 * The witness of the branch is `a bare OK and a silent probe confirm the whole chunk`: the
 * obvious reading — "no COPYUID, so nothing moved" — is FALSE, and shipping it would break moves
 * on every server that does not implement UIDPLUS.
 */
class MoveConfirmsWhatLeftTheSourceTest {

    /** Every `UID SEARCH UID …` the client sent — the identity probe, and nothing else. */
    private fun FakeImapServer.probes(): List<String> = issued().filter { it.startsWith("UID SEARCH UID ") }

    /**
     * The probes and the landing commands **in the order they went out**, destination argument
     * dropped. Written on the order and not on a count because the questions this file asks are
     * ordering questions: a probe BEFORE the move observes a source the move has not touched yet,
     * the same probe AFTER it observes what the move left — and the copy fallback's verdict must
     * come from neither.
     */
    private fun FakeImapServer.moveTraffic(): List<String> =
        issued()
            .filter { it.startsWith("UID SEARCH UID ") || it.startsWith("UID MOVE ") || it.startsWith("UID COPY ") }
            .map { it.substringBefore(" \"") }

    /**
     * The common case (Stalwart, Dovecot): a COPYUID that names the whole set. It is an
     * authoritative per-UID report, so no probe follows the move — a move of 200 messages must
     * not cost a round trip per chunk for a verdict already in hand. The observation TAKEN BEFORE
     * cannot be spared the same way (nothing knows yet what the server will state) and its answer
     * is simply never needed here.
     */
    @Test fun `a COPYUID naming the whole chunk confirms it, with no probe after the move`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("UID MOVE") -> "$tag OK [COPYUID 77 1:3 7:9] moved\r\n"
                line.startsWith("UID SEARCH") -> searchResponse(tag, listOf(1L, 2L, 3L))
                else -> ok(tag)
            }
        }.use { server ->
            val moved = server.session().use { session ->
                session.select("INBOX")
                session.move(listOf(1L, 2L, 3L), "Trash")
            }

            assertEquals(setOf(1L, 2L, 3L), moved.confirmedGone)
            assertEquals(
                "a COPYUID that covers the chunk answers by itself: nothing may follow the move",
                listOf("UID SEARCH UID 1:3", "UID MOVE 1:3"),
                server.moveTraffic(),
            )
        }
    }

    /**
     * THE WITNESS. A server that answers the `UID MOVE` with a bare `OK` (no UIDPLUS, so no
     * COPYUID) has still moved the mail. Reading the empty mapping as "nothing moved" would turn
     * a working move into a permanent failure on that whole class of servers. The two probes are
     * what settle it: the source folder is still selected, `UID SEARCH UID 1:3` named all three
     * before the move and comes back EMPTY after it — those UIDs were there, and they left.
     *
     * This is also the falsification of an over-zealous fix: requiring a `COPYUID`, or reading
     * the empty answer as "prove nothing", loses every departure here.
     */
    @Test fun `a bare OK and a silent probe confirm the whole chunk`() {
        var afterTheMove = false
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("UID MOVE") -> { afterTheMove = true; "$tag OK moved\r\n" }
                line.startsWith("UID SEARCH") ->
                    searchResponse(tag, if (afterTheMove) emptyList() else listOf(1L, 2L, 3L))
                else -> ok(tag)
            }
        }.use { server ->
            val moved = server.session().use { session ->
                session.select("INBOX")
                session.move(listOf(1L, 2L, 3L), "Trash")
            }

            assertEquals(
                "all three were in the source before the move and none is after: they left, and " +
                    "reading the absent COPYUID as 'nothing moved' would break the move here",
                setOf(1L, 2L, 3L),
                moved.confirmedGone,
            )
            assertEquals(
                listOf("UID SEARCH UID 1:3", "UID MOVE 1:3", "UID SEARCH UID 1:3"),
                server.moveTraffic(),
            )
            assertEquals("no COPYUID is still no mapping", emptyMap<Long, Long>(), moved.uids)
            assertNull(moved.destinationUidValidity)
        }
    }

    /**
     * A UID the source still names AFTER the move did not move: the server moved the other two
     * and did nothing for this one. It was there before and it is there after, so it is the one
     * thing NOT confirmed.
     */
    @Test fun `a UID that still answers the probe is not confirmed gone`() {
        var afterTheMove = false
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("UID MOVE") -> { afterTheMove = true; "$tag OK moved\r\n" }
                line.startsWith("UID SEARCH") ->
                    searchResponse(tag, if (afterTheMove) listOf(2L) else listOf(1L, 2L, 3L))
                else -> ok(tag)
            }
        }.use { server ->
            val moved = server.session().use { session ->
                session.select("INBOX")
                session.move(listOf(1L, 2L, 3L), "Trash")
            }

            assertEquals(
                "2 answered from the source folder after the move, so 2 did not move — the other " +
                    "two were named before and are not named after",
                setOf(1L, 3L),
                moved.confirmedGone,
            )
            assertEquals(
                listOf("UID SEARCH UID 1:3", "UID MOVE 1:3", "UID SEARCH UID 1:3"),
                server.moveTraffic(),
            )
        }
    }

    /**
     * THE INVARIANT OF THIS VOLET. UID 1 had ALREADY left the source BEFORE the `UID MOVE` went
     * out — another client moved or destroyed it since the list was drawn. The move is a no-op for
     * it and the server answers a bare `OK`; the probe sent AFTER cannot tell "left by our move"
     * from "left before it", because 1 fails to answer either way. Only an observation made BEFORE
     * the move can, and it does not name 1.
     *
     * One storey up a wrongly confirmed departure is not a stale row: the line is dropped AND its
     * FTS entry destroyed, and nothing reindexes it on IMAP. The message becomes unfindable
     * offline, for good.
     */
    @Test fun `a UID already gone before the move is not confirmed gone`() {
        var afterTheMove = false
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("UID MOVE") -> { afterTheMove = true; "$tag OK moved\r\n" }
                // Before the move 1 is already absent; after it, nothing of the set is left.
                line.startsWith("UID SEARCH") ->
                    searchResponse(tag, if (afterTheMove) emptyList() else listOf(2L, 3L))
                else -> ok(tag)
            }
        }.use { server ->
            val report = server.session().use { session ->
                session.select("INBOX")
                session.move(listOf(1L, 2L, 3L), "Trash")
            }

            assertEquals(
                "1 was not in the source before the move either, so its absence afterwards proves " +
                    "nothing: only 2 and 3 are proved to have left",
                setOf(2L, 3L),
                report.confirmedGone,
            )
            assertEquals(
                "the same command, once each side of the move: that difference is the verdict",
                listOf("UID SEARCH UID 1:3", "UID MOVE 1:3", "UID SEARCH UID 1:3"),
                server.moveTraffic(),
            )
        }
    }

    /**
     * A COPYUID that names only part of the chunk does not answer for the rest, so the probe goes
     * out — and its verdict is completed by the COPYUID keys, which are a statement of the server
     * about those very messages. The fixture is deliberately CONTRADICTORY (the probe after still
     * lists 1, which COPYUID says was copied out) so that the union is the only reading that
     * passes: with an intersection, or with the probes alone, 1 would drop out.
     */
    @Test fun `a partial COPYUID probes, and its keys complete the probe's verdict`() {
        var afterTheMove = false
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("UID MOVE") -> { afterTheMove = true; "$tag OK [COPYUID 77 1,2 7,8] moved\r\n" }
                line.startsWith("UID SEARCH") ->
                    searchResponse(tag, if (afterTheMove) listOf(1L, 3L) else listOf(1L, 2L, 3L))
                else -> ok(tag)
            }
        }.use { server ->
            val moved = server.session().use { session ->
                session.select("INBOX")
                session.move(listOf(1L, 2L, 3L), "Trash")
            }

            assertEquals(
                listOf("UID SEARCH UID 1:3", "UID MOVE 1:3", "UID SEARCH UID 1:3"),
                server.moveTraffic(),
            )
            assertEquals(
                "2 was there before and is not there after; 1 is the server's own word against " +
                    "its own probe, and the word is kept",
                setOf(1L, 2L),
                moved.confirmedGone,
            )
            assertEquals("the mapping is untouched by any of this", mapOf(1L to 7L, 2L to 8L), moved.uids)
            assertEquals(77L, moved.destinationUidValidity)
        }
    }

    /**
     * A refused probe AFTER the move leaves the question unanswered — and that is all it does.
     * The MOVE itself succeeded; failing the call here would have the caller retry a move that
     * already happened. So: nothing confirmed, nothing thrown, the rest of the report as it was.
     * The observation taken before is intact and still proves nothing on its own: presence is not
     * departure.
     */
    @Test fun `a refused probe confirms nothing and does not fail the move`() {
        var afterTheMove = false
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("UID MOVE") -> { afterTheMove = true; "$tag OK moved\r\n" }
                line.startsWith("UID SEARCH") ->
                    if (afterTheMove) "$tag NO cannot search now\r\n" else searchResponse(tag, listOf(1L, 2L, 3L))
                else -> ok(tag)
            }
        }.use { server ->
            val moved = server.session().use { session ->
                session.select("INBOX")
                session.move(listOf(1L, 2L, 3L), "Trash")
            }

            assertEquals(emptySet<Long>(), moved.confirmedGone)
            assertEquals(
                listOf("UID SEARCH UID 1:3", "UID MOVE 1:3", "UID SEARCH UID 1:3"),
                server.moveTraffic(),
            )
        }
    }

    /**
     * And a refused probe drops the COPYUID keys with it: half a verdict about a chunk is not a
     * verdict. Upstairs, "confirmed gone" is what licenses forgetting a row, so under-confirming
     * costs a stale line the next sync repairs, and over-confirming costs the line itself.
     */
    @Test fun `a refused probe confirms nothing even where COPYUID named some`() {
        var afterTheMove = false
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("UID MOVE") -> { afterTheMove = true; "$tag OK [COPYUID 77 1,2 7,8] moved\r\n" }
                line.startsWith("UID SEARCH") ->
                    if (afterTheMove) "$tag NO cannot search now\r\n" else searchResponse(tag, listOf(1L, 2L, 3L))
                else -> ok(tag)
            }
        }.use { server ->
            val moved = server.session().use { session ->
                session.select("INBOX")
                session.move(listOf(1L, 2L, 3L), "Trash")
            }

            assertEquals(emptySet<Long>(), moved.confirmedGone)
            assertEquals("the move happened and its mapping stands", mapOf(1L to 7L, 2L to 8L), moved.uids)
            assertEquals(77L, moved.destinationUidValidity)
        }
    }

    /**
     * The whole selection had ALREADY left the folder — every one of these UIDs was moved or
     * destroyed from another client since the list was drawn. The `UID MOVE` is a no-op for all
     * three and the server answers a bare `OK`; the probe before names nothing, the probe after
     * names nothing either. Nothing is confirmed.
     *
     * This is the maximal form of the defect the volet closes, and it is the OTHER way the
     * observation before can prove no presence: an answer that never came ([a probe refused
     * before the move…]) and an answer that came empty are different events, and only the second
     * one looks like a normal reading. Reading "the source names none of them" as "they were all
     * there" would credit the whole batch and destroy three rows AND their FTS entries for mail
     * the server never touched — which is precisely the report this branch exists for.
     */
    @Test fun `a source that names none of the set before the move confirms nothing`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("UID MOVE") -> "$tag OK moved\r\n"
                // Empty BOTH times, and answered rather than refused: they had all already left.
                line.startsWith("UID SEARCH") -> searchResponse(tag, emptyList())
                else -> ok(tag)
            }
        }.use { server ->
            val moved = server.session().use { session ->
                session.select("INBOX")
                session.move(listOf(1L, 2L, 3L), "Trash")
            }

            assertEquals(
                "none of them was in the source before the move, so none of their absences " +
                    "afterwards proves anything — an empty answer is a reading, not a presence",
                emptySet<Long>(),
                moved.confirmedGone,
            )
            assertEquals(
                listOf("UID SEARCH UID 1:3", "UID MOVE 1:3", "UID SEARCH UID 1:3"),
                server.moveTraffic(),
            )
        }
    }

    /**
     * And the symmetric refusal, which is the one that can restore the defect: the probe taken
     * BEFORE the move is refused, the one taken after answers perfectly. Nothing is confirmed.
     *
     * A refusal there proves NO presence, and a presence not observed can never become a proved
     * departure — otherwise "the source would not answer me" would read as "everything I handed
     * in was there", which is exactly what the whole batch used to be credited with. The probe
     * after is impeccable here (the folder names nothing of the set any more) and it still must
     * not carry the verdict on its own: that is the entire point of the volet.
     *
     * Under-confirming costs three stale rows the next sync repairs. Over-confirming costs the
     * rows AND their FTS entries, which nothing reindexes on IMAP.
     */
    @Test fun `a probe refused before the move confirms nothing, however clean the one after`() {
        var afterTheMove = false
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("UID MOVE") -> { afterTheMove = true; "$tag OK moved\r\n" }
                line.startsWith("UID SEARCH") ->
                    if (afterTheMove) searchResponse(tag, emptyList()) else "$tag NO cannot search now\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            val moved = server.session().use { session ->
                session.select("INBOX")
                session.move(listOf(1L, 2L, 3L), "Trash")
            }

            assertEquals(
                "the observation before the move was refused, so no presence was ever proved and " +
                    "no departure can be: an empty answer after is not a verdict by itself",
                emptySet<Long>(),
                moved.confirmedGone,
            )
            assertEquals(
                "both probes still went out, and the move between them",
                listOf("UID SEARCH UID 1:3", "UID MOVE 1:3", "UID SEARCH UID 1:3"),
                server.moveTraffic(),
            )
        }
    }

    /**
     * The copy fallback must NEVER be probed AFTER its `UID COPY`. It leaves the originals in
     * the source folder, flagged `\Deleted`, purged only after the loop — so a probe there would
     * answer "still there" for mail that has landed, i.e. a systematically false failure on every
     * server without the MOVE extension. Its own COPYUID is its per-UID report and its only one,
     * and the observation taken before the refused `UID MOVE` is dropped unread.
     *
     * The assertion is on the ORDER of the commands, not on their number: what must not exist is
     * a `UID SEARCH UID` AFTER the `UID COPY`.
     */
    @Test fun `the copy fallback is never probed after the copy and answers with its own COPYUID`() {
        var afterTheMove = false
        FakeImapServer { tag, line ->
            when {
                line.startsWith("CAPABILITY") ->
                    "* CAPABILITY IMAP4rev1 LITERAL+ UIDPLUS\r\n$tag OK capability completed\r\n"
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("UID MOVE") -> { afterTheMove = true; "$tag BAD unknown command\r\n" }
                line.startsWith("UID COPY") -> "$tag OK [COPYUID 77 1:3 7:9] copy completed\r\n"
                line.startsWith("UID SEARCH") ->
                    searchResponse(tag, if (afterTheMove) emptyList() else listOf(1L, 2L, 3L))
                else -> ok(tag)
            }
        }.use { server ->
            val moved = server.session().use { session ->
                session.select("INBOX")
                session.move(listOf(1L, 2L, 3L), "Trash")
            }

            assertTrue("the fallback did not run: ${server.issued()}", server.issued().any { it.startsWith("UID COPY") })
            assertEquals(
                "probing a copy fallback is a false negative: nothing may follow the UID COPY",
                listOf("UID SEARCH UID 1:3", "UID MOVE 1:3", "UID COPY 1:3"),
                server.moveTraffic(),
            )
            assertEquals(setOf(1L, 2L, 3L), moved.confirmedGone)
        }
    }

    /**
     * And where the fallback's COPYUID says nothing, nothing is confirmed — still without a probe
     * after the copy. The fixture would REWARD one: the probe answers "empty" once the `UID MOVE`
     * has been refused, so a fallback that read it would confirm all three, and those messages
     * are still sitting in the source folder.
     */
    @Test fun `a copy fallback with no COPYUID confirms nothing, and still does not probe`() {
        var afterTheMove = false
        FakeImapServer { tag, line ->
            when {
                line.startsWith("CAPABILITY") ->
                    "* CAPABILITY IMAP4rev1 LITERAL+ UIDPLUS\r\n$tag OK capability completed\r\n"
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("UID MOVE") -> { afterTheMove = true; "$tag BAD unknown command\r\n" }
                line.startsWith("UID COPY") -> "$tag OK copy completed\r\n"
                line.startsWith("UID SEARCH") ->
                    searchResponse(tag, if (afterTheMove) emptyList() else listOf(1L, 2L, 3L))
                else -> ok(tag)
            }
        }.use { server ->
            val moved = server.session().use { session ->
                session.select("INBOX")
                session.move(listOf(1L, 2L, 3L), "Trash")
            }

            assertEquals(
                listOf("UID SEARCH UID 1:3", "UID MOVE 1:3", "UID COPY 1:3"),
                server.moveTraffic(),
            )
            assertEquals(emptySet<Long>(), moved.confirmedGone)
        }
    }

    /**
     * A move too big for one command is several chunks, and each chunk gets its own verdict by
     * its own route — the first answers with a covering COPYUID and is never probed after, the
     * second answers bare and is. Each has its OWN observation before, naming its own set. The
     * union of the two verdicts is the report.
     *
     * Written against a mutation, not against a shipped defect: `gone += …` guarded by
     * `if (gone.isEmpty())` passes every single-chunk test in this file, and would silently drop
     * 300 proved departures out of a 500-message archive. Same for an observation hoisted out of
     * the loop: it would name the first chunk's set and confirm nothing of the second.
     */
    @Test fun `every chunk of a long move contributes its own verdict`() {
        val all = (1L..250L).toList()
        var movesIssued = 0
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = all.size)
                // The first chunk: a COPYUID covering it whole, so no probe after it.
                line.startsWith("UID MOVE 1:200") -> { movesIssued++; "$tag OK [COPYUID 77 1:200 1001:1200] moved\r\n" }
                // The second: nothing stated, so the probes are what answer.
                line.startsWith("UID MOVE 201:250") -> { movesIssued++; "$tag OK moved\r\n" }
                // Before either move, every UID of the chunk asked about is in the source.
                line.startsWith("UID SEARCH UID 1:200") -> searchResponse(tag, (1L..200L).toList())
                // And after the second move, 225 is the one still there.
                line.startsWith("UID SEARCH UID 201:250") ->
                    searchResponse(tag, if (movesIssued == 2) listOf(225L) else (201L..250L).toList())
                else -> ok(tag)
            }
        }.use { server ->
            val moved = server.session().use { session ->
                session.select("INBOX")
                session.move(all, "Archive")
            }

            assertEquals(
                "each chunk observes its OWN set before its own move, and only the second chunk " +
                    "needs an answer after it",
                listOf(
                    "UID SEARCH UID 1:200",
                    "UID MOVE 1:200",
                    "UID SEARCH UID 201:250",
                    "UID MOVE 201:250",
                    "UID SEARCH UID 201:250",
                ),
                server.moveTraffic(),
            )
            assertEquals(
                "the second chunk's 49 proved departures must not be lost behind the first " +
                    "chunk's verdict",
                (all - 225L).toSet(),
                moved.confirmedGone,
            )
        }
    }

    /**
     * A probe's answer must be read WHOLE — BOTH of them. A server may split a long `SEARCH`
     * result over several untagged lines, and may interleave untagged traffic of its own
     * (`* n EXPUNGE` for what another client just removed); this fixture does both, on each side
     * of the move. Anything dropped from the answer AFTER falls out of the subtraction and is
     * reported as PROVED GONE: the read error turns into the destructive answer, which is the very
     * defect this field exists to close, one storey up. 11 is the only UID that actually left.
     */
    @Test fun `a split SEARCH answer with untagged noise is read whole`() {
        val all = (1L..12L).toList()
        var afterTheMove = false
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = all.size)
                line.startsWith("UID MOVE") -> { afterTheMove = true; "$tag OK moved\r\n" }
                line.startsWith("UID SEARCH") -> {
                    // Before: all twelve are there. After: everything but 11 is still there.
                    val listed = if (afterTheMove) {
                        "* SEARCH 1 2 3 4 5 6 7 8 9 10\r\n* SEARCH 12\r\n"
                    } else {
                        "* SEARCH 1 2 3 4 5 6 7\r\n* SEARCH 8 9 10 11 12\r\n"
                    }
                    (3..6).joinToString("") { "* $it EXPUNGE\r\n" } + listed + "$tag OK search completed\r\n"
                }
                else -> ok(tag)
            }
        }.use { server ->
            val moved = server.session().use { session ->
                session.select("INBOX")
                session.move(all, "Archive")
            }

            assertEquals(listOf("UID SEARCH UID 1:12", "UID SEARCH UID 1:12"), server.probes())
            assertEquals(
                "everything the SEARCH lists is still in the source, however the server chose to " +
                    "lay it out; a UID lost to a reading limit would be reported as gone, and its " +
                    "row destroyed while the mail is still there",
                setOf(11L),
                moved.confirmedGone,
            )
        }
    }

    /**
     * And the same truncation on the observation taken BEFORE falls the other way, which is
     * why the two readings can share one command with no cap: the server names only 1 and 2 while
     * 3 is in fact there too, and after the move nothing answers at all. 3 is a presence never
     * observed, so it is not confirmed — a stale row the next sync repairs, instead of a row and
     * an FTS entry destroyed.
     */
    @Test fun `a truncated answer before the move under-confirms rather than over-confirms`() {
        var afterTheMove = false
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                line.startsWith("UID MOVE") -> { afterTheMove = true; "$tag OK moved\r\n" }
                line.startsWith("UID SEARCH") ->
                    searchResponse(tag, if (afterTheMove) emptyList() else listOf(1L, 2L))
                else -> ok(tag)
            }
        }.use { server ->
            val moved = server.session().use { session ->
                session.select("INBOX")
                session.move(listOf(1L, 2L, 3L), "Trash")
            }

            assertEquals(
                "a UID missing from the answer before is a presence not observed, and an " +
                    "unobserved presence confirms nothing",
                setOf(1L, 2L),
                moved.confirmedGone,
            )
        }
    }
}
