package app.sterna.ui.inbox

import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.showOnlySubscribedFoldersOf
import app.sterna.core.data.mail.isRoutingTargetByName
import app.sterna.core.jmap.model.Mailbox

/**
 * The folders the screens show when an account asks for its SUBSCRIBED folders only (#174) —
 * [mailboxes] in the order they came in, never a sort, never a rebuild of the tree.
 *
 * [showOnlySubscribed] false hands the list back UNTOUCHED, the same object: that is the default
 * every account is on, so it must be trivially true and cost nothing.
 *
 * A folder survives on any ONE of five counts:
 *
 *  1. **the server says it is subscribed** ([Mailbox.isSubscribed]) — the setting's whole point.
 *     A server that never reports subscription leaves every folder subscribed (the field defaults
 *     to true, from the wire down), so nothing is hidden by silence;
 *  2. **it carries a role** — ANY role, not the list of the ones this app translates. This is
 *     K-9's own defect (`thunderbird-android#2615`) and it is not theoretical here: a probe on
 *     2026-08-25 has Stalwart answering `Sent` with `isSubscribed: false`, and the bench found
 *     `Archive` unsubscribed on three personas. An account with no visible Trash cannot delete
 *     into one and cannot show what it deleted; no Sent hides sent mail. `role != null` is
 *     strictly more conservative than naming the roles, and the `~trash` mark that means "this
 *     folder claimed trash and lost the election" is already erased by `MailboxMapper.toMailbox`
 *     before a [Mailbox] ever reaches here;
 *  3. **an action of this app can file mail into it by NAME** ([isRoutingTargetByName]). The
 *     role guard above cannot see that folder: measured on the bench on 2026-08-25, Stalwart
 *     answers `Archive` with `role: null` AND `isSubscribed: false`, so it left the drawer and the
 *     move picker while the Archive button kept filing mail into it — archived mail unreachable
 *     from the app. On IMAP the same account is fine only because `ImapClient.roleFromName` elects
 *     the role from the name at listing time. The decision is not restated here: this RUNS the
 *     very elections the routing runs (`archiveFolderByName`, `trashFolderByName`), so there is one
 *     table of names and it cannot drift;
 *  4. **something visible lives under it.** Drop a parent and keep its child and the child is an
 *     orphan: `mailboxTree` reparents it to the root, the drawer draws it one level up and the
 *     hierarchy on screen is a lie. `ProjectA` unsubscribed, `ProjectA/Done` subscribed: BOTH stay.
 *  5. **it CLAIMED a role and lost the election** ([Mailbox.lostRoleClaim]). An IMAP account with
 *     both a `Junk` and a `Spam` has two junks and only one can be THE junk
 *     (`ImapClient.assignRoles`); the loser is stored with a marked role and reaches this function
 *     with `role == null`, and it is a routing target for nothing — so an unsubscribed one falls
 *     through rules 1 to 3 at once and left the drawer, both move pickers, the unread view and the
 *     ⋮ menu together. It is out of search and out of the FTS index by an older and deliberate
 *     decision that stands, which makes the drawer its ONLY way back: hidden here, the folder
 *     holding the spam false positives cannot be reached from the app at all while its mail sits on
 *     the server. The boolean is NOT a role and must not be read as one — `role` stays null on
 *     that folder on purpose (`MailboxMapper.toMailbox`), which is what keeps its Rename and Delete
 *     and keeps it the destination of nothing.
 *
 * Filiation is resolved by [folderParentId] — the same walk the drawer's tree uses, because they
 * have to agree on what a parent is or rule 4 protects the wrong folder.
 *
 * Applied at the SCREEN, never at the source. `MailRepository.observeMailboxes` and
 * `MailUi.mailboxes` stay whole: `InboxViewModel.subfolderIdsOf` reads that list to find the
 * children of a folder being deleted (a hidden child left behind is mail nobody can reach),
 * `selectionIsGone` (#89) tells a DELETED folder from a hidden one on it, and the reader names a
 * parent path from it (#109).
 */
internal fun visibleFolders(mailboxes: List<Mailbox>, showOnlySubscribed: Boolean): List<Mailbox> {
    if (!showOnlySubscribed) return mailboxes
    val byId = mailboxes.associateBy { it.id }
    val keep = mutableSetOf<String>()
    mailboxes.forEach { mailbox ->
        val survives = mailbox.isSubscribed || mailbox.role != null ||
            isRoutingTargetByName(mailbox) || mailbox.lostRoleClaim
        if (!survives) return@forEach
        // Rule 4, walked from the visible folder UP: every ancestor of something visible is
        // visible. `seen` is the guard the two neighbouring walks also carry — a `parentId` that
        // points back down its own chain is a server answer, not an impossibility, and it would
        // spin here forever.
        val seen = mutableSetOf<String>()
        var current: Mailbox? = mailbox
        while (current != null && seen.add(current.id)) {
            keep += current.id
            current = folderParentId(current, byId)?.let { byId[it] }
        }
    }
    return mailboxes.filter { it.id in keep }
}

/**
 * The id of [mailbox]'s parent among [byId], or null when it sits at the root of what is listed.
 *
 * Two servers, one answer, and it is the app's existing one (`mailboxTree`, which calls this, and
 * `InboxViewModel.subfolderIdsOf`, which mirrors it): the JMAP `parentId` when it names a folder
 * the list actually holds, else the IMAP path prefix — [Mailbox.id] IS the path there, and the
 * delimiter is whichever of "/" or "." the id carries.
 *
 * A `parentId` that names a folder the list does NOT hold falls through to the path branch
 * rather than answering null on the spot: an IMAP-shaped id must still be placed, and the drawer
 * has always drawn an unplaceable child at the top level rather than dropping it.
 */
internal fun folderParentId(mailbox: Mailbox, byId: Map<String, Mailbox>): String? {
    if (mailbox.parentId != null && byId.containsKey(mailbox.parentId)) return mailbox.parentId
    val delimiter = when {
        mailbox.id.contains('/') -> "/"
        mailbox.id.contains('.') -> "."
        else -> return null
    }
    val parent = mailbox.id.substringBeforeLast(delimiter, "")
    return if (parent.isNotEmpty() && byId.containsKey(parent)) parent else null
}

/**
 * What [accountId]'s own record asks for, and no other account's — the setting is PER ACCOUNT
 * ([StoredAccount.showOnlySubscribedFolders]) and the two pickers do not read the same account as
 * the drawer: the reader's is scoped to the account owning the open message, which in the unified
 * inbox is not the current one (#92).
 *
 * An id that resolves to no record answers FALSE ([showOnlySubscribedFoldersOf]): true would
 * hide folders on an account nobody could even identify — the one answer that makes mail vanish
 * with nobody having asked.
 */
internal fun showOnlySubscribedFor(accountId: String?, accounts: List<StoredAccount>): Boolean =
    showOnlySubscribedFoldersOf(accounts.firstOrNull { it.id == accountId })
