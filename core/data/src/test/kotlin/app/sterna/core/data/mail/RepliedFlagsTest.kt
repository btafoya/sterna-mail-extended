package app.sterna.core.data.mail

import app.sterna.core.data.db.ReadAndStar
import app.sterna.core.imap.ImapFlagChange
import app.sterna.core.imap.ImapMessage
import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Answered" and "forwarded" (#259), from what each protocol says to the cache row and back to
 * the [Email] the list and the reader are drawn from.
 */
class RepliedFlagsTest {

    private val account = "accA"
    private val inbox = "INBOX"

    private fun imapMessage(answered: Boolean, forwarded: Boolean) = ImapMessage(
        uid = 7L,
        subject = "Six o'clock",
        fromName = "Leo",
        fromEmail = "leo@example.org",
        to = emptyList(),
        dateMillis = 1_780_000_000_000L,
        seen = true,
        flagged = false,
        answered = answered,
        forwarded = forwarded,
        hasAttachment = false,
        messageId = "<7@example.org>",
        inReplyTo = null,
    )

    private fun jmapEmail(keywords: Map<String, Boolean>) = Email(
        id = "m1",
        accountId = account,
        subject = "Six o'clock",
        receivedAt = "2026-10-15T08:00:00Z",
        keywords = keywords,
    )

    // --- IMAP: the message as listed, into the row and back -------------------------------------

    @Test fun `an IMAP message answered and not forwarded writes exactly that`() {
        val row = imapMessage(answered = true, forwarded = false).toEntity(account, inbox, 42L, preview = null)

        assertTrue("`\\Answered` read off the FETCH stopped at the ImapMessage", row.answered)
        assertFalse(row.forwarded)
    }

    @Test fun `an IMAP message forwarded and not answered writes exactly that`() {
        val row = imapMessage(answered = false, forwarded = true).toEntity(account, inbox, 42L, preview = null)

        assertFalse(row.answered)
        assertTrue("`\$Forwarded` read off the FETCH stopped at the ImapMessage", row.forwarded)
    }

    @Test fun `an IMAP row reads back as the keywords a JMAP message carries`() {
        val answered = imapMessage(answered = true, forwarded = false).toEntity(account, inbox, 42L, preview = null).toEmail()
        val forwarded = imapMessage(answered = false, forwarded = true).toEntity(account, inbox, 42L, preview = null).toEmail()

        assertEquals(mapOf("\$seen" to true, "\$answered" to true), answered.keywords)
        assertEquals(mapOf("\$seen" to true, "\$forwarded" to true), forwarded.keywords)
    }

    // --- JMAP: keywords into the row and back ---------------------------------------------------

    @Test fun `a JMAP message reads its answer and its forward from the keywords`() {
        assertTrue(jmapEmail(mapOf("\$answered" to true)).isAnswered)
        assertFalse(jmapEmail(mapOf("\$answered" to true)).isForwarded)
        assertTrue(jmapEmail(mapOf("\$forwarded" to true)).isForwarded)
        assertFalse(jmapEmail(mapOf("\$forwarded" to true)).isAnswered)
        assertFalse(
            "a keyword set to false is a keyword the message does not have",
            jmapEmail(mapOf("\$answered" to false, "\$forwarded" to false)).let { it.isAnswered || it.isForwarded },
        )
    }

    @Test fun `a JMAP row keeps the answer and the forward apart, both ways`() {
        val answered = jmapEmail(mapOf("\$answered" to true)).toEntity(account, inbox)
        val forwarded = jmapEmail(mapOf("\$forwarded" to true)).toEntity(account, inbox)

        assertEquals(true to false, answered.answered to answered.forwarded)
        assertEquals(false to true, forwarded.answered to forwarded.forwarded)
        assertEquals(mapOf("\$answered" to true), answered.toEmail().keywords)
        assertEquals(mapOf("\$forwarded" to true), forwarded.toEmail().keywords)
    }

    // --- IMAP delta: a flag-only answer reaches the cached row ----------------------------------

    @Test fun `an answer or a forward seen by a delta lands in the cached row, and nothing else moves`() {
        JdbcMailCache().use { cache ->
            val seven = imapMessage(answered = false, forwarded = false)
            val eight = seven.copy(uid = 8L)
            cache.upsert(
                listOf(
                    seven.toEntity(account, inbox, 42L, preview = "see you there"),
                    eight.toEntity(account, inbox, 42L, preview = "or at seven"),
                ),
            )
            val before = cache.rows(account)
            val delta = imapFolderDelta(
                cachedIds = { mailboxId -> cache.idsForMailbox(account, mailboxId) },
                stillConfigured = {},
                heldLocally = { emptyList() },
                setReadAndStar = { ids, seen, flagged, answered, forwarded ->
                    cache.setReadAndStar(account, ids, seen, flagged, answered, forwarded)
                },
            )

            val all = runBlocking {
                delta.writeFlags(
                    flagWritesFor(
                        changes = listOf(
                            ImapFlagChange(7L, listOf("\\Seen", "\\Answered")),
                            ImapFlagChange(8L, listOf("\\Seen", "\$Forwarded")),
                        ),
                        accountId = account,
                        mailboxId = inbox,
                        cachedIds = delta.cachedIds(inbox),
                    ),
                )
            }

            assertTrue(all)
            assertEquals(
                "the read state and the star unchanged, only the answer of the one and the forward " +
                    "of the other may move: anything else is the delta rewriting a row it was not asked to",
                listOf(
                    before[0] + mapOf("answered" to "1"),
                    before[1] + mapOf("forwarded" to "1"),
                ),
                cache.rows(account),
            )
        }
    }

    // --- the read a cached body is reconciled against -------------------------------------------

    @Test fun `the row a cached body is reconciled against reads each mark from its own column`() {
        JdbcMailCache().use { cache ->
            val answered = imapMessage(answered = true, forwarded = false)
            val forwarded = answered.copy(uid = 8L, answered = false, forwarded = true, seen = false, flagged = true)
            cache.upsert(
                listOf(
                    answered.toEntity(account, inbox, 42L, preview = null),
                    forwarded.toEntity(account, inbox, 42L, preview = null),
                ),
            )

            assertEquals(
                "the read behind `readAndStarOf` no longer hands back the row's own marks: a cached " +
                    "body reconciled against it loses the `\$answered` it carried",
                listOf(
                    ReadAndStar(seen = true, flagged = false, answered = true, forwarded = false),
                    ReadAndStar(seen = false, flagged = true, answered = false, forwarded = true),
                ),
                listOf(
                    cache.readAndStarOf(account, ImapMailService.emailId(account, inbox, 7L)),
                    cache.readAndStarOf(account, ImapMailService.emailId(account, inbox, 8L)),
                ),
            )
        }
    }
}
