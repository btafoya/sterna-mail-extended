package app.sterna.push

import app.sterna.core.data.account.MailProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Codeberg #134 on IMAP: re-reading a folder never says what left it, so the remembered ids the
 * folder no longer shows are the departure candidates there. JMAP's delta already names its own.
 */
class ImapDepartureCandidatesTest {

    @Test fun `on IMAP a remembered id the folder no longer shows is a candidate`() {
        assertEquals(
            listOf("I2"),
            departureCandidates(
                MailProtocol.IMAP,
                departedIds = emptyList(),
                remembered = setOf("I1", "I2", "I3"),
                held = listOf("I1", "I3", "I4"),
            ),
        )
    }

    @Test fun `on IMAP a folder showing everything it remembered has no candidate`() {
        assertEquals(
            emptyList<String>(),
            departureCandidates(
                MailProtocol.IMAP,
                departedIds = emptyList(),
                remembered = setOf("I1", "I2"),
                held = listOf("I1", "I2"),
            ),
        )
    }

    @Test fun `on JMAP only the delta's departures are candidates`() {
        assertEquals(
            listOf("M9"),
            departureCandidates(
                MailProtocol.JMAP,
                departedIds = listOf("M9"),
                remembered = setOf("M1", "M2"),
                held = listOf("M1"),
            ),
        )
    }
}
