package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WHAT THE BODY FETCHES ACTUALLY PUT ON THE WIRE — the three of them, line for line.
 *
 * ⛔ `BODY.PEEK[…]`, never `BODY[…]`. A bare `BODY` fetch sets `\Seen` on the server (RFC 3501
 * §6.4.5): the message leaves the unread list, on every device the account is open on, and no
 * command exists that undoes "the user read this". On the preview path the read is made BY A
 * NOTIFICATION, so the mail would be marked read at the moment it is announced.
 *
 * Nothing held that word before this file. The two existing fetches were written with `.PEEK` and
 * stayed that way by luck: the only mention of it in the test tree was a COMMENT, and the fake
 * server answers `line.startsWith("UID FETCH")` without ever looking at the rest of the command.
 * Dropping `.PEEK` from either of them left the whole suite green.
 *
 * ⛔ Whole lines, compared with assertEquals — never `contains("PEEK")`. A containment check is
 * blind to every mutation that makes the line LONGER, which is most of them: it cannot see a second
 * item added to the fetch, a `<0.8192>` dropped (the line only gets shorter... and a partial that
 * became `<0.20000000>`), nor a `BODY.PEEK[]` fetching the whole message next to the section.
 *
 * The list is the WHOLE conversation, so a second round trip that nobody asked for shows up as an
 * extra line rather than passing unnoticed.
 */
class BodyPeekOnTheWireTest {

    /** A FETCH answer carrying [body] for [item] (`BODY[1]<0>`, `BODY[2]`, `BODY[]`). */
    private fun bodyResponse(tag: String, item: String, body: String): String =
        "* 1 FETCH (UID 7 $item {${body.length}}\r\n$body)\r\n$tag OK fetched\r\n"

    /** Run [act] against a server that answers every UID FETCH with [answer]; give back what the
     *  session actually said, LOGIN excluded. */
    private fun conversation(answer: (String) -> String, act: (ImapSession) -> Unit): List<String> {
        val server = FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 1)
                line.startsWith("UID FETCH") -> answer(tag)
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

    @Test fun `a preview fetch peeks, and asks for the first 8 KiB only`() {
        val issued = conversation({ tag -> bodyResponse(tag, "BODY[1]<0>", "Hello") }) { session ->
            session.fetchSectionPartial(7L, "1", IMAP_PREVIEW_FETCH_BYTES)
        }

        assertEquals(
            listOf("SELECT \"INBOX\"", "UID FETCH 7 (BODY.PEEK[1]<0.8192>)", "LOGOUT"),
            issued,
        )
    }

    @Test fun `an attachment fetch peeks`() {
        val issued = conversation({ tag -> bodyResponse(tag, "BODY[2]", "data") }) { session ->
            session.fetchSection(7L, "2")
        }

        assertEquals(listOf("SELECT \"INBOX\"", "UID FETCH 7 (BODY.PEEK[2])", "LOGOUT"), issued)
    }

    @Test fun `a source fetch peeks`() {
        val issued = conversation({ tag -> bodyResponse(tag, "BODY[]", "Subject: hi\r\n\r\nbody") }) { session ->
            session.fetchSource(7L)
        }

        assertEquals(listOf("SELECT \"INBOX\"", "UID FETCH 7 (BODY.PEEK[])", "LOGOUT"), issued)
    }

    /**
     * ⭐ THE UNPROVEN CLAIM, proven: a PARTIAL fetch has never been made by this app, and the code
     * carries a warning that `ImapParser` stops on parentheses — which is why a `BODY.PEEK[HEADER.
     * FIELDS (…)]` would come back as an empty string with no error at all. `<0.8192>` has no
     * parentheses, and the answer names the item `BODY[1]<0>`; this asserts that the reader really
     * does recognise that atom and hand back the octets, rather than silently returning nothing.
     */
    @Test fun `the partial answer is recognised, and its octets come back`() {
        var fetched: String? = null
        conversation({ tag -> bodyResponse(tag, "BODY[1]<0>", "Bonjour a tous") }) { session ->
            fetched = session.fetchSectionPartial(7L, "1", IMAP_PREVIEW_FETCH_BYTES)
        }

        assertEquals("Bonjour a tous", fetched)
    }

    /**
     * ⛔ "At most 8 KiB" must be TRUE, not merely REQUESTED. `<0.8192>` is a request; nothing above
     * enforces it — a literal is read up to 32 MiB and the body item is taken by prefix, so a server
     * that ignores the partial (or answers `BODY[]`) would otherwise have a whole 20 MB message
     * decoded and shaped before anything threw it away, inside a foreground service.
     */
    @Test fun `an over-long answer is cut to the bound the client asked for`() {
        val huge = "A".repeat(20_000)
        var fetched: String? = null
        conversation({ tag -> bodyResponse(tag, "BODY[1]<0>", huge) }) { session ->
            fetched = session.fetchSectionPartial(7L, "1", IMAP_PREVIEW_FETCH_BYTES)
        }

        assertEquals(IMAP_PREVIEW_FETCH_BYTES, fetched!!.length)
        assertEquals(huge.take(IMAP_PREVIEW_FETCH_BYTES), fetched)
    }

    /** A truncated part is answered with fewer octets than were asked for, and that is not an
     *  error: what came back is what there is. */
    @Test fun `a part shorter than the bound comes back whole`() {
        var fetched: String? = null
        conversation({ tag -> bodyResponse(tag, "BODY[1]<0>", "hi") }) { session ->
            fetched = session.fetchSectionPartial(7L, "1", IMAP_PREVIEW_FETCH_BYTES)
        }

        assertEquals("hi", fetched)
    }

    /**
     * ⛔ A server that answers something else must yield NOTHING. The item list opens with `UID 7`,
     * and the reader used to take the atom at index 0 when it found no body item — so this answer
     * produced the string "UID", which on this path would be posted to a lock screen as the opening
     * line of somebody's mail.
     */
    @Test fun `a FETCH with no body item yields nothing at all`() {
        var fetched: String? = null
        conversation({ tag -> "* 1 FETCH (UID 7 FLAGS (\\Seen))\r\n$tag OK fetched\r\n" }) { session ->
            fetched = session.fetchSectionPartial(7L, "1", IMAP_PREVIEW_FETCH_BYTES)
        }

        assertEquals("", fetched)
    }

    /** Same refusal for the two older fetches, which share the one reader. */
    @Test fun `an attachment answer with no body item yields nothing either`() {
        var section: String? = null
        var source: String? = null
        conversation({ tag -> "* 1 FETCH (UID 7 FLAGS (\\Seen))\r\n$tag OK fetched\r\n" }) { session ->
            section = session.fetchSection(7L, "2")
            source = session.fetchSource(7L)
        }

        assertEquals("", section)
        assertEquals("", source)
    }
}
