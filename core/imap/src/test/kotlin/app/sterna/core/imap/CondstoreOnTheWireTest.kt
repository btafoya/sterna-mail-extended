package app.sterna.core.imap

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CONDSTORE (RFC 7162) against a scripted server on loopback: when `(CONDSTORE)` leaves the client,
 * what `HIGHESTMODSEQ` is read as, and the three commands an incremental reading is built from.
 */
class CondstoreOnTheWireTest {

    /**
     * A folder whose UIDs sit [UID_OFFSET] above their sequence numbers, so a command that names a
     * sequence number where a UID was meant (or the reverse) answers with the wrong messages. The
     * server honours a search key only when the command carries it.
     */
    private class Folder(count: Int) {
        val flags = LinkedHashMap<Long, MutableSet<String>>()
        val modSeq = HashMap<Long, Long>()
        var highest = 100L

        init {
            for (seq in 1..count) {
                flags[seq + UID_OFFSET] = mutableSetOf("\\Seen")
                modSeq[seq + UID_OFFSET] = 1L
            }
        }

        @Synchronized fun flag(uid: Long, flag: String) {
            flags.getValue(uid) += flag
            modSeq[uid] = ++highest
        }

        @Synchronized fun uids(): List<Long> = flags.keys.toList()

        @Synchronized fun flagsOf(uid: Long): String = flags.getValue(uid).joinToString(" ")

        @Synchronized fun deleted(uid: Long): Boolean = "\\Deleted" in flags.getValue(uid)

        @Synchronized fun changedAfter(uid: Long, since: Long): Boolean = modSeq.getValue(uid) > since

        @Synchronized fun modSeqOf(uid: Long): Long = modSeq.getValue(uid)
    }

    private fun server(
        folder: Folder = Folder(0),
        capabilities: String = "CONDSTORE",
        advertiseAtLogin: Boolean = false,
        modSeqLine: (Folder) -> String = { "* OK [HIGHESTMODSEQ ${it.highest}] Highest\r\n" },
        statesExists: Boolean = true,
        refuseSelectOf: String? = null,
        onNoop: String = "",
        inSearch: String = "",
        inChanged: String = "",
        splitSearch: Boolean = false,
    ) = FakeImapServer { tag, line ->
        val uids = folder.uids()
        when {
            advertiseAtLogin && line.startsWith("LOGIN") -> "$tag OK [CAPABILITY IMAP4rev1 $capabilities] logged in\r\n"
            line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1 $capabilities\r\n" + ok(tag)
            refuseSelectOf != null && line.startsWith("SELECT $refuseSelectOf") -> "$tag NO no such mailbox\r\n"
            line.startsWith("SELECT") ->
                (if (statesExists) "* ${uids.size} EXISTS\r\n" else "") +
                    "* OK [UIDVALIDITY 1] ok\r\n* OK [UIDNEXT ${UID_OFFSET + uids.size + 1}] ok\r\n" +
                    // A server states a mod-sequence only to a client that asked for one.
                    (if (line.endsWith("(CONDSTORE)")) modSeqLine(folder) else "") +
                    "$tag OK [READ-WRITE] selected\r\n"
            line.startsWith("UID SEARCH ") -> {
                val arguments = line.removePrefix("UID SEARCH ")
                val lowest = arguments.substringBefore(":*").toInt()
                // RFC 3501: `n:*` with n beyond the last message still names the last message.
                val named = if (lowest > uids.size) uids.takeLast(1) else uids.drop(lowest - 1)
                val found = if ("UNDELETED" in arguments) named.filterNot(folder::deleted) else named
                val lines = if (splitSearch) found.chunked(found.size / 2 + 1) else listOf(found)
                inSearch + lines.joinToString("") { "* SEARCH ${it.joinToString(" ")}\r\n" } + ok(tag)
            }
            line.startsWith("UID FETCH ") && "CHANGEDSINCE" in line -> {
                val lowest = line.removePrefix("UID FETCH ").substringBefore(":*").toLong()
                val since = line.substringAfter("CHANGEDSINCE ").substringBefore(')').toLong()
                val named = if (uids.isNotEmpty() && lowest > uids.last()) uids.takeLast(1) else uids.filter { it >= lowest }
                inChanged + named.filter { folder.changedAfter(it, since) }.joinToString("") { uid ->
                    "* ${uid - UID_OFFSET} FETCH (UID $uid FLAGS (${folder.flagsOf(uid)}) MODSEQ (${folder.modSeqOf(uid)}))\r\n"
                } + ok(tag)
            }
            line.startsWith("UID FETCH ") -> fetchResponse(tag, uidsOf(line), flags = folder::flagsOf) { false }
            line.startsWith("NOOP") -> onNoop + ok(tag)
            else -> ok(tag)
        }
    }

    private fun FakeImapServer.selects(): List<String> = issued().filter { it.startsWith("SELECT") }

    private fun FakeImapServer.searches(): List<String> = issued().filter { it.startsWith("UID SEARCH") }

    private fun FakeImapServer.uidFetches(): List<String> = issued().filter { it.startsWith("UID FETCH") }

    // -- SELECT ----------------------------------------------------------------------------------

    @Test fun `a server that does not announce CONDSTORE gets the plain SELECT, to the octet`() {
        server(capabilities = "UIDPLUS").use { server ->
            val status = server.session().use { it.select("INBOX", condstore = true) }

            assertEquals(listOf("SELECT \"INBOX\""), server.selects())
            assertNull(status.highestModSeq)
        }
    }

    @Test fun `a server that announces CONDSTORE is asked for it, and its HIGHESTMODSEQ is read as a Long`() {
        server(modSeqLine = { "* OK [HIGHESTMODSEQ 715194045007] Highest\r\n" }).use { server ->
            val status = server.session().use { it.select("INBOX", condstore = true) }

            assertEquals(listOf("SELECT \"INBOX\" (CONDSTORE)"), server.selects())
            assertEquals(715194045007L, status.highestModSeq)
        }
    }

    @Test fun `the capability is read from the login completion as well`() {
        server(advertiseAtLogin = true).use { server ->
            server.session().use { it.select("INBOX", condstore = true) }

            assertEquals(listOf("SELECT \"INBOX\" (CONDSTORE)"), server.selects())
        }
    }

    @Test fun `NOMODSEQ is no mod-sequence at all, and never zero`() {
        server(modSeqLine = { "* OK [NOMODSEQ] Sorry, this mailbox format does not support modsequences\r\n" })
            .use { server ->
                val status = server.session().use { it.select("INBOX", condstore = true) }

                assertEquals(listOf("SELECT \"INBOX\" (CONDSTORE)"), server.selects())
                assertNotEquals(0L, status.highestModSeq)
                assertNull(status.highestModSeq)
            }
    }

    @Test fun `a caller that does not ask gets the plain SELECT even from a CONDSTORE server`() {
        server().use { server ->
            val status = server.session().use { it.select("INBOX") }

            assertEquals(listOf("SELECT \"INBOX\""), server.selects())
            assertNull(status.highestModSeq)
            assertEquals("select() asked for the capability list on its own", 1, server.issued().count { it == "CAPABILITY" })
        }
    }

    @Test fun `the verbatim retry of a refused name carries the parameter too`() {
        server(refuseSelectOf = "\"R&-D\"").use { server ->
            server.session().use { it.select("R&D", condstore = true) }

            assertEquals(listOf("SELECT \"R&-D\" (CONDSTORE)", "SELECT \"R&D\" (CONDSTORE)"), server.selects())
        }
    }

    // -- the live set ----------------------------------------------------------------------------

    @Test fun `the live set is asked by the window's sequence floor and answered in UIDs`() {
        server(Folder(1000)).use { server ->
            val live = server.session().use { session ->
                session.liveUids(session.select("INBOX"), limit = 300)
            }

            assertEquals(listOf("UID SEARCH 701:* UNDELETED"), server.searches())
            assertEquals((701L + UID_OFFSET..1000L + UID_OFFSET).toSet(), live.uids)
            assertFalse(live.moved)
            assertFalse(live.folderStatedEmpty)
        }
    }

    @Test fun `a message flagged deleted between two readings leaves the live set and shows in the changes`() {
        val folder = Folder(1000)
        val gone = 900L + UID_OFFSET
        server(folder).use { server ->
            server.session().use { session ->
                val status = session.select("INBOX", condstore = true)
                val before = session.liveUids(status, limit = 300)
                assertTrue(gone in before.uids)

                folder.flag(gone, "\\Deleted")

                val after = session.liveUids(status, limit = 300)
                val changed = session.flagsChangedSince(after.uids.min(), status.highestModSeq!!, status.exists)

                assertFalse("a \\Deleted message is still in the live set", gone in after.uids)
                assertEquals(before.uids - gone, after.uids)
                assertEquals(listOf(gone), changed.changes.map { it.uid })
                assertTrue(
                    "the \\Deleted flag was lost on the way: ${changed.changes}",
                    changed.changes.single().flags.any { it.equals("\\Deleted", ignoreCase = true) },
                )
                assertTrue("\\Seen" in changed.changes.single().flags)
                assertFalse(changed.moved)
            }
            assertEquals(
                listOf("UID FETCH ${701L + UID_OFFSET}:* (UID FLAGS) (CHANGEDSINCE 100)"),
                server.uidFetches(),
            )
        }
    }

    @Test fun `an empty folder is not searched, and says it was stated empty`() {
        server(Folder(0)).use { server ->
            val live = server.session().use { session -> session.liveUids(session.select("INBOX"), limit = 300) }

            assertEquals(emptyList<String>(), server.searches())
            assertEquals(emptySet<Long>(), live.uids)
            assertTrue(live.folderStatedEmpty)
        }
    }

    @Test fun `a SELECT that never stated a size does not vouch for an empty folder`() {
        server(Folder(12), statesExists = false).use { server ->
            val live = server.session().use { session -> session.liveUids(session.select("INBOX"), limit = 300) }

            assertEquals(emptySet<Long>(), live.uids)
            assertFalse(live.folderStatedEmpty)
        }
    }

    @Test fun `a window larger than the folder asks from the first message`() {
        server(Folder(12)).use { server ->
            val live = server.session().use { session ->
                session.liveUids(session.select("INBOX"), limit = Int.MAX_VALUE)
            }

            assertEquals(listOf("UID SEARCH 1:* UNDELETED"), server.searches())
            assertEquals((1L + UID_OFFSET..12L + UID_OFFSET).toSet(), live.uids)
        }
    }

    @Test fun `every SEARCH line of the answer is read`() {
        server(Folder(40), splitSearch = true).use { server ->
            val live = server.session().use { session -> session.liveUids(session.select("INBOX"), limit = 40) }

            assertEquals(40, live.uids.size)
        }
    }

    @Test fun `an EXPUNGE announced with the live set leaves it unreconcilable`() {
        server(Folder(40), inSearch = "* 3 EXPUNGE\r\n").use { server ->
            val live = server.session().use { session -> session.liveUids(session.select("INBOX"), limit = 40) }

            assertTrue(live.moved)
        }
    }

    // -- the paged UID FETCH ----------------------------------------------------------------------

    private class Paged {
        val pages = mutableListOf<List<ImapMessage>>()
        lateinit var walk: ImapUidWalk
    }

    private fun paged(server: FakeImapServer, uids: List<Long>): Paged {
        val out = Paged()
        server.session().use { session ->
            val status = session.select("INBOX")
            out.walk = runBlocking {
                session.fetchUidsPaged(uids, status.exists, IMAP_FOLDER_PAGE) { out.pages += it }
            }
        }
        return out
    }

    private val items = "(UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE BODY.PEEK[HEADER.FIELDS (REFERENCES)])"

    @Test fun `450 UIDs go out as three UID FETCH of the full walk's items, a NOOP between them`() {
        val folder = Folder(450)
        server(folder).use { server ->
            val out = paged(server, folder.uids().reversed())

            val fetches = server.uidFetches()
            assertEquals(listOf(200, 200, 50), fetches.map { uidsOf(it).size })
            assertEquals(folder.uids().reversed(), fetches.flatMap { uidsOf(it) })
            fetches.forEach { assertEquals("UID FETCH ${uidsOf(it).joinToString(",")} $items", it) }
            assertEquals(
                listOf("UID FETCH", "NOOP", "UID FETCH", "NOOP", "UID FETCH"),
                server.issued().filter { it.startsWith("UID FETCH") || it == "NOOP" }
                    .map { if (it == "NOOP") it else "UID FETCH" },
            )
            assertEquals(listOf(200, 200, 50), out.pages.map { it.size })
            assertEquals(450, out.walk.uids.size)
            assertFalse("a quiet folder counts as moved", out.walk.moved)
        }
    }

    @Test fun `the paged fetch builds the messages the one-shot fetch builds`() {
        val folder = Folder(5)
        val oneShot = server(folder).use { server ->
            server.session().use { session ->
                session.select("INBOX")
                session.fetchUids(folder.uids())
            }
        }
        server(folder).use { server ->
            val out = paged(server, folder.uids())

            assertEquals(oneShot.sortedByDescending { it.uid }, out.pages.single())
        }
    }

    @Test fun `an EXPUNGE between two pages leaves the paged fetch unreconcilable`() {
        val folder = Folder(450)
        server(folder, onNoop = "* 3 EXPUNGE\r\n").use { server ->
            val out = paged(server, folder.uids())

            assertTrue(out.walk.moved)
            assertEquals(3, out.pages.size)
        }
    }

    @Test fun `an EXISTS that grew between two pages is refused like any other movement`() {
        val folder = Folder(450)
        server(folder, onNoop = "* 451 EXISTS\r\n").use { server ->
            assertTrue(paged(server, folder.uids()).walk.moved)
        }
    }

    @Test fun `the same EXISTS between two pages is not movement`() {
        val folder = Folder(450)
        server(folder, onNoop = "* 450 EXISTS\r\n").use { server ->
            assertFalse(paged(server, folder.uids()).walk.moved)
        }
    }

    @Test fun `a message flagged deleted stays out of the paged fetch`() {
        val folder = Folder(5)
        val hidden = 3L + UID_OFFSET
        folder.flag(hidden, "\\Deleted")
        server(folder).use { server ->
            val out = paged(server, folder.uids())

            assertFalse(hidden in out.walk.uids)
            assertFalse(hidden in out.pages.flatten().map { it.uid })
            assertEquals(4, out.walk.uids.size)
        }
    }

    @Test fun `no UID, no command`() {
        server(Folder(5)).use { server ->
            val out = paged(server, emptyList())

            assertEquals(emptyList<String>(), server.uidFetches())
            assertEquals(emptyList<Long>(), out.walk.uids)
            assertFalse(out.walk.moved)
        }
    }

    // -- CHANGEDSINCE ----------------------------------------------------------------------------

    @Test fun `a floor above the last UID does not bring back the last message`() {
        val folder = Folder(10)
        val last = 10L + UID_OFFSET
        folder.flag(last, "\\Flagged")
        server(folder).use { server ->
            val changed = server.session().use { session ->
                val status = session.select("INBOX")
                session.flagsChangedSince(last + 50, 100L, status.exists)
            }

            assertEquals(listOf("UID FETCH ${last + 50}:* (UID FLAGS) (CHANGEDSINCE 100)"), server.uidFetches())
            assertEquals(emptyList<ImapFlagChange>(), changed.changes)
        }
    }

    @Test fun `nothing changed is an empty answer from a folder that held still`() {
        server(Folder(10)).use { server ->
            val changed = server.session().use { session ->
                val status = session.select("INBOX")
                session.flagsChangedSince(1L + UID_OFFSET, 100L, status.exists)
            }

            assertEquals(emptyList<ImapFlagChange>(), changed.changes)
            assertFalse(changed.moved)
        }
    }

    @Test fun `an EXPUNGE announced with the changes leaves them unreconcilable`() {
        server(Folder(10), inChanged = "* 3 EXPUNGE\r\n").use { server ->
            val changed = server.session().use { session ->
                val status = session.select("INBOX")
                session.flagsChangedSince(1L + UID_OFFSET, 100L, status.exists)
            }

            assertTrue(changed.moved)
        }
    }

    private companion object {
        const val UID_OFFSET = 5000L
    }
}
