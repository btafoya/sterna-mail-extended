package app.sterna.core.imap

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The two readings the server-side "Unread" view is built on (#251), on a scripted server: the
 * whole unread set of a folder, and the envelopes of named UIDs fetched by pages with no verdict on
 * movement.
 */
class UnseenOnTheWireTest {

    private val items = "(UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE BODY.PEEK[HEADER.FIELDS (REFERENCES)])"

    // -- the unread set ----------------------------------------------------------------------------

    private fun searching(answer: (tag: String) -> String) = FakeImapServer { tag, line ->
        when {
            line.startsWith("SELECT") -> selectResponse(tag, exists = 900)
            line.startsWith("UID SEARCH") -> answer(tag)
            else -> ok(tag)
        }
    }

    private fun unseen(server: FakeImapServer): List<Long> = server.session().use { session ->
        session.select("INBOX")
        session.unseenUids()
    }

    @Test fun `an answer split over several SEARCH lines is read whole, newest first`() {
        searching { tag -> "* SEARCH 12 801\r\n* SEARCH 7\r\n* SEARCH 640 15\r\n" + ok(tag) }.use { server ->
            assertEquals(listOf(801L, 640L, 15L, 12L, 7L), unseen(server))
        }
    }

    @Test fun `one command, UID SEARCH UNSEEN, and no fetch`() {
        searching { tag -> searchResponse(tag, listOf(3L, 9L)) }.use { server ->
            assertEquals(listOf(9L, 3L), unseen(server))
            assertEquals(listOf("CAPABILITY", "SELECT \"INBOX\"", "UID SEARCH UNSEEN", "LOGOUT"), server.issued())
        }
    }

    @Test fun `more unread than any window holds come back uncapped`() {
        val all = (1L..5_000L).toList()
        searching { tag -> all.chunked(1_700).joinToString("") { "* SEARCH ${it.joinToString(" ")}\r\n" } + ok(tag) }.use { server ->
            val read = unseen(server)

            assertEquals(5_000, read.size)
            assertEquals(5_000L to 1L, read.first() to read.last())
            assertEquals(read.sortedDescending(), read)
        }
    }

    @Test fun `an empty SEARCH line is no unread message`() {
        searching { tag -> "* SEARCH\r\n" + ok(tag) }.use { server ->
            assertEquals(emptyList<Long>(), unseen(server))
        }
    }

    @Test fun `no SEARCH line at all is no unread message either`() {
        searching { tag -> ok(tag) }.use { server ->
            assertEquals(emptyList<Long>(), unseen(server))
        }
    }

    @Test fun `a refused search throws`() {
        searching { tag -> "$tag NO search not permitted\r\n" }.use { server ->
            server.session().use { session ->
                session.select("INBOX")
                val refused = assertThrows(ImapException::class.java) { session.unseenUids() }
                assertEquals("NO", refused.taggedStatus)
            }
        }
    }

    // -- the envelopes of named UIDs ---------------------------------------------------------------

    private class Paged(val walk: ImapUidWalk, val pages: List<List<ImapMessage>>)

    /**
     * A folder of 5 by its SELECT. Every full `UID FETCH` is answered with the envelopes of the
     * set it names, ranges expanded; [before] is written ahead of the answer to the nth of them.
     */
    private fun fetching(
        flags: (Long) -> String = { "" },
        before: (nth: Int) -> String = { "" },
    ): FakeImapServer {
        val fetches = AtomicInteger()
        return FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 5)
                line.startsWith("UID FETCH ") -> {
                    val named = expandUidSet(line.removePrefix("UID FETCH ").substringBefore(' '))
                    before(fetches.incrementAndGet()) + fetchResponse(tag, named, flags) { false }
                }
                else -> ok(tag)
            }
        }
    }

    /** [startedWith] null is the reading under test; the folder's count is the incremental one. */
    private fun paged(server: FakeImapServer, uids: List<Long>, pageSize: Int, judged: Boolean = false): Paged {
        val pages = mutableListOf<List<ImapMessage>>()
        val walk = server.session().use { session ->
            val status = session.select("INBOX")
            val startedWith = if (judged) status.exists else null
            runBlocking { session.fetchUidsPaged(uids, startedWith, pageSize) { pages += it } }
        }
        return Paged(walk, pages)
    }

    private fun FakeImapServer.afterSelect(): List<String> = issued().dropWhile { !it.startsWith("SELECT") }.drop(1)

    @Test fun `named UIDs go out by pages of compressed sets, with nothing between them`() {
        fetching().use { server ->
            val out = paged(server, listOf(40L, 39L, 38L, 30L, 21L, 20L, 9L), pageSize = 3)

            assertEquals(
                listOf("UID FETCH 38:40 $items", "UID FETCH 20:21,30 $items", "UID FETCH 9 $items", "LOGOUT"),
                server.afterSelect(),
            )
            assertEquals(listOf(listOf(40L, 39L, 38L), listOf(30L, 21L, 20L), listOf(9L)), out.pages.map { page -> page.map { it.uid } })
            assertEquals(listOf(40L, 39L, 38L, 30L, 21L, 20L, 9L), out.walk.uids)
            assertEquals("Message 30", out.pages[1].first().subject)
            assertFalse(out.walk.moved)
        }
    }

    @Test fun `mail arriving between two pages stops nothing and is not movement`() {
        fetching(before = { nth -> if (nth == 2) "* 6 EXISTS\r\n" else "" }).use { server ->
            val out = paged(server, listOf(4L, 3L, 2L, 1L), pageSize = 2)

            assertFalse("an arrival was reported as a folder that moved", out.walk.moved)
            assertEquals(listOf(listOf(4L, 3L), listOf(2L, 1L)), out.pages.map { page -> page.map { it.uid } })
        }
    }

    @Test fun `the same arrival is still movement to the reading that judges it`() {
        fetching(before = { nth -> if (nth == 2) "* 6 EXISTS\r\n" else "" }).use { server ->
            val out = paged(server, listOf(4L, 3L, 2L, 1L), pageSize = 2, judged = true)

            assertTrue(out.walk.moved)
            assertEquals(
                listOf("UID FETCH 4,3 $items", "NOOP", "UID FETCH 2,1 $items", "LOGOUT"),
                server.afterSelect(),
            )
        }
    }

    @Test fun `an EXPUNGE announced with a page is not a verdict this reading gives`() {
        fetching(before = { nth -> if (nth == 1) "* 2 EXPUNGE\r\n" else "" }).use { server ->
            val out = paged(server, listOf(4L, 3L, 1L), pageSize = 2)

            assertFalse(out.walk.moved)
            assertEquals(listOf(4L, 3L, 1L), out.walk.uids)
        }
    }

    @Test fun `a flags-only FETCH nobody asked for does not become a message`() {
        val flagsOnly = "* 2 FETCH (UID 3 FLAGS (\\Seen) MODSEQ (812))\r\n* 9 FETCH (UID 77 FLAGS (\\Seen) MODSEQ (813))\r\n"
        fetching(before = { flagsOnly }).use { server ->
            val out = paged(server, listOf(4L, 3L), pageSize = 2)

            assertEquals(listOf(4L to "Message 4", 3L to "Message 3"), out.pages.single().map { it.uid to it.subject })
            assertEquals(listOf(4L, 3L), out.walk.uids)
        }
    }

    @Test fun `a whole envelope for a UID the page did not name is left out`() {
        val stranger = fetchResponse("x", listOf(77L)) { false }.removeSuffix("x OK fetch completed\r\n")
        fetching(before = { stranger }).use { server ->
            val out = paged(server, listOf(4L, 3L), pageSize = 2)

            assertEquals(listOf(4L, 3L), out.pages.flatten().map { it.uid })
            assertEquals(listOf(4L, 3L), out.walk.uids)
        }
    }

    @Test fun `a message flagged deleted is not brought back`() {
        fetching(flags = { if (it == 3L) "\\Deleted" else "" }).use { server ->
            val out = paged(server, listOf(4L, 3L, 2L), pageSize = 50)

            assertEquals(listOf(4L, 2L), out.pages.single().map { it.uid })
            assertEquals(listOf(4L, 2L), out.walk.uids)
        }
    }

    @Test fun `no UID, no command`() {
        fetching().use { server ->
            val out = paged(server, emptyList(), pageSize = 2)

            assertEquals(listOf("LOGOUT"), server.afterSelect())
            assertEquals(emptyList<Long>(), out.walk.uids)
        }
    }
}
