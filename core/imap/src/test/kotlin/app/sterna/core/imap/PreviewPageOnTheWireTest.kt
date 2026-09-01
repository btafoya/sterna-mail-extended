package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHAT THE GROUPED PREVIEW FETCH PUTS ON THE WIRE, and how its answer is read — the page twin of
 * [BodyPeekOnTheWireTest], whose rules this file keeps.
 *
 * ⛔ `BODY.PEEK[…]`, never `BODY[…]`. A bare `BODY` fetch sets `\Seen` on the server (RFC 3501
 * §6.4.5) for EVERY uid in the set. On this path the caller reads a page of a folder as it syncs,
 * so the slip would not mark a message read, it would mark the mailbox read — on every device the
 * account is open on, with no command that undoes it.
 *
 * ⛔ Whole lines, compared with assertEquals — never `contains("PEEK")`. A containment check is
 * blind to every mutation that makes the line LONGER: a second item added to the fetch, a
 * `<0.8192>` grown to `<0.20000000>`, a `BODY.PEEK[]` fetching the whole message beside the
 * section. The list asserted is the WHOLE conversation, so a round trip nobody asked for shows up
 * as an extra line instead of passing unnoticed.
 *
 * ⭐ And the thing this file exists for: the answer is filed BY UID. A grouped fetch comes back as
 * one untagged line per message, numbered by SEQUENCE, in whatever order the server likes — so a
 * reader that keeps the first body (which is what the single-message [ImapSession.fetchSection]
 * path does, by design) or that zips the answer against the request would hand one person's mail
 * to another person's row.
 */
class PreviewPageOnTheWireTest {

    /**
     * A server that answers each `UID FETCH <set>` with one `FETCH` line per requested uid for
     * which [body] gives something, carrying it as `BODY[1]<0>`.
     *
     * ⚠ The sequence number is the message's POSITION IN THE ANSWER (`* 1`, `* 2`, …) and
     * deliberately not its uid: that is what a real server sends, and it is the number a reader
     * that indexed by anything but the `UID` item would latch onto.
     */
    private fun previewResponse(tag: String, line: String, body: (Long) -> String?): String {
        val requested = expandUidSet(line.removePrefix("UID FETCH ").substringBefore(' '))
        val lines = requested.withIndex().mapNotNull { (i, uid) ->
            body(uid)?.let { "* ${i + 1} FETCH (UID $uid BODY[1]<0> {${it.length}}\r\n$it)\r\n" }
        }
        return lines.joinToString("") + "$tag OK fetched\r\n"
    }

    /** Run [act] against a server answering every UID FETCH with [answer]; give back what the
     *  session actually said, LOGIN excluded. */
    private fun conversation(answer: (tag: String, line: String) -> String, act: (ImapSession) -> Unit): List<String> {
        val server = FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 1)
                line.startsWith("UID FETCH") -> answer(tag, line)
                else -> ok(tag)
            }
        }
        return server.use {
            it.session().use { session ->
                session.select("INBOX")
                act(session)
            }
            it.issued()
        }
    }

    /** The bodies a run collected, and the lines it put on the wire. */
    private class Page {
        var fetched: Map<Long, String> = emptyMap()
        var issued: List<String> = emptyList()
    }

    private fun page(uids: List<Long>, body: (Long) -> String?): Page {
        val out = Page()
        out.issued = conversation({ tag, line -> previewResponse(tag, line, body) }) { session ->
            out.fetched = session.fetchSectionPartials(uids, "1", IMAP_PREVIEW_FETCH_BYTES)
        }
        return out
    }

    @Test fun `a page of previews peeks, asks for 8 KiB of each, and names one compressed set`() {
        // Handed over in no order at all, as a caller holding a folder's uids would: what leaves is
        // one command over the compressed set (`compressUidSet`), not four.
        val run = page(listOf(10L, 7L, 11L, 9L)) { uid -> "body $uid" }

        assertEquals(
            // Every session opens by asking whether the server knows the RFC 2971 ID
            // command (#173); this fixture answers without it, so nothing is named.
            listOf("CAPABILITY", "SELECT \"INBOX\"", "UID FETCH 7,9:11 (BODY.PEEK[1]<0.8192>)", "LOGOUT"),
            run.issued,
        )
    }

    /**
     * ⭐ THE POINT OF THE WHOLE PANEL: three answers, three bodies, three rows — each under ITS OWN
     * uid. The reader shared by the single-message fetches keeps the FIRST body of the answer and
     * drops the rest ([ImapSession] `bodyItem`), which here would show message 7's opening on all
     * three rows; indexing by the answer's position would show them shuffled, since the sequence
     * numbers this server sends are 1, 2, 3 and the uids are 7, 9, 11.
     */
    @Test fun `each body comes back under its own uid`() {
        val bodies = mapOf(7L to "seven speaks", 9L to "nine speaks", 11L to "eleven speaks")
        val run = page(listOf(7L, 9L, 11L)) { uid -> bodies[uid] }

        assertEquals(bodies, run.fetched)
    }

    /**
     * ⛔ One `UID FETCH` per BATCH, not per page asked for. The answer to a preview fetch carries up
     * to 8 KiB per message and all of it is parsed and held before a body is read out of it: two
     * hundred messages in one command is ~1.6 MB on the heap of a foreground service, which is the
     * shape `walkFolder` paginates to avoid. The batch is pinned to its VALUE here, not read back
     * from the constant — a test that recomputed the split from the constant would pass whatever
     * the constant became.
     */
    @Test fun `more messages than one batch go out as several fetches, and all of them come back`() {
        // Newest-first, the order a folder walk holds its uids in: the batches must still be
        // contiguous ranges, or the first command would name the oldest messages of the lot.
        val run = page((25L downTo 1L).toList()) { uid -> "body $uid" }

        assertEquals(
            listOf(
                "CAPABILITY",
                "SELECT \"INBOX\"",
                "UID FETCH 1:20 (BODY.PEEK[1]<0.8192>)",
                "UID FETCH 21:25 (BODY.PEEK[1]<0.8192>)",
                "LOGOUT",
            ),
            run.issued,
        )
        assertEquals((1L..25L).toList(), run.fetched.keys.sorted())
        assertEquals((1L..25L).associateWith { "body $it" }, run.fetched)
    }

    /** The reason the batch is 20 and not 200, stated as the property that must hold if anyone
     *  moves it: what one answer may put on the heap stays in the hundreds of kilobytes. */
    @Test fun `one batch cannot ask for more than a few hundred kilobytes of bodies`() {
        assertTrue(
            "IMAP_PREVIEW_FETCH_CHUNK ($IMAP_PREVIEW_FETCH_CHUNK) × IMAP_PREVIEW_FETCH_BYTES " +
                "($IMAP_PREVIEW_FETCH_BYTES) = ${IMAP_PREVIEW_FETCH_CHUNK * IMAP_PREVIEW_FETCH_BYTES} " +
                "octets in one answer",
            IMAP_PREVIEW_FETCH_CHUNK * IMAP_PREVIEW_FETCH_BYTES <= 256 * 1024,
        )
    }

    /**
     * ⛔ "At most 8 KiB" must be TRUE of EVERY body, not merely requested, and not only of the
     * first: `<0.8192>` is a request, a literal is read up to 32 MiB, and the item is taken by
     * prefix — so a server that ignores the partial would otherwise have twenty whole messages
     * decoded and held before anything threw them away.
     */
    @Test fun `every over-long body is cut to the bound the client asked for`() {
        val huge = "A".repeat(20_000)
        val run = page(listOf(7L, 9L)) { huge }

        assertEquals(listOf(7L, 9L), run.fetched.keys.sorted())
        assertEquals(IMAP_PREVIEW_FETCH_BYTES, run.fetched[7L]!!.length)
        assertEquals(IMAP_PREVIEW_FETCH_BYTES, run.fetched[9L]!!.length)
        assertEquals(huge.take(IMAP_PREVIEW_FETCH_BYTES), run.fetched[9L])
    }

    /** A part shorter than the bound comes back whole: what came back is what there is. */
    @Test fun `a body shorter than the bound comes back whole`() {
        val run = page(listOf(7L, 9L)) { uid -> if (uid == 7L) "hi" else "there" }

        assertEquals(mapOf(7L to "hi", 9L to "there"), run.fetched)
    }

    /** ⛔ Nothing to ask about puts NOTHING on the wire — not an empty set, which would go out as
     *  `UID FETCH  (…)` and be answered BAD. */
    @Test fun `nothing to ask about sends no fetch at all`() {
        val run = page(emptyList()) { "never" }

        // Every session opens by asking whether the server knows the RFC 2971 ID
        // command (#173); this fixture answers without it, so nothing is named.
        assertEquals(listOf("CAPABILITY", "SELECT \"INBOX\"", "LOGOUT"), run.issued)
        assertEquals(emptyMap<Long, String>(), run.fetched)
    }

    /**
     * ⛔ A message the server answers about WITHOUT a body item makes no entry — and above all not
     * the string "UID". The item list opens with `UID <n>`, so a reader that took index 0 when it
     * found no body would file that atom as the message; on this path it is what a mail list row
     * would show as somebody's first line. An absent uid is an ordinary answer, and the entries
     * around it must survive it.
     */
    @Test fun `a fetch line with no body item makes no entry, and never the word UID`() {
        var fetched: Map<Long, String> = emptyMap()
        conversation({ tag, _ ->
            "* 1 FETCH (UID 7 FLAGS (\\Seen))\r\n" +
                "* 2 FETCH (UID 9 BODY[1]<0> {4}\r\nnine)\r\n" +
                "$tag OK fetched\r\n"
        }) { session ->
            fetched = session.fetchSectionPartials(listOf(7L, 9L), "1", IMAP_PREVIEW_FETCH_BYTES)
        }

        assertEquals(mapOf(9L to "nine"), fetched)
        assertFalse("uid 7 answered no body, so it must not be a key", fetched.containsKey(7L))
        assertFalse("the atom UID was filed as a message", fetched.containsValue("UID"))
    }

    /** An empty body is an absence too: never a "" sitting in the map pretending to be mail. */
    @Test fun `an empty body is not an entry`() {
        val run = page(listOf(7L, 9L)) { uid -> if (uid == 7L) "" else "nine" }

        assertEquals(mapOf(9L to "nine"), run.fetched)
    }
}
