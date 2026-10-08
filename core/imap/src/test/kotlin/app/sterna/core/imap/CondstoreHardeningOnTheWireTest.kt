package app.sterna.core.imap

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the commands of an incremental reading refuse to take from a server's answer, on a scripted
 * server: a `FETCH` nobody asked for in a paged fetch, and a live set asked of a window of nothing.
 */
class CondstoreHardeningOnTheWireTest {

    private class Paged(val walk: ImapUidWalk, val pages: List<List<ImapMessage>>)

    /** Three messages, UIDs 5001..5003; [around] wraps the answer to every full `UID FETCH`. */
    private fun server(searchAnswer: String? = null, around: (answer: String, tagged: String) -> String = { answer, tagged -> answer + tagged }) =
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> "* 3 EXISTS\r\n* OK [UIDVALIDITY 1] ok\r\n$tag OK [READ-WRITE] selected\r\n"
                line.startsWith("UID SEARCH") -> (searchAnswer ?: "* SEARCH 5001 5002 5003\r\n") + ok(tag)
                line.startsWith("UID FETCH ") -> {
                    val tagged = "$tag OK fetch completed\r\n"
                    around(fetchResponse(tag, uidsOf(line)) { false }.removeSuffix(tagged), tagged)
                }
                else -> ok(tag)
            }
        }

    private fun paged(server: FakeImapServer, uids: List<Long>): Paged {
        val pages = mutableListOf<List<ImapMessage>>()
        val walk = server.session().use { session ->
            val status = session.select("INBOX")
            runBlocking { session.fetchUidsPaged(uids, status.exists, IMAP_FOLDER_PAGE) { pages += it } }
        }
        return Paged(walk, pages)
    }

    /** The envelope answer a server would give for [uid], as untagged lines alone. */
    private fun envelopeOf(uid: Long): String = fetchResponse("x", listOf(uid)) { false }.removeSuffix("x OK fetch completed\r\n")

    private val flagsOnly = "* 2 FETCH (UID 5002 FLAGS (\\Seen \\Flagged) MODSEQ (812))\r\n"

    // -- the paged fetch ---------------------------------------------------------------------------

    @Test fun `a flags-only FETCH slipped in before the answer does not stand in for the message`() {
        server(around = { answer, tagged -> flagsOnly + answer + tagged }).use { server ->
            val out = paged(server, listOf(5003L, 5002L, 5001L))

            assertEquals(
                "the unsolicited line was read as the message: a row with no subject, and the real answer dropped as its duplicate",
                listOf(5003L to "Message 5003", 5002L to "Message 5002", 5001L to "Message 5001"),
                out.pages.single().map { it.uid to it.subject },
            )
            assertEquals(listOf(5003L, 5002L, 5001L), out.walk.uids)
        }
    }

    @Test fun `a flags-only FETCH slipped in after the answer adds no message either`() {
        server(around = { answer, tagged -> answer + flagsOnly + tagged }).use { server ->
            val out = paged(server, listOf(5003L, 5002L, 5001L))

            assertEquals(listOf(5003L to "Message 5003", 5002L to "Message 5002", 5001L to "Message 5001"), out.pages.single().map { it.uid to it.subject })
        }
    }

    @Test fun `a FETCH for a UID the page did not ask for is neither delivered nor counted`() {
        val stranger = "* 9 FETCH (UID 5900 FLAGS (\\Seen) MODSEQ (812))\r\n"
        server(around = { answer, tagged -> stranger + answer + tagged }).use { server ->
            val out = paged(server, listOf(5002L, 5001L))

            assertEquals(listOf(5002L, 5001L), out.pages.flatten().map { it.uid })
            assertEquals(listOf(5002L, 5001L), out.walk.uids)
        }
    }

    @Test fun `even a whole envelope is left out when its UID was not asked for`() {
        server(around = { answer, tagged -> answer + envelopeOf(5003L) + tagged }).use { server ->
            val out = paged(server, listOf(5002L, 5001L))

            assertEquals(listOf(5002L, 5001L), out.pages.flatten().map { it.uid })
            assertEquals(listOf(5002L, 5001L), out.walk.uids)
        }
    }

    @Test fun `an EXPUNGE beside an unsolicited FETCH is still movement`() {
        server(around = { answer, tagged -> flagsOnly + "* 1 EXPUNGE\r\n" + answer + tagged }).use { server ->
            assertTrue(paged(server, listOf(5003L, 5002L, 5001L)).walk.moved)
        }
    }

    // -- the live set ------------------------------------------------------------------------------

    @Test fun `a window of nothing asks nothing, as the walk asks nothing`() {
        listOf(0, -1).forEach { limit ->
            server().use { server ->
                val live = server.session().use { session -> session.liveUids(session.select("INBOX"), limit) }

                assertEquals(
                    "limit $limit put a search on the wire: its floor is above the folder, and `n:*` then answers the last message",
                    emptyList<String>(),
                    server.issued().filter { it.startsWith("UID SEARCH") },
                )
                assertEquals(emptySet<Long>(), live.uids)
                assertFalse(live.moved)
                assertFalse("a window of nothing vouched for an empty folder", live.folderStatedEmpty)
            }
        }
    }

    @Test fun `an empty answer from a folder that holds messages does not state the folder empty`() {
        server(searchAnswer = "* SEARCH\r\n").use { server ->
            val live = server.session().use { session -> session.liveUids(session.select("INBOX"), limit = 50) }

            assertEquals(listOf("UID SEARCH 1:* UNDELETED"), server.issued().filter { it.startsWith("UID SEARCH") })
            assertEquals(emptySet<Long>(), live.uids)
            assertFalse("the caller could not tell this from a search key the server ignored", live.folderStatedEmpty)
        }
    }
}
