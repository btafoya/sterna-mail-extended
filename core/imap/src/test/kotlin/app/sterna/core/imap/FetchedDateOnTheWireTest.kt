package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a fetched [ImapMessage] is dated by, driven against a scripted server: which envelope slot
 * the date comes from, and what stands in when that slot holds nothing legal.
 */
class FetchedDateOnTheWireTest {

    /** The date of one message fetched over a real socket, its `Date:` and its delivery stamp
     *  spelled out. [envelopeDate] goes in raw, so `NIL` can be passed as well as a quoted string. */
    private fun dateOf(envelopeDate: String, internalDate: String? = null): Long? {
        val stamp = internalDate?.let { "INTERNALDATE \"$it\" " } ?: ""
        val response = { tag: String ->
            "* 1 FETCH (UID 7 FLAGS (\\Seen) $stamp" +
                "ENVELOPE ($envelopeDate \"Hi\" ((\"Alex Rivera\" NIL \"alex.rivera\" \"masto.top\")) " +
                "NIL NIL ((\"Team\" NIL \"team\" \"masto.top\")) NIL NIL NIL \"<7@masto.top>\") " +
                "BODYSTRUCTURE (\"text\" \"plain\" (\"charset\" \"utf-8\") NIL NIL \"7bit\" 12 1))\r\n" +
                "$tag OK fetched\r\n"
        }
        return FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 1)
                line.startsWith("UID FETCH") -> response(tag)
                else -> ok(tag)
            }
        }.use { server ->
            server.session().use { session ->
                session.select("INBOX")
                session.fetchByUid(7L)?.dateMillis
            }
        }
    }

    /** 2026-09-01T19:17:27Z, written by the sender. */
    private val sent = 1_788_290_247_000L

    /** 2026-09-02T08:30:00Z, stamped by the server on delivery, a day after [sent]. */
    private val delivered = 1_788_337_800_000L

    /**
     * The envelope's first slot, and a day padded on two characters, which RFC 5322 §3.3 allows
     * and almost nobody writes.
     */
    @Test fun `a legal but unusual Date header is read, off the envelope's first slot`() {
        assertEquals(sent, dateOf("\"Tue,  1 Sep 2026 19:17:27 +0000\"", "02-Sep-2026 08:30:00 +0000"))
    }

    @Test fun `the sender's date is kept when the server's stamp sits beside it`() {
        assertEquals(sent, dateOf("\"Tue, 1 Sep 2026 19:17:27 +0000\"", "02-Sep-2026 08:30:00 +0000"))
    }

    @Test fun `no Date header at all falls back to the delivery stamp`() {
        assertEquals(delivered, dateOf("NIL", "02-Sep-2026 08:30:00 +0000"))
    }

    @Test fun `a Date header no grammar can read falls back to the delivery stamp`() {
        assertEquals(delivered, dateOf("\"yesterday\"", "02-Sep-2026 08:30:00 +0000"))
    }

    @Test fun `the sender's date is read when no stamp comes with it`() {
        assertEquals(sent, dateOf("\"Tue, 1 Sep 2026 19:17:27 +0000\""))
    }

    /** A sender whose clock was never set dates the header before the epoch, which the row
     *  downstream turns back into no date at all, so it counts as none and the stamp takes over. */
    @Test fun `a Date at or before the epoch falls back to the delivery stamp`() {
        assertEquals(delivered, dateOf("\"Wed, 31 Dec 1969 23:00:00 +0000\"", "02-Sep-2026 08:30:00 +0000"))
        assertEquals(delivered, dateOf("\"Thu, 1 Jan 1970 00:00:00 +0000\"", "02-Sep-2026 08:30:00 +0000"))
    }

    /** A date nobody can vouch for wins all the same: the list shows what the sender wrote, and
     *  the stamp that cannot be forged is read elsewhere, for the keys that need it. */
    @Test fun `a Date in the future still beats the stamp`() {
        assertEquals(4_102_444_800_000L, dateOf("\"Fri, 1 Jan 2100 00:00:00 +0000\"", "02-Sep-2026 08:30:00 +0000"))
    }

    /** Neither date readable is 0, not the current clock: a made-up date sorts the row to the top
     *  of the list and moves it again at every sync. */
    @Test fun `an unreadable Date and no stamp at all is zero`() {
        assertEquals(0L, dateOf("\"yesterday\""))
    }

    @Test fun `an unreadable Date and an unreadable stamp is zero`() {
        assertEquals(0L, dateOf("NIL", "whenever"))
    }
}
