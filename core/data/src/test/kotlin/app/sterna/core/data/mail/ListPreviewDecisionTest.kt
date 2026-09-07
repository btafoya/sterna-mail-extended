package app.sterna.core.data.mail

import app.sterna.core.data.settings.NotificationContent
import app.sterna.core.data.settings.PreviewLines
import app.sterna.core.imap.ImapMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two decisions of the IMAP list preview, EXECUTED (#187): whether to read opening lines at
 * all, and whether the value read survives into the cache row.
 *
 * Neither test recomputes the rule it is checking. The couples below are written out by hand,
 * one line each, precisely so that inverting the shipped condition turns them red — a test that
 * derives its expectation from the same expression is a copy of the decision and stays green
 * whatever the decision says.
 */
class ListPreviewDecisionTest {

    /**
     * THE WHOLE PRODUCT OF BOTH ENUMERATIONS, sixteen couples, each stated. Two claims live in
     * it and both are load-bearing:
     *
     *  - a list set to show a preview wants one, whatever notifications are set to. That is the
     *    reported defect: the setting had no effect on an IMAP account at all;
     *  - a list set to NONE still wants one when notifications are set to BODY_PREVIEW. The extract
     *    a notification carries is gathered on ARRIVAL, so a snoozed message re-announced later
     *    from the bare row would lose it — the row is all that wake-up has.
     *
     * The `false` row is therefore exactly one: no preview in the list, and no preview in the
     * notification either.
     */
    @Test fun `the list wants an opening line for these settings, and only these`() {
        val wanted = mutableListOf<Pair<PreviewLines, NotificationContent>>()
        PreviewLines.entries.forEach { lines ->
            NotificationContent.entries.forEach { content ->
                if (previewWantedInList(lines, content)) wanted += lines to content
            }
        }

        assertEquals(
            "the couples that must read a body, written out rather than derived: a NONE/NONE " +
                "account reads nothing, and every other combination reads",
            listOf(
                PreviewLines.NONE to NotificationContent.BODY_PREVIEW,
                PreviewLines.ONE to NotificationContent.BODY_PREVIEW,
                PreviewLines.ONE to NotificationContent.SENDER_AND_SUBJECT,
                PreviewLines.ONE to NotificationContent.SENDER_ONLY,
                PreviewLines.ONE to NotificationContent.NONE,
                PreviewLines.THREE to NotificationContent.BODY_PREVIEW,
                PreviewLines.THREE to NotificationContent.SENDER_AND_SUBJECT,
                PreviewLines.THREE to NotificationContent.SENDER_ONLY,
                PreviewLines.THREE to NotificationContent.NONE,
                PreviewLines.FIVE to NotificationContent.BODY_PREVIEW,
                PreviewLines.FIVE to NotificationContent.SENDER_AND_SUBJECT,
                PreviewLines.FIVE to NotificationContent.SENDER_ONLY,
                PreviewLines.FIVE to NotificationContent.NONE,
            ),
            wanted,
        )
    }

    /** And the ONE couple that reads nothing, stated on its own so the line cannot be lost in
     *  the list above: an account that shows no preview anywhere spends nothing on the radio. */
    @Test fun `an account that shows no preview anywhere reads no body`() {
        assertFalse(previewWantedInList(PreviewLines.NONE, NotificationContent.SENDER_AND_SUBJECT))
        assertFalse(previewWantedInList(PreviewLines.NONE, NotificationContent.SENDER_ONLY))
        assertFalse(previewWantedInList(PreviewLines.NONE, NotificationContent.NONE))
    }

    private fun message(uid: Long) = ImapMessage(
        uid = uid,
        subject = "Subject $uid",
        fromName = "Alex",
        fromEmail = "alex@example.org",
        to = emptyList(),
        dateMillis = 1_700_000_000_000L,
        seen = false,
        flagged = false,
        answered = false,
        hasAttachment = false,
        messageId = "<$uid@example.org>",
        inReplyTo = null,
    )

    /**
     * THE END OF THE ROAD, and the line issue #187 was: the value read for a message has to reach
     * `EmailEntity.preview`. This mapper is the IMAP side's own — the JMAP path does not go through
     * it — and it wrote a hard `null` here for all six of its call sites, so the list's "Preview"
     * setting could do nothing at all on an IMAP account whatever it was set to.
     */
    @Test fun `the opening line read for a message reaches the cache row`() {
        assertEquals(
            "Bonjour, l'été arrive",
            message(7L).toEntity("accA", "INBOX", 42L, preview = "Bonjour, l'été arrive").preview,
        )
    }

    /** And `null` stays `null`: the four read paths that cache no opening line say so, and the row
     *  they write must not invent one. */
    @Test fun `no opening line read means no opening line stored`() {
        assertNull(message(7L).toEntity("accA", "INBOX", 42L, preview = null).preview)
    }
}
