package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WHAT `fetchIsDraft` PUTS ON THE WIRE, and what it makes of the answer — driven against a
 * scripted server, so the three answers (yes, no, and "I could not read that") are told apart.
 */
class DraftFlagOnTheWireTest {

    /** Run against a server answering every `UID FETCH` with [answer]; give back what was said and
     *  what the session read. */
    private fun conversation(answer: (String) -> String): Pair<List<String>, Boolean?> {
        var read: Boolean? = null
        val server = FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 1)
                line.startsWith("UID FETCH") -> answer(tag)
                else -> ok(tag)
            }
        }
        val issued = server.use {
            it.session().use { session ->
                session.select("INBOX")
                read = session.fetchIsDraft(7L)
            }
            it.issued()
        }
        return issued to read
    }

    /** Every session opens by asking whether the server knows the RFC 2971 ID command (#173); this
     *  fixture answers without it, so nothing is named. */
    private val expectedCommands =
        listOf("CAPABILITY", "SELECT \"INBOX\"", "UID FETCH 7 (FLAGS)", "LOGOUT")

    @Test fun `a message flagged Draft reads as one`() {
        val (issued, read) = conversation { tag ->
            "* 1 FETCH (UID 7 FLAGS (\\Seen \\Draft))\r\n$tag OK fetched\r\n"
        }

        assertEquals(expectedCommands, issued)
        assertEquals(true, read)
    }

    @Test fun `the flags asked for are the ONLY thing asked for`() {
        // A body item slipped into this fetch would have to be `BODY.PEEK[]`; the plain `BODY[]` a
        // widened fetch is written with sets \Seen on mail that was only ever looked at.
        val (issued, _) = conversation { tag ->
            "* 1 FETCH (UID 7 FLAGS (\\Draft))\r\n$tag OK fetched\r\n"
        }

        val fetches = issued.filter { it.startsWith("UID FETCH") }
        assertEquals(listOf("UID FETCH 7 (FLAGS)"), fetches)
        assertFalse("no body may be pulled to read a flag: $fetches", fetches.any { it.contains("BODY") })
    }

    @Test fun `a received message that merely sits in the drafts folder is not a draft`() {
        val (issued, read) = conversation { tag ->
            "* 1 FETCH (UID 7 FLAGS (\\Seen))\r\n$tag OK fetched\r\n"
        }

        assertEquals(expectedCommands, issued)
        assertEquals(false, read)
    }

    @Test fun `flags are case-insensitive, as RFC 3501 says`() {
        listOf("\\draft", "\\DRAFT", "\\DrAfT").forEach { spelling ->
            val (_, read) = conversation { tag ->
                "* 1 FETCH (UID 7 FLAGS (\\Seen $spelling))\r\n$tag OK fetched\r\n"
            }

            assertEquals("«$spelling» is the draft flag", true, read)
        }
    }

    @Test fun `a keyword that merely looks like the flag is not the flag`() {
        // Server-defined keywords share the FLAGS list with the system flags; matching on the word
        // rather than on the whole atom would hand a licence to destroy to any of them.
        listOf("Draft", "\$Draft", "\\Drafts", "NotADraft", "\\Draft2").forEach { keyword ->
            val (_, read) = conversation { tag ->
                "* 1 FETCH (UID 7 FLAGS (\\Seen $keyword))\r\n$tag OK fetched\r\n"
            }

            assertEquals("«$keyword» is not the \\Draft flag", false, read)
        }
    }

    @Test fun `an empty flag list is read, and reads as no`() {
        val (_, read) = conversation { tag -> "* 1 FETCH (UID 7 FLAGS ())\r\n$tag OK fetched\r\n" }

        assertEquals(false, read)
    }

    @Test fun `the line read is the one bearing the uid asked for`() {
        // A server pushes `* n FETCH (FLAGS (…))` unasked when another client retags a message, and
        // another client in this very folder is the whole premise of the defect being fixed.
        val (_, read) = conversation { tag ->
            "* 2 FETCH (UID 9 FLAGS (\\Draft))\r\n* 1 FETCH (UID 7 FLAGS (\\Seen))\r\n$tag OK fetched\r\n"
        }

        assertEquals("the parasite line speaks for uid 9, not for the one asked about", false, read)
    }

    @Test fun `flags for another message alone prove nothing about this one`() {
        val (_, read) = conversation { tag ->
            "* 2 FETCH (UID 9 FLAGS (\\Draft))\r\n$tag OK fetched\r\n"
        }

        assertNull(read)
    }

    @Test fun `a FETCH line whose items are not a list at all is unknown, never a no`() {
        val (_, read) = conversation { tag -> "* 1 FETCH NIL\r\n$tag OK fetched\r\n" }

        assertNull(read)
    }

    @Test fun `an answer with no FLAGS item is unknown, never a no`() {
        // The three mute shapes below must not be told apart from each other, but they must be
        // told apart from a read `false`: a caller may act on "not a draft", never on silence.
        val noItem = conversation { tag ->
            "* 1 FETCH (UID 7 INTERNALDATE \"01-Jun-2026 10:00:00 +0000\")\r\n$tag OK fetched\r\n"
        }
        assertNull(noItem.second)
        assertEquals(expectedCommands, noItem.first)

        val noFetchLine = conversation { tag -> "$tag OK fetched\r\n" }
        assertNull(noFetchLine.second)

        val flagsNotAList = conversation { tag -> "* 1 FETCH (UID 7 FLAGS NIL)\r\n$tag OK fetched\r\n" }
        assertNull(flagsNotAList.second)

        val emptyFetch = conversation { tag -> "* 1 FETCH ()\r\n$tag OK fetched\r\n" }
        assertNull(emptyFetch.second)
    }
}
