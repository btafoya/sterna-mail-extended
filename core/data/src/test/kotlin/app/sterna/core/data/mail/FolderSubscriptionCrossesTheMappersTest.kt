package app.sterna.core.data.mail

import app.sterna.core.data.db.MailboxEntity
import app.sterna.core.imap.ImapFolder
import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The folder's subscription, EXECUTED across the three crossings it has to survive to reach the
 * drawer (Codeberg #174): IMAP listing → cache row ([imapMailboxEntity]), JMAP model → cache row
 * ([toEntity]), and cache row → UI model ([toMailbox]).
 *
 * Every crossing is played with BOTH values. A test that only ever passes `true` stays green on
 * `isSubscribed = true` written into the mapper as a literal — the exact slip that makes a later
 * "hide unsubscribed folders" setting a no-op, with nothing red anywhere to say so. So each
 * assertion below pins the VALUE the shipped function returns, for `false` and for `true`, and
 * inverting what the mapper copies makes it fail.
 *
 * The defaults are pinned too, and they are load-bearing rather than tidy. `true` is what the
 * app must believe when nobody told it anything: an IMAP server whose `LSUB` was not read yet, and
 * above all a JMAP server that does not send `isSubscribed` at all — `JmapClient` decodes with
 * `ignoreUnknownKeys`, but a missing property with no default throws, and the throw comes out of
 * `Mailbox/get` as an empty drawer and an unusable account.
 */
class FolderSubscriptionCrossesTheMappersTest {

    private fun imapFolder(subscribed: Boolean) = ImapFolder(
        name = "Projekte",
        path = "Shared/team/Projekte",
        role = null,
        delimiter = "/",
        isSubscribed = subscribed,
    )

    private fun jmapMailbox(subscribed: Boolean) = Mailbox(
        id = "mb2",
        name = "Projekte",
        role = null,
        sortOrder = 6,
        totalEmails = 4,
        unreadEmails = 1,
        isSubscribed = subscribed,
    )

    private fun row(subscribed: Boolean) = MailboxEntity(
        accountId = "acc-1",
        id = "mb2",
        name = "Projekte",
        role = null,
        sortOrder = 6001,
        totalEmails = 4,
        unreadEmails = 1,
        isSubscribed = subscribed,
    )

    // --- IMAP listing → cache row ------------------------------------------------------------------

    @Test fun anUnsubscribedImapFolderIsCachedUnsubscribed() {
        assertEquals(false, imapMailboxEntity("acc-1", imapFolder(subscribed = false), index = 3).isSubscribed)
    }

    @Test fun aSubscribedImapFolderIsCachedSubscribed() {
        assertEquals(true, imapMailboxEntity("acc-1", imapFolder(subscribed = true), index = 3).isSubscribed)
    }

    /** The rest of the row is untouched: carrying the subscription is not a rename or a reorder. */
    @Test fun carryingTheSubscriptionChangesNothingElseOnTheRow() {
        val subscribed = imapMailboxEntity("acc-1", imapFolder(subscribed = true), index = 3)
        val not = imapMailboxEntity("acc-1", imapFolder(subscribed = false), index = 3)
        assertEquals(subscribed.copy(isSubscribed = false), not)
        assertEquals("Shared/team/Projekte", not.id)
        assertEquals("Projekte", not.name)
        assertEquals(null, not.role)
        assertEquals(6003, not.sortOrder)
    }

    // --- JMAP model → cache row, and back to the UI model -----------------------------------------

    @Test fun anUnsubscribedJmapMailboxIsCachedUnsubscribed() {
        assertEquals(false, jmapMailbox(subscribed = false).toEntity("acc-1").isSubscribed)
    }

    @Test fun aSubscribedJmapMailboxIsCachedSubscribed() {
        assertEquals(true, jmapMailbox(subscribed = true).toEntity("acc-1").isSubscribed)
    }

    @Test fun anUnsubscribedRowReachesTheUiModelUnsubscribed() {
        assertEquals(false, row(subscribed = false).toMailbox().isSubscribed)
    }

    @Test fun aSubscribedRowReachesTheUiModelSubscribed() {
        assertEquals(true, row(subscribed = true).toMailbox().isSubscribed)
    }

    /** Round trip, both values: nothing in the pair of mappers flips or forgets it. */
    @Test fun theSubscriptionSurvivesTheRoundTripBothWays() {
        assertEquals(false, jmapMailbox(subscribed = false).toEntity("acc-1").toMailbox().isSubscribed)
        assertEquals(true, jmapMailbox(subscribed = true).toEntity("acc-1").toMailbox().isSubscribed)
    }

    // --- what "nobody said" means ------------------------------------------------------------------

    /**
     * Unknown is SUBSCRIBED, on all three types. This volet reads the subscription nowhere yet:
     * every folder the app handles right now takes these defaults, so a default of `false` would
     * mean "hide everything" the day the filter lands.
     */
    @Test fun aFolderNobodySaidAnythingAboutIsSubscribed() {
        assertTrue(ImapFolder(name = "Projekte", path = "Projekte", role = null, delimiter = "/").isSubscribed)
        assertTrue(Mailbox(id = "mb2", name = "Projekte").isSubscribed)
        assertTrue(
            MailboxEntity(
                accountId = "acc-1",
                id = "mb2",
                name = "Projekte",
                role = null,
                sortOrder = 6001,
                totalEmails = 4,
                unreadEmails = 1,
            ).isSubscribed,
        )
    }
}
