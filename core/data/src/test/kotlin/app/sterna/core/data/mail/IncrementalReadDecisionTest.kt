package app.sterna.core.data.mail

import app.sterna.core.data.db.EmailEntity
import app.sterna.core.imap.ImapFlagChange
import app.sterna.core.imap.ImapFolderWalk
import app.sterna.core.imap.ImapMailboxStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decisions of an incremental IMAP read, executed: when a delta is allowed, what it may write, what it may keep. */
class IncrementalReadDecisionTest {

    private val account = "acc-1"
    private val inbox = "INBOX"

    private fun id(uid: Long) = "imap:$account:$inbox:$uid"

    private fun decide(
        condstore: Boolean = true,
        stated: Long? = 812L,
        recorded: Long? = 800L,
        numbering: UidValidity.Verdict = UidValidity.Verdict.SAME,
    ) = mayReadIncrementally(condstore, stated, recorded, numbering)

    // -- delta or full walk ------------------------------------------------------------------------

    @Test fun `the conditions together allow a delta`() {
        assertTrue(decide())
        assertTrue("a folder nothing changed in states the recorded value itself", decide(stated = 800L, recorded = 800L))
    }

    @Test fun `a mod-sequence stated below the recorded one is walked in full`() {
        assertFalse(
            "a delta was allowed since a mod-sequence the server no longer reaches: it answers nothing, and the lower value is then recorded",
            decide(stated = 799L, recorded = 800L),
        )
    }

    @Test fun `a server that does not announce CONDSTORE is walked in full`() {
        assertFalse(decide(condstore = false))
    }

    @Test fun `a folder that keeps no mod-sequence is walked in full`() {
        assertFalse(decide(stated = null))
    }

    @Test fun `a folder with no recorded mod-sequence is walked in full`() {
        assertFalse(decide(recorded = null))
    }

    @Test fun `any numbering verdict but SAME is walked in full`() {
        UidValidity.Verdict.entries.filter { it != UidValidity.Verdict.SAME }.forEach { verdict ->
            assertFalse("a delta was allowed under $verdict", decide(numbering = verdict))
        }
        assertEquals(
            setOf(UidValidity.Verdict.FIRST_SIGHT, UidValidity.Verdict.CHANGED, UidValidity.Verdict.UNVERIFIABLE),
            UidValidity.Verdict.entries.toSet() - UidValidity.Verdict.SAME,
        )
    }

    // -- the mark ----------------------------------------------------------------------------------

    @Test fun `a SELECT states a mark only with both a mod-sequence and a numbering`() {
        assertEquals(ImapModSeqMark(9L, 812L), modSeqMarkOf(ImapMailboxStatus(3, 9L, 4L, true, 812L)))
        assertNull(modSeqMarkOf(ImapMailboxStatus(3, 9L, 4L, true, null)))
        assertNull(modSeqMarkOf(ImapMailboxStatus(3, 0L, 4L, true, 812L)))
    }

    private fun load(walk: ImapFolderWalk, mark: ImapModSeqMark? = ImapModSeqMark(9L, 812L)) =
        ImapFolderLoad(emptyList(), inbox, "Inbox", 0, "tester", walk, modSeq = mark)

    @Test fun `a reconciled read keeps its mark`() {
        assertEquals(ImapModSeqMark(9L, 812L), reconciledModSeq(load(ImapFolderWalk(listOf(9L, 8L), moved = false)), account))
        assertEquals(
            "a folder the SELECT stated empty is reconciled too",
            ImapModSeqMark(9L, 812L),
            reconciledModSeq(load(ImapFolderWalk(emptyList(), moved = false, folderStatedEmpty = true)), account),
        )
    }

    @Test fun `a read the reconcile refused keeps no mark`() {
        assertNull(
            "a mark was kept from a walk whose folder moved",
            reconciledModSeq(load(ImapFolderWalk(listOf(9L, 8L), moved = true)), account),
        )
        assertNull(
            "a mark was kept from a walk that read nothing of a folder not stated empty",
            reconciledModSeq(load(ImapFolderWalk(emptyList(), moved = false)), account),
        )
    }

    @Test fun `a read that carries no mark keeps none`() {
        assertNull(reconciledModSeq(load(ImapFolderWalk(listOf(9L), moved = false), mark = null), account))
    }

    // -- what a delta writes -----------------------------------------------------------------------

    private fun write(uid: Long, seen: Boolean = false, flagged: Boolean = false, answered: Boolean = false, forwarded: Boolean = false) =
        ImapFlagWrite(id(uid), seen = seen, flagged = flagged, answered = answered, forwarded = forwarded)

    @Test fun `flag writes name cached rows only, with the four flags a row holds`() {
        val writes = flagWritesFor(
            changes = listOf(
                ImapFlagChange(7L, listOf("\\Seen", "\\Flagged", "\\Answered", "\$Forwarded", "\\Deleted")),
                ImapFlagChange(8L, emptyList()),
                ImapFlagChange(9L, listOf("\\seen")),
            ),
            accountId = account,
            mailboxId = inbox,
            cachedIds = setOf(id(7), id(8)),
        )

        assertEquals(
            listOf(write(7, seen = true, flagged = true, answered = true, forwarded = true), write(8)),
            writes,
        )
    }

    @Test fun `an answer or a forward alone is a change the delta writes, whatever the case`() {
        val writes = flagWritesFor(
            changes = listOf(
                ImapFlagChange(7L, listOf("\\Seen", "\\answered")),
                ImapFlagChange(8L, listOf("\\Seen", "\$forwarded")),
                ImapFlagChange(9L, listOf("\\Seen", "Forwarded", "\\Forwarded")),
            ),
            accountId = account,
            mailboxId = inbox,
            cachedIds = setOf(id(7), id(8), id(9)),
        )

        assertEquals(
            "`\\Answered` and `\$Forwarded` are compared as `\\Seen` is, without regard to case; " +
                "`\$Forwarded` has no backslash, and a bare or backslashed `Forwarded` is another word",
            listOf(write(7, seen = true, answered = true), write(8, seen = true, forwarded = true), write(9, seen = true)),
            writes,
        )
    }

    @Test fun `the last answer for a UID is the one written`() {
        val writes = flagWritesFor(
            listOf(ImapFlagChange(7L, emptyList()), ImapFlagChange(7L, listOf("\\Seen"))),
            account, inbox, setOf(id(7)),
        )

        assertEquals(listOf(write(7, seen = true)), writes)
    }

    @Test fun `what the cache lacks is fetched newest first, under this account and folder`() {
        assertEquals(listOf(9L, 5L), absentFromCache(setOf(5L, 7L, 9L), account, inbox, setOf(id(7), "imap:other:INBOX:9")))
    }

    // -- what a page writes ------------------------------------------------------------------------

    private fun row(uid: Long) = EmailEntity(
        id = id(uid), accountId = account, mailboxId = inbox, threadId = null, subject = null, preview = null,
        receivedAt = null, fromName = null, fromEmail = null, seen = false, flagged = false, hasAttachment = false, sortKey = uid,
    )

    private fun folderWrites(
        written: MutableList<EmailEntity>,
        inFlight: Set<String> = emptySet(),
        stillConfigured: suspend () -> Unit = {},
    ) = ImapFolderWrites(
        wantsPreviews = false,
        idsForMailbox = { emptyList() },
        idsWithPreviewForMailbox = { emptyList() },
        stillConfigured = stillConfigured,
        heldLocally = { emptyList() },
        setReadAndStar = { _, _, _, _, _ -> },
        sparingInFlight = { rows -> rows.filterNot { it.id in inFlight } },
        upsertAll = { rows -> written += rows },
        markWalked = { _, _ -> },
        marksOf = { emptyList() },
    )

    @Test fun `a page written whole leaves the read free to keep its mark`() {
        val written = mutableListOf<EmailEntity>()
        val writes = folderWrites(written)

        runBlocking { writes.writePage(listOf(row(7), row(8))) }

        assertEquals(listOf(row(7), row(8)), written)
        assertTrue(writes.everyRowWritten)
    }

    @Test fun `a page that loses a row to a swipe in flight says so, and goes on saying so`() {
        val written = mutableListOf<EmailEntity>()
        val writes = folderWrites(written, inFlight = setOf(id(8)))

        runBlocking {
            writes.writePage(listOf(row(7), row(8)))
            writes.writePage(listOf(row(9)))
        }

        assertEquals("the held row was written, or another one was not", listOf(row(7), row(9)), written)
        assertFalse("a page lost a row and the read may still keep its mark", writes.everyRowWritten)
    }

    @Test fun `the account is asked about before a page is written, and its refusal writes nothing`() {
        val written = mutableListOf<EmailEntity>()
        val gone = AccountGoneException(account)

        val thrown = runCatching {
            runBlocking { folderWrites(written, stillConfigured = { throw gone }).writePage(listOf(row(7))) }
        }.exceptionOrNull()

        assertTrue("the refusal did not climb out: $thrown", thrown is AccountGoneException)
        assertEquals(emptyList<EmailEntity>(), written)
    }

    private data class Applied(val ids: List<String>, val seen: Boolean, val flagged: Boolean, val answered: Boolean, val forwarded: Boolean)

    private class Writes {
        val applied = mutableListOf<Applied>()
        var asked = 0
    }

    private fun delta(writes: Writes, held: Set<String> = emptySet(), stillConfigured: suspend () -> Unit = {}) = imapFolderDelta(
        cachedIds = { emptyList() },
        stillConfigured = { writes.asked++; stillConfigured() },
        heldLocally = { held },
        setReadAndStar = { ids, seen, flagged, answered, forwarded -> writes.applied += Applied(ids, seen, flagged, answered, forwarded) },
    )

    @Test fun `flag writes go out grouped by state, and say they were all applied`() {
        val writes = Writes()

        val all = runBlocking {
            delta(writes).writeFlags(
                listOf(write(7, seen = true), write(8, flagged = true), write(9, seen = true)),
            )
        }

        assertTrue(all)
        assertEquals(
            setOf(Applied(listOf(id(7), id(9)), true, false, false, false), Applied(listOf(id(8)), false, true, false, false)),
            writes.applied.toSet(),
        )
    }

    @Test fun `rows that differ by their answer or their forward alone are written apart`() {
        val writes = Writes()

        runBlocking {
            delta(writes).writeFlags(
                listOf(write(7, seen = true), write(8, seen = true, answered = true), write(9, seen = true, forwarded = true)),
            )
        }

        assertEquals(
            "a grouping on the read state and the star alone writes one row's answer onto its neighbours",
            setOf(
                Applied(listOf(id(7)), true, false, false, false),
                Applied(listOf(id(8)), true, false, true, false),
                Applied(listOf(id(9)), true, false, false, true),
            ),
            writes.applied.toSet(),
        )
    }

    @Test fun `a row the user has just changed is not written, and the delta says a write is owed`() {
        val writes = Writes()

        val all = runBlocking {
            delta(writes, held = setOf(id(8))).writeFlags(
                listOf(write(7, seen = true), write(8)),
            )
        }

        assertFalse("a held-back write was reported as applied: the mod-sequence would move past it", all)
        assertEquals(listOf(Applied(listOf(id(7)), true, false, false, false)), writes.applied)
    }

    @Test fun `the account is asked about before a flag is written, and its refusal writes nothing`() {
        val writes = Writes()
        val gone = AccountGoneException(account)

        val thrown = runCatching {
            runBlocking {
                delta(writes, stillConfigured = { throw gone })
                    .writeFlags(listOf(write(7, seen = true)))
            }
        }.exceptionOrNull()

        assertTrue("the refusal did not climb out: $thrown", thrown is AccountGoneException)
        assertEquals(1, writes.asked)
        assertEquals(emptyList<Applied>(), writes.applied)
    }
}
