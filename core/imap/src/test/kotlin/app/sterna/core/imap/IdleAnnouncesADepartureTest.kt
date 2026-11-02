package app.sterna.core.imap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A message deleted from another client reaches an IDLE as `* n EXPUNGE`, and that must wake the
 * watcher like an arrival does, or the row stays on screen until a pull (#278).
 */
class IdleAnnouncesADepartureTest {

    private fun idleAnswering(untagged: String): Boolean {
        var idleTag = ""
        return FakeImapServer { tag, line ->
            when {
                line.startsWith("IDLE") -> { idleTag = tag; "+ idling\r\n$untagged" }
                line == "DONE" -> "$idleTag OK idle terminated\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            val session = server.session()
            session.select("INBOX")
            val changed = session.idle(1_500)
            session.close()
            changed
        }
    }

    @Test(timeout = 60_000)
    fun `an EXPUNGE ends the IDLE as a change`() {
        assertTrue(
            "idle() sat out its timeout on a `* 3 EXPUNGE`: a message deleted elsewhere does not wake the watcher",
            idleAnswering("* 3 EXPUNGE\r\n"),
        )
    }

    @Test(timeout = 60_000)
    fun `an unsolicited OK is not a change`() {
        assertFalse(
            "idle() took a neutral status line for a change of the mailbox",
            idleAnswering("* OK Still here\r\n"),
        )
    }

    /** The decision, pinned: our own STORE of \Seen comes back as FETCH, so counting it would make every read wake a pass. */
    @Test(timeout = 60_000)
    fun `a FETCH alone does not`() {
        assertFalse(
            "idle() took a flag change for a change of the mailbox",
            idleAnswering("* 3 FETCH (FLAGS (\\Seen))\r\n"),
        )
    }
}
