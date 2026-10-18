package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #255: a folder read in full contradicts the index rows claiming to match there, and only after a
 * probe on the same session, the folder still selected. Driven against a scripted server.
 */
class SearchContradictsTheIndexTest {

    private val starred = buildImapSearch(ImapSearchCriteria(flagged = true))

    /**
     * INBOX lists 8 as starred and still holds 6 (no longer starred); 5 and 7 are gone. Archive lists
     * nothing and no longer holds 3. [probe] answers `UID SEARCH UID`, given the folder and the line.
     */
    private fun server(
        uidValidity: Long = 7L,
        probe: (tag: String, folder: String) -> String = { tag, folder ->
            searchResponse(tag, if (folder == "INBOX") listOf(6L) else emptyList())
        },
    ): FakeImapServer {
        var selected = ""
        return FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> {
                    selected = line.substringAfter('"').substringBefore('"')
                    selectResponse(tag, uidValidity = uidValidity)
                }
                line.startsWith("UID SEARCH UID") -> probe(tag, selected)
                line.startsWith("UID SEARCH") -> searchResponse(tag, if (selected == "INBOX") listOf(8L) else emptyList())
                line.startsWith("UID FETCH") && line.endsWith("(UID FLAGS)") ->
                    uidsOf(line).joinToString("") { "* 1 FETCH (UID $it FLAGS (\\Seen))\r\n" } + "$tag OK fetch completed\r\n"
                line.startsWith("UID FETCH") -> fetchResponse(tag, uidsOf(line)) { false }
                else -> ok(tag)
            }
        }
    }

    private val claims = mapOf(
        "INBOX" to ImapIndexClaims(setOf(5L, 6L, 7L, 8L), uidValidity = 7L),
        "Archive" to ImapIndexClaims(setOf(3L), uidValidity = 7L),
    )

    private fun walk(server: FakeImapServer, claims: Map<String, ImapIndexClaims> = this.claims) =
        server.session().use { session ->
            session.searchFolders(listOf("INBOX", "Archive"), starred, requireAttachment = false, limit = 50, claims = claims)
        }

    @Test
    fun `an unanswered claim is gone only when the probe does not list it, and a listed one takes the flags read`() {
        server().use { server ->
            val hits = walk(server).associateBy { it.mailbox }

            val inbox = hits.getValue("INBOX").verdict!!
            assertEquals("absent from the probe: gone", setOf(5L, 7L), inbox.gone)
            assertEquals("still in the folder: its flags, not a purge", mapOf(6L to listOf("\\Seen")), inbox.flags)
            assertEquals(
                "a folder that lists nothing still answers for its claims",
                setOf(3L),
                hits.getValue("Archive").verdict!!.gone,
            )
            val probes = server.issued().filter { it.startsWith("UID SEARCH UID") }
            assertEquals("only what the answer left out is probed, 8 being listed", listOf("UID SEARCH UID 5:7", "UID SEARCH UID 3"), probes)
            val inboxSelect = server.issued().indexOf("""SELECT "INBOX"""")
            val archiveSelect = server.issued().indexOf("""SELECT "Archive"""")
            val inboxProbe = server.issued().indexOf("UID SEARCH UID 5:7")
            assertTrue("probed while INBOX is still selected", inboxProbe in (inboxSelect + 1) until archiveSelect)
        }
    }

    @Test
    fun `a refused probe proves nothing`() {
        server(probe = { tag, _ -> "$tag NO [UNAVAILABLE] try later\r\n" }).use { server ->
            val verdicts = walk(server).map { it.verdict }
            assertEquals("both folders were read in full, both have a verdict", 2, verdicts.filterNotNull().size)
            assertEquals("nothing is gone", emptySet<Long>(), verdicts.flatMap { it!!.gone }.toSet())
            assertEquals("and no flags were read", emptyMap<Long, List<String>>(), verdicts.first()!!.flags)
        }
    }

    @Test
    fun `a folder under another numbering than the claims contradicts nothing and is not probed`() {
        server(uidValidity = 8L).use { server ->
            val hits = walk(server)
            assertTrue(hits.all { it.verdict == null })
            assertFalse(server.issued().any { it.startsWith("UID SEARCH UID") })
        }
    }

    @Test
    fun `a folder stating no numbering contradicts nothing`() {
        server(uidValidity = 0L).use { server ->
            assertTrue(walk(server).all { it.verdict == null })
            assertFalse(server.issued().any { it.startsWith("UID SEARCH UID") })
        }
    }

    @Test
    fun `a folder whose search failed contradicts nothing`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, uidValidity = 7L)
                line.startsWith("UID SEARCH UID") -> searchResponse(tag, emptyList())
                line.startsWith("UID SEARCH") -> "$tag NO search refused\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            assertTrue(walk(server).all { it.verdict == null })
            assertFalse(server.issued().any { it.startsWith("UID SEARCH UID") })
        }
    }

    @Test
    fun `without claims the walk puts no probe on the wire`() {
        server().use { server ->
            val hits = walk(server, claims = emptyMap())
            assertNull(hits.single().verdict)
            assertFalse(server.issued().any { it.startsWith("UID SEARCH UID") })
        }
    }
}
