package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a folder NOBODY has decided anything about does on the first run, EXECUTED
 * ([defaultCollapsedFolderIds]). The rule lives in a function precisely so this file can run it:
 * in the drawer it would sit inside a Composable, which nothing in this module can run.
 *
 * ⛔ Every expectation is a set written out in full. Deriving it from the list under test would
 * re-implement the rule here, and the shipped condition could then be inverted with this file
 * still green.
 *
 * Every case below passes `rowsBadgeUnread = true` — the account whose folder rows CAN badge
 * unread, which is the only account this default was ever written for. The other answer has its
 * own case at the bottom of the file, and it is the whole of what V5 changed.
 */
class DefaultCollapsedFoldersTest {

    private fun mailbox(
        id: String,
        role: String? = null,
        name: String = id,
        parentId: String? = null,
        lostRoleClaim: Boolean = false,
    ) = Mailbox(id = id, name = name, role = role, parentId = parentId, lostRoleClaim = lostRoleClaim)

    @Test fun `a folder of the user's with something under it starts folded, a leaf does not`() {
        val folders = listOf(
            mailbox("Travail"),
            mailbox("Travail/Client A"),
            mailbox("Perso"),
        )
        assertEquals(
            "a user folder with children must start folded, and a folder with nothing under it " +
                "must not: the drawer draws no chevron on a leaf, so folding it would hide nothing " +
                "and could never be undone from the screen.",
            setOf("Travail"),
            defaultCollapsedFolderIds(folders, rowsBadgeUnread = true),
        )
    }

    /**
     * ⛔ The mutation this test exists for: `folderRank(role) == 6` in place of `role == null`.
     * `folderRank` answers 6 for every role outside the six the app translates, so a JMAP `all`
     * folder — a real role on a real standard folder — would start folded under that condition.
     * Every other case in this file stays green with it.
     */
    @Test fun `no folder carrying a role starts folded, not the six the app names and not 'all'`() {
        val folders = listOf(
            mailbox("in", role = "inbox"), mailbox("in/x"),
            mailbox("dr", role = "drafts"), mailbox("dr/x"),
            mailbox("se", role = "sent"), mailbox("se/x"),
            mailbox("tr", role = "trash"), mailbox("tr/x"),
            mailbox("ju", role = "junk"), mailbox("ju/x"),
            mailbox("ar", role = "archive"), mailbox("ar/x"),
            mailbox("al", role = "all"), mailbox("al/x"),
            mailbox("Travail"), mailbox("Travail/Client A"),
        )
        assertEquals(
            "a folder the server gave a role must never start folded, whatever its children — and " +
                "that includes the roles this app does not translate ('all' here), which " +
                "folderRank lumps in with the user's own folders. Only the user folder in this " +
                "list may come back. If Travail alone is missing, the rule stopped folding " +
                "anything at all.",
            setOf("Travail"),
            defaultCollapsedFolderIds(folders, rowsBadgeUnread = true),
        )
    }

    /**
     * ⭐ The child that carries the role, which no other case here has: the rule reads the role of
     * the folder being FOLDED, never of its children.
     *
     * ⛔ The mutation it exists for: counting as a parent only the folders whose child has no role
     * (`mailboxes.filter { it.role == null }.mapNotNullTo(…) { folderParentId(it, byId) }`). Every
     * other case in this file stays green under it, and `Perso` — whose only child is its
     * `Archive` — would stop folding on the first run.
     *
     * ⭐ It is also the witness of assumed consequence n°1, written in [defaultCollapsedFolderIds]'
     * KDoc: a role-carrying folder hanging under a folder of the user's IS off screen on the first
     * launch, hidden by its folded parent. That is the shipped decision, not an oversight — no
     * "never fold a parent that hides a role" exception — and the parent's unread badge
     * ([drawerUnreadCount]) is what keeps it honest. Change your mind about it and this case is
     * the one that has to be rewritten, deliberately.
     */
    @Test fun `a user folder whose only child carries a role still starts folded`() {
        val folders = listOf(
            mailbox("Perso"),
            mailbox("Perso/Archive", role = "archive"),
        )
        assertEquals(
            "Perso must fold on the first run even though the only thing under it is a folder the " +
                "server gave the archive role. The role guard applies to the folder being folded, " +
                "not to its children — and the assumed consequence is that Perso/Archive is off " +
                "screen until the user opens Perso, with Perso's badge carrying its unread.",
            setOf("Perso"),
            defaultCollapsedFolderIds(folders, rowsBadgeUnread = true),
        )
    }

    /**
     * ⭐ Stalwart's `Archive`, MEASURED on the bench on 2026-08-25 and written into
     * `SubscribedFolders.kt`: `role: null` on a folder that is the Archive button's destination.
     * `role == null` alone therefore folds the archive of every JMAP Stalwart account that has one
     * subfolder, on the first launch, and its badge is most often 0 — nothing on screen says the
     * row went away.
     */
    @Test fun `an archive the server left roleless is not folded — the app files mail into it`() {
        val folders = listOf(
            mailbox("Archive"),
            mailbox("Archive/2024"),
            mailbox("Travail"),
            mailbox("Travail/Client A"),
        )
        assertEquals(
            "a folder one of this app's actions can file mail into BY NAME must not fold itself " +
                "on the first run: on Stalwart the Archive arrives role-null, and folding it hides " +
                "the destination of the Archive button behind a chevron nobody asked for. Travail " +
                "is the control — if it is missing too, the rule stopped folding anything.",
            setOf("Travail"),
            defaultCollapsedFolderIds(folders, rowsBadgeUnread = true),
        )
    }

    /**
     * The loser of an IMAP role election (`ImapClient.assignRoles`): the server DID advertise a
     * role on it, `MailboxMapper.toMailbox` erases it to null and keeps [Mailbox.lostRoleClaim].
     * `role == null` alone reads that folder as one of the user's own.
     */
    @Test fun `a folder that claimed a role and lost the election is not folded either`() {
        val folders = listOf(
            mailbox("Spam", lostRoleClaim = true),
            mailbox("Spam/Faux positifs"),
            mailbox("Travail"),
            mailbox("Travail/Client A"),
        )
        assertEquals(
            "an account with both a Junk and a Spam has one of them reaching here with role null " +
                "and lostRoleClaim true — the server named a role on it. Folding it puts the " +
                "folder holding the spam false positives behind a chevron on the first launch. " +
                "Travail is the control.",
            setOf("Travail"),
            defaultCollapsedFolderIds(folders, rowsBadgeUnread = true),
        )
    }

    /**
     * ⭐ The IMAP shape the whole guard is built around: ids that ARE paths, everything hanging
     * under the Inbox. Folding the Inbox would leave the drawer showing one single line.
     */
    @Test fun `an IMAP tree under the Inbox leaves the Inbox open, both delimiters`() {
        val slash = listOf(
            mailbox("INBOX", role = "inbox"),
            mailbox("INBOX/Travail"),
            mailbox("INBOX/Travail/Client A"),
        )
        assertEquals(
            "on an INBOX/ namespace the whole tree hangs under the Inbox: folding it would leave " +
                "the drawer with a single line and no way to see anything else.",
            setOf("INBOX/Travail"),
            defaultCollapsedFolderIds(slash, rowsBadgeUnread = true),
        )
        val dotted = listOf(
            mailbox("INBOX", role = "inbox"),
            mailbox("INBOX.Travail"),
            mailbox("INBOX.Travail.Client A"),
        )
        assertEquals(
            "the same account on Dovecot, whose delimiter is '.' — the other branch of " +
                "folderParentId. A rule that only knows '/' folds nothing at all here.",
            setOf("INBOX.Travail"),
            defaultCollapsedFolderIds(dotted, rowsBadgeUnread = true),
        )
    }

    @Test fun `nesting declared by the JMAP parentId counts too`() {
        val folders = listOf(
            mailbox("m1", name = "Travail"),
            mailbox("m2", name = "Client A", parentId = "m1"),
        )
        assertEquals(
            "JMAP ids carry no path: filiation comes from parentId, and a rule that only splits " +
                "ids on a delimiter folds nothing on a JMAP account.",
            setOf("m1"),
            defaultCollapsedFolderIds(folders, rowsBadgeUnread = true),
        )
    }

    /**
     * The grandchild, pinned to what [folderParentId] actually answers rather than to what the
     * shape suggests: with the middle folder missing from the list, the path branch resolves a
     * parent the list does not hold and gives up, so the drawer draws BOTH at the top level and
     * `Perso` hides nothing.
     */
    @Test fun `a folder whose only descendant skips a level is nobody's parent`() {
        val folders = listOf(
            mailbox("Perso"),
            mailbox("Perso/Projets/Client A"),
        )
        assertEquals(
            "with the intermediate folder absent, mailboxTree draws the deep folder at the top " +
                "level: Perso has no chevron, so it must not start folded.",
            emptySet<String>(),
            defaultCollapsedFolderIds(folders, rowsBadgeUnread = true),
        )
    }

    @Test(timeout = 5_000) fun `a parentId pointing back down its own chain does not spin`() {
        val folders = listOf(
            mailbox("a", parentId = "b"),
            mailbox("b", parentId = "a"),
        )
        assertEquals(
            "a cycle is a server answer, not an impossibility. Each of the two names the other as " +
                "its parent, so both are parents; what must not happen is a walk that never ends.",
            setOf("a", "b"),
            defaultCollapsedFolderIds(folders, rowsBadgeUnread = true),
        )
    }

    /**
     * ⭐ V5, and the ONLY thing it changes here: an account whose drawer rows carry no unread count
     * folds nothing at all by default.
     *
     * MEASURED on the bench on 2026-09-01: not one row of an IMAP drawer shows a badge.
     * `ImapMailService.imapMailboxEntity` writes `unreadEmails = 0` with no condition,
     * `MailboxMapper` copies that 0 into `unreadForList`, and `MailRepository.observeMailboxes`
     * swaps in the live local aggregate for JMAP accounts only. Filling those counters is a
     * decision already refused in writing on `MailRepository.isImapAccount` (one STATUS round trip
     * per folder per listing), so the badge is not coming.
     *
     * Without the badge the default is dishonest: [drawerUnreadCount] is what makes a folder
     * folded on the first run survivable, and with nothing to show, mail arriving under a folded
     * parent is on NO row of the drawer. So the default is suspended — and the default only; the
     * user's own folds are the other function's business ([collapsedFolderIds]).
     *
     * ⛔ The same list is pinned BOTH ways in one case on purpose. Only the `false` half fails if
     * the flag is ignored; only the `true` half fails if the flag is read backwards, or if the
     * guard is written as an unconditional `emptySet()` that kills the feature outright.
     */
    @Test fun `rows that cannot badge unread fold nothing by default, and the flag changes nothing else`() {
        val folders = listOf(
            mailbox("INBOX", role = "inbox"),
            mailbox("INBOX/Travail"),
            mailbox("INBOX/Travail/Client A"),
            mailbox("Perso"),
            mailbox("Perso/Archive", role = "archive"),
        )
        assertEquals(
            "the account whose rows CAN badge unread must keep the shipped default: every folder " +
                "of the user's that has children starts folded. If this half is empty the guard " +
                "is unconditional and the feature is dead on every account.",
            setOf("INBOX/Travail", "Perso"),
            defaultCollapsedFolderIds(folders, rowsBadgeUnread = true),
        )
        assertEquals(
            "on an account whose folder rows carry no unread count, NOTHING may start folded. " +
                "Folding INBOX/Travail and Perso here hides whatever arrives under them behind a " +
                "chevron nobody touched, and no row anywhere says so — the badge that makes the " +
                "default honest does not exist on this account (measured on the bench, IMAP, " +
                "2026-09-01).",
            emptySet<String>(),
            defaultCollapsedFolderIds(folders, rowsBadgeUnread = false),
        )
    }

    /**
     * The role guard is not what V5 touches, seen from the other side: with no badge available the
     * answer is empty whatever the roles say, so a mutation that swapped the role guard for a
     * [folderRank] test cannot hide behind this argument.
     */
    @Test fun `a badge-less account folds nothing even where the role guard would have allowed it`() {
        val folders = listOf(mailbox("Travail"), mailbox("Travail/Client A"))
        assertEquals(
            "Travail is the plainest user folder with a child there is — the case at the top of " +
                "this file folds it. With no badge on its row it must not fold.",
            emptySet<String>(),
            defaultCollapsedFolderIds(folders, rowsBadgeUnread = false),
        )
    }

    @Test fun `an account with no folders folds nothing`() {
        assertEquals(emptySet<String>(), defaultCollapsedFolderIds(emptyList(), rowsBadgeUnread = true))
    }
}
