package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Once CONDSTORE is on, the server adds a `MODSEQ (n)` item to its untagged `FETCH` lines (RFC 7162
 * §3.1.4), wherever it likes among the others. One test per reader of such a line.
 */
class ModSeqItemToleranceTest {

    private enum class Where { BEFORE, BETWEEN, AFTER }

    /** A canned full `UID FETCH` answer for [uids], `MODSEQ (12345)` placed at [where] on each line. */
    private fun fetched(tag: String, uids: List<Long>, where: Where?, flags: (Long) -> String = { "\\Seen" }): String {
        val plain = fetchResponse(tag, uids, flags = flags) { false }
        val placed = when (where) {
            null -> plain
            Where.BEFORE -> plain.replace(" FETCH (UID ", " FETCH ($MODSEQ UID ")
            Where.BETWEEN -> plain.replace(" INTERNALDATE ", " $MODSEQ INTERNALDATE ")
            Where.AFTER -> plain.replace(")\r\n", " $MODSEQ)\r\n")
        }
        if (where != null) check(placed != plain && MODSEQ in placed) { "the fixture placed no MODSEQ $where" }
        return placed
    }

    /** A flags-only untagged `FETCH`, as a `STORE` or a `CHANGEDSINCE` fetch is answered. */
    private fun flagsLine(where: Where, uid: Long = 7L, flags: String = "\\Seen \\Deleted"): String = when (where) {
        Where.BEFORE -> "* 3 FETCH ($MODSEQ UID $uid FLAGS ($flags))\r\n"
        Where.BETWEEN -> "* 3 FETCH (UID $uid $MODSEQ FLAGS ($flags))\r\n"
        Where.AFTER -> "* 3 FETCH (UID $uid FLAGS ($flags) $MODSEQ)\r\n"
    }

    private fun parse(raw: String): List<Any?> =
        ImapParser(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8))).readResponse()

    @Test fun `folderMoved does not take a FETCH carrying MODSEQ for movement`() {
        for (where in Where.values()) {
            val lines = listOf(parse(flagsLine(where)), parse("* 1000 EXISTS\r\n"))
            assertFalse("MODSEQ $where was read as movement", folderMoved(lines, startedWith = 1000))
            assertTrue(
                "MODSEQ $where hid an EXPUNGE",
                folderMoved(lines + listOf(parse("* 3 EXPUNGE\r\n")), startedWith = 1000),
            )
        }
    }

    @Test fun `a message is built the same with MODSEQ before, between and after its items`() {
        val uids = listOf(7L, 9L)
        fun read(where: Where?): List<ImapMessage> = FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag)
                line.startsWith("UID FETCH") -> fetched(tag, uids, where)
                else -> ok(tag)
            }
        }.use { server ->
            server.session().use { session ->
                session.select("INBOX")
                session.fetchUids(uids)
            }
        }

        val plain = read(null)
        assertEquals(uids, plain.map { it.uid })
        for (where in Where.values()) assertEquals("MODSEQ $where changed the message", plain, read(where))
    }

    @Test fun `a deleted message stays hidden with MODSEQ before, between and after its items`() {
        for (where in Where.values()) {
            val read = FakeImapServer { tag, line ->
                when {
                    line.startsWith("SELECT") -> selectResponse(tag)
                    line.startsWith("UID FETCH") ->
                        fetched(tag, listOf(7L, 9L), where) { if (it == 9L) "\\Seen \\Deleted" else "\\Seen" }
                    else -> ok(tag)
                }
            }.use { server ->
                server.session().use { session ->
                    session.select("INBOX")
                    session.fetchUids(listOf(7L, 9L))
                }
            }
            assertEquals("MODSEQ $where let a \\Deleted message through", listOf(7L), read.map { it.uid })
        }
    }

    @Test fun `a STORE answered with MODSEQ before, between and after the flags is accepted`() {
        for (where in Where.values()) {
            FakeImapServer { tag, line ->
                when {
                    line.startsWith("SELECT") -> selectResponse(tag)
                    line.startsWith("UID STORE") -> flagsLine(where) + "$tag OK [HIGHESTMODSEQ 12346] stored\r\n"
                    else -> ok(tag)
                }
            }.use { server ->
                server.session().use { session ->
                    session.select("INBOX")
                    session.setFlag(7L, "\\Seen", set = true)
                }
                assertEquals(listOf("UID STORE 7 +FLAGS (\\Seen)"), server.issued().filter { it.startsWith("UID STORE") })
            }
        }
    }

    @Test fun `changed flags are read raw with MODSEQ before, between and after them`() {
        for (where in Where.values()) {
            val changed = FakeImapServer { tag, line ->
                when {
                    line.startsWith("SELECT") -> selectResponse(tag)
                    line.startsWith("UID FETCH") -> flagsLine(where) + ok(tag)
                    else -> ok(tag)
                }
            }.use { server ->
                server.session().use { session ->
                    val status = session.select("INBOX")
                    session.flagsChangedSince(1L, 12000L, status.exists)
                }
            }
            assertEquals(
                "MODSEQ $where",
                listOf(ImapFlagChange(7L, listOf("\\Seen", "\\Deleted"))),
                changed.changes,
            )
        }
    }

    private companion object {
        const val MODSEQ = "MODSEQ (12345)"
    }
}
