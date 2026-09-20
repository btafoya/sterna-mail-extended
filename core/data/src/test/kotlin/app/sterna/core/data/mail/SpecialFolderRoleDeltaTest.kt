package app.sterna.core.data.mail

import app.sterna.core.data.db.MailboxIdRole
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rewrite a change of choice owes the folder cache before any network refresh (#227), RUN.
 * Nothing here recomputes the rule: every expected row is written out, mark included.
 *
 * The cached `role` is the effective role and there is no column for the automatic one, which is
 * what makes this a delta rather than a recount, and what the unpin cases below pin the price of.
 */
class SpecialFolderRoleDeltaTest {

    /** The delta on an IMAP account, where a folder id is its path. */
    private fun imapDelta(
        rows: List<MailboxIdRole>,
        role: String,
        previousChoice: String? = null,
        choice: String?,
    ): List<MailboxIdRole> = specialFolderRoleDelta(rows, role, previousChoice, choice, idsArePaths = true)

    /** And on a JMAP account, where an id is an opaque server string nobody may read a path out of. */
    private fun jmapDelta(
        rows: List<MailboxIdRole>,
        role: String,
        previousChoice: String? = null,
        choice: String?,
    ): List<MailboxIdRole> = specialFolderRoleDelta(rows, role, previousChoice, choice, idsArePaths = false)

    /** A server that named `Trash` its bin, beside a folder of the user's own. */
    private val listing = listOf(
        MailboxIdRole("Corbeille", null),
        MailboxIdRole("INBOX", "inbox"),
        MailboxIdRole("Trash", "trash"),
    )

    @Test fun `the pinned folder takes the role`() {
        assertEquals(
            listOf(MailboxIdRole("Trash", "~trash"), MailboxIdRole("Corbeille", "trash")),
            imapDelta(listing, "trash", previousChoice = null, choice = "Corbeille"),
        )
    }

    /**
     * The folder that held the role is dispossessed in the same delta. Two holders of one role are
     * two bins in the drawer and a delete aiming at whichever row came back first.
     */
    @Test fun `the folder that held the role loses it in the same breath`() {
        val delta = imapDelta(listing, "trash", previousChoice = null, choice = "Corbeille")
        assertEquals(listOf("Trash"), delta.filter { it.role != "trash" }.map { it.id })
    }

    /**
     * And it is written BEFORE the new holder: the two writes are separate statements, so the
     * other order leaves a window where the cache answers two folders for one role.
     */
    @Test fun `the folder taking the role is written last`() {
        val delta = imapDelta(listing, "trash", previousChoice = null, choice = "Corbeille")
        assertEquals(MailboxIdRole("Corbeille", "trash"), delta.last())
    }

    /**
     * A dispossessed holder takes the mark of a lost claim, exactly like the loser of a listing's
     * own election: that is what keeps the folder the server calls the bin out of every search
     * result, and what lets it take the role back when the choice is undone.
     *
     * Only the roles a search skips are marked; the rest read as no role at all
     * ([UNELECTED_ROLE_MARK]).
     */
    @Test fun `a dispossessed holder of an unmarked role is left with no role`() {
        assertEquals(
            listOf(MailboxIdRole("Sent", null), MailboxIdRole("Envoyes", "sent")),
            imapDelta(
                listOf(MailboxIdRole("Envoyes", null), MailboxIdRole("Sent", "sent")),
                "sent",
                previousChoice = null,
                choice = "Envoyes",
            ),
        )
    }

    @Test fun `pinning the folder that already carries the role writes nothing`() {
        assertEquals(
            emptyList<MailboxIdRole>(),
            imapDelta(listing, "trash", previousChoice = null, choice = "Trash"),
        )
    }

    /**
     * The guards are [honouredSpecialFolders]': no role may be moved onto the inbox, or the
     * account is left with no folder answering `MailRepository.cachedInboxMailboxId`, its incoming
     * mail out of every search and a delete aimed at it.
     *
     * Recognised as the listings recognise it, and never by the elected role alone: a server that
     * announces `\Archive` on INBOX takes it out of the role election, so its cached row reads
     * `archive`, and a guard reading that row's role would hand it `trash`.
     */
    @Test fun `the inbox is refused as a target`() {
        assertEquals(
            emptyList<MailboxIdRole>(),
            imapDelta(listing, "trash", previousChoice = null, choice = "INBOX"),
        )
    }

    /** On IMAP the id IS the path, so RFC 3501 §5.1's reserved path answers even when the
     *  listing elected no inbox at all. */
    @Test fun `an inbox the listing failed to elect is refused all the same`() {
        assertEquals(
            emptyList<MailboxIdRole>(),
            imapDelta(
                listOf(MailboxIdRole("INBOX", "archive"), MailboxIdRole("Trash", "trash")),
                "trash",
                previousChoice = null,
                choice = "INBOX",
            ),
        )
    }

    /** The other side of that guard: a JMAP server whose ids happen to be readable may hand out one
     *  that spells INBOX, and that mailbox is an ordinary folder, designatable like any other. */
    @Test fun `a jmap mailbox whose opaque id spells INBOX stays designatable`() {
        assertEquals(
            listOf(MailboxIdRole("Trash", "~trash"), MailboxIdRole("INBOX", "trash")),
            jmapDelta(
                listOf(MailboxIdRole("INBOX", null), MailboxIdRole("Trash", "trash")),
                "trash",
                previousChoice = null,
                choice = "INBOX",
            ),
        )
    }

    @Test fun `a folder the cache does not carry rewrites nothing`() {
        assertEquals(
            emptyList<MailboxIdRole>(),
            imapDelta(listing, "trash", previousChoice = null, choice = "Poubelle"),
        )
    }

    @Test fun `a role outside the five special ones rewrites nothing`() {
        assertEquals(
            emptyList<MailboxIdRole>(),
            imapDelta(listing, "all", previousChoice = null, choice = "Corbeille"),
        )
    }

    // ---- changing one's mind, the gesture the screen makes twice

    /**
     * Designating a second folder takes the role off the first, and the first is left with NO
     * role, never with the mark. The mark says "this folder claimed the role and lost the
     * election"; an ordinary mail folder the user pointed at for a day claimed nothing, and a
     * folder marked `~trash` is out of every search result, offline, with nothing on screen
     * saying so. Which folder was designated is read off the record, not guessed from the cache.
     */
    @Test fun `changing one's mind leaves the folder that was designated out of it`() {
        assertEquals(
            listOf(MailboxIdRole("Projets", null), MailboxIdRole("Corbeille", "trash")),
            imapDelta(
                listOf(
                    MailboxIdRole("Corbeille", null),
                    MailboxIdRole("INBOX", "inbox"),
                    MailboxIdRole("Projets", "trash"),
                ),
                "trash",
                previousChoice = "Projets",
                choice = "Corbeille",
            ),
        )
    }

    /** And the folder the SERVER elected still takes the mark when it is the one dispossessed:
     *  that one did claim the role, and search has skipped it since its own listing. */
    @Test fun `the server's own holder is still marked when a first choice dispossesses it`() {
        assertEquals(
            listOf(MailboxIdRole("Trash", "~trash"), MailboxIdRole("Corbeille", "trash")),
            imapDelta(listing, "trash", previousChoice = null, choice = "Corbeille"),
        )
    }

    // ---- unpinning

    /** The marked folder takes its claim back, and the unpinned one is written first, so no
     *  reader sees two bins in between. */
    @Test fun `unpinning gives the role back to the folder that claimed and lost it`() {
        assertEquals(
            listOf(MailboxIdRole("Corbeille", null), MailboxIdRole("Trash", "trash")),
            imapDelta(
                listOf(
                    MailboxIdRole("Corbeille", "trash"),
                    MailboxIdRole("INBOX", "inbox"),
                    MailboxIdRole("Trash", "~trash"),
                ),
                "trash",
                previousChoice = "Corbeille",
                choice = null,
            ),
        )
    }

    /**
     * The price of having no column for the automatic role: `sent`, `drafts` and `archive` are
     * never marked, so nothing in the cache says which folder the server declared. The unpinned
     * folder drops its role and the account waits for the next refresh. No mail is filed anywhere
     * odd meanwhile: a send and a delete resolve their folder through the server.
     */
    @Test fun `unpinning an unmarked role leaves nobody carrying it until the next refresh`() {
        assertEquals(
            listOf(MailboxIdRole("Envoyes", null)),
            imapDelta(
                listOf(MailboxIdRole("Envoyes", "sent"), MailboxIdRole("Sent", null)),
                "sent",
                previousChoice = "Envoyes",
                choice = null,
            ),
        )
    }

    /**
     * Unpinning strips the folder the choice NAMED, never whoever currently answers the role: a
     * choice the listing could not honour (its folder gone from the server, unsubscribed, a
     * listing that failed) leaves the role with its automatic holder, and stripping that one would
     * leave the account with no bin at all until the next refresh.
     */
    @Test fun `unpinning a choice the cache never honoured strips nobody`() {
        assertEquals(
            emptyList<MailboxIdRole>(),
            imapDelta(listing, "trash", previousChoice = "Poubelle", choice = null),
        )
    }

    /**
     * The claim comes back to an ordinary folder and never to the inbox, the guard a pin holds
     * through [honouredSpecialFolders]. A server that announces `\Trash` on INBOX takes it out of
     * the role election, so its row reads the lost claim and is the row this branch would re-elect:
     * under `trash` the inbox drops out of every search result and
     * `MailRepository.cachedInboxMailboxId` answers nothing, so unarchiving moves nothing at all.
     * The folder the choice named is released all the same.
     */
    @Test fun `unpinning never hands the role back to the inbox`() {
        assertEquals(
            listOf(MailboxIdRole("Corbeille", null)),
            imapDelta(
                listOf(MailboxIdRole("Corbeille", "trash"), MailboxIdRole("INBOX", "~trash")),
                "trash",
                previousChoice = "Corbeille",
                choice = null,
            ),
        )
    }

    /** The other side of that guard, as on a pin: a JMAP id is opaque, so a mailbox whose id spells
     *  INBOX is an ordinary folder and takes its claim back like any other. */
    @Test fun `a jmap mailbox whose opaque id spells INBOX takes its claim back`() {
        assertEquals(
            listOf(MailboxIdRole("Corbeille", null), MailboxIdRole("INBOX", "trash")),
            jmapDelta(
                listOf(MailboxIdRole("Corbeille", "trash"), MailboxIdRole("INBOX", "~trash")),
                "trash",
                previousChoice = "Corbeille",
                choice = null,
            ),
        )
    }

    @Test fun `unpinning a role nobody had pinned writes nothing`() {
        assertEquals(
            emptyList<MailboxIdRole>(),
            imapDelta(listing, "trash", previousChoice = null, choice = null),
        )
    }
}
