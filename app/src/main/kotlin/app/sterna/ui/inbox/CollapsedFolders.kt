package app.sterna.ui.inbox

import app.sterna.core.data.mail.isRoutingTargetByName
import app.sterna.core.jmap.model.Mailbox

/**
 * What a folder NOBODY has touched does — the default half of the fold decision (#185).
 *
 * FIRST, and before any of the rules below: when [rowsBadgeUnread] is false, NOTHING is folded
 * by default and this answers the empty set. [rowsBadgeUnread] is the account's answer to "can a
 * folder row in this drawer show an unread count at all"
 * (`MailRepository.folderRowsBadgeUnread`), and today the account that answers no is the IMAP one:
 * its folder rows carry a hard 0 written without a condition (`ImapMailService.imapMailboxEntity`,
 * `unreadEmails = 0`, copied into `Mailbox.unreadForList` by `MailboxMapper`), and
 * `MailRepository.observeMailboxes` replaces that counter with the live local aggregate only for
 * JMAP accounts. MEASURED on the bench on 2026-09-01: not one row of an IMAP drawer carries a
 * badge.
 *
 * That is not a detail of presentation, it is what this default RESTS ON. Folding a folder on the
 * first run is honest only because [drawerUnreadCount] badges the folded row with the mail it
 * hides; with no badge anywhere, folding hides arriving mail and NOTHING on screen says so — the
 * exact disappearance the badge exists to prevent. So on such an account the DEFAULT is suspended,
 * and the default only: the user's own folds are still obeyed to the letter ([collapsedFolderIds]),
 * because remembering a chevron is a promise made on every account.
 *
 * The other way out — filling the IMAP counters — is a decision already taken and written down
 * on `MailRepository.isImapAccount`: one STATUS round trip per folder on every listing. It is not
 * reopened here. If it ever is, `folderRowsBadgeUnread` is the one line that changes and none of
 * the rules below move.
 *
 * With a badge available, a folder is folded by default when both hold:
 *
 *  1. **the user made it** — the "this folder is standard" HALF of [visibleFolders]' own rule,
 *     minus its subscription arm: `role == null && !isRoutingTargetByName && !lostRoleClaim`.
 *
 *     The role test alone is not that rule and never was. [visibleFolders] survives a folder on
 *     five counts precisely because `role == null` does NOT mean "the user made it": three
 *     families of folder the SERVER named a role on arrive here role-null.
 *      - a **routing target by NAME** ([isRoutingTargetByName]) — measured on the bench on
 *        2026-08-25 and written down in [visibleFolders]: Stalwart answers `Archive` with
 *        `role: null`. On a JMAP Stalwart account holding an `Archive/2024`, the destination of
 *        the Archive button folded itself on the first launch, and its badge is most often 0 —
 *        nothing on screen said the row had gone;
 *      - the **loser of a role election** ([Mailbox.lostRoleClaim]) — an IMAP account with a
 *        `Junk` AND a `Spam`: `ImapClient.assignRoles` elects one, and the other reaches here
 *        with its role erased on purpose (`MailboxMapper.toMailbox`) although the server
 *        advertised `\Junk` on it.
 *     The two guards are the SAME functions [visibleFolders] runs, never a second copy: one
 *     table of names, and no way to widen what the app hides without widening what it files into.
 *
 *     The test is never [folderRank], which answers 6 for every role outside the six this app
 *     translates: a JMAP server's `role = "all"` is a real role on a real standard folder and
 *     would be folded by a rank test.
 *
 *     The DEBT this leaves, unfixed and deliberately: a `SPECIAL-USE` attribute outside the six
 *     the app translates (`\Flagged`, `\Snoozed`) elects no role and is a routing target for
 *     nothing, so such a folder with children still folds itself on the first run. Nothing carries
 *     it — same shape as the untranslated-language folder below;
 *  2. **something in [mailboxes] calls it its parent** — via [folderParentId], the app's ONE
 *     definition of filiation, shared with [mailboxTree] and [visibleFolders]. A second walk
 *     written here would drift from the tree the drawer draws and put a chevron on a folder that
 *     hides nothing.
 *
 * [mailboxes] must be the list the drawer DRAWS (`MailUi.visibleMailboxes`), never the account's
 * whole list: a parent whose only child the "only subscribed folders" setting hides (#174) has no
 * child on screen, and folding it would hide nothing while showing a chevron.
 *
 * No folder the server named a role on is ever folded, and the Inbox least of all. On IMAP the
 * parent is deduced from the NAME and [Mailbox.id] IS the path ([folderParentId]), so on an
 * account in an `INBOX.` or `INBOX/` namespace the entire tree hangs under the Inbox: folding it
 * would leave the drawer showing a single line. The Inbox is safe on every server — RFC 3501
 * mandates the name, and `ImapClient.roleFromName` elects the role from it even without
 * SPECIAL-USE (which Stalwart does not advertise).
 *
 * Two consequences, assumed and deliberately NOT caught:
 *
 *  - a folder that carries a role but hangs under a folder of the user's (`Perso/Archive` elected
 *    `archive`) is off screen on the first run, hidden by its folded parent. No "never fold a
 *    parent that hides a role" exception: that is a crossed rule for a rare tree, and the unread
 *    count a folded parent will show is what makes it honest;
 *  - on a server whose folders are named in a language NEITHER `ImapClient.roleFromName` NOR
 *    `ARCHIVE_FOLDER_NAMES`/`TRASH_FOLDER_NAMES` carry, nothing elects a role and nothing
 *    recognises the name: a Greek `Κάδος` arrives `role == null` and, if it has children, is
 *    folded on the first run. Written down, not corrected. The Inbox stays safe — RFC 3501
 *    mandates its name.
 *
 * One pass, no recursion, so a `parentId` pointing back down its own chain — a server answer, not
 * an impossibility — cannot spin here; [folderParentId] itself never walks.
 */
internal fun defaultCollapsedFolderIds(mailboxes: List<Mailbox>, rowsBadgeUnread: Boolean): Set<String> {
    if (!rowsBadgeUnread) return emptySet()
    val byId = mailboxes.associateBy { it.id }
    val parents = mailboxes.mapNotNullTo(mutableSetOf()) { folderParentId(it, byId) }
    return mailboxes
        .filter { it.role == null && !isRoutingTargetByName(it) && !it.lostRoleClaim && it.id in parents }
        .mapTo(mutableSetOf()) { it.id }
}

/**
 * The ONE place the stored fold/unfold registry and the default become the set of ids [mailboxTree]
 * hides the children of.
 *
 * [choices] is the user's EXPLICIT choice per folder (`StoredAccount.collapsedFolders`): a key that
 * is absent means nobody decided anything about that folder, which is why this is a resolution and
 * not a set lookup.
 *
 *  - a key that is present WINS over the default, in both directions;
 *  - `false` is therefore not "not stored": it is the user having opened that folder by hand,
 *    and it must keep it open on every later launch against a default that would fold it. Collapse
 *    "the set of folded folders" back into this and a folder the user unfolded folds itself again
 *    at the next cold start — the trap this branch has already paid for once;
 *  - an absent key takes [defaultCollapsedFolderIds]' answer for [mailboxes].
 *
 * [rowsBadgeUnread] is carried straight through to [defaultCollapsedFolderIds] and is read
 * NOWHERE else here. On an account whose folder rows carry no unread count it empties the DEFAULT
 * — the third bullet — and it must leave the first two untouched: the registry keeps winning in
 * both directions, on every account. Fold the three cases back into "the set of folded folders"
 * and a folder the user folded by hand on an IMAP account re-opens itself at every cold start,
 * which is the memory this branch promised on all accounts, IMAP included.
 *
 * An id the registry folds is handed back even when [mailboxes] does not hold it — [mailboxTree]
 * only ever asks whether a folder it is drawing is in the set, and a fold decided on a folder that
 * is momentarily out of the list must survive its return.
 */
internal fun collapsedFolderIds(
    mailboxes: List<Mailbox>,
    choices: Map<String, Boolean>,
    rowsBadgeUnread: Boolean,
): Set<String> {
    val decided = choices.filterValues { it }.keys
    val untouched = defaultCollapsedFolderIds(mailboxes, rowsBadgeUnread).filterNot { choices.containsKey(it) }
    return decided + untouched
}

/**
 * The number the drawer badges ONE folder row with: its own unread when it is open, and its own
 * PLUS what it hides when it is folded (every descendant's [Mailbox.unreadForList] summed).
 *
 * Its own unread is not part of what it hides — the row is right there and a tap opens it. The
 * count is "this folder, and everything you can no longer see under it".
 *
 * Why it exists at all: since [defaultCollapsedFolderIds] a user folder with children starts
 * FOLDED, so a row that badges its own count alone makes new mail vanish from the drawer before
 * anyone has touched a chevron. This is that volet's other half, not an extra.
 *
 * What is ADDED to its own count is what is HIDDEN, not the whole subtree. An open folder
 * answers its own count and nothing else, so
 * with `Travail` open and its child `Client A` folded it is `Client A` that carries the sum of its
 * own descendants and `Travail` that stays on its own — exactly what the drawer shows.
 *
 * [mailboxes] must be the list the drawer DRAWS (`MailUi.visibleMailboxes`), never the account's
 * whole list: a descendant the "only subscribed folders" setting hides (#174) is on no row and in
 * no chevron, so counting it would advertise mail no gesture in this drawer can reach.
 *
 * The walk is RECURSIVE because [mailboxTree] hides grandchildren too, and it is guarded by
 * [visited] like [mailboxTree] and [visibleFolders] are: a `parentId` pointing back down its own
 * chain is a server answer, not an impossibility, and an unguarded walk would spin or count a
 * folder twice. Filiation is [folderParentId]'s, the app's one definition — never a second one.
 *
 * Two limits, assumed and deliberately NOT corrected — the same two `unreadViewCount` carries:
 *
 *  - in CONVERSATION view the sum can OVER-count against the list: it adds per-folder counts while
 *    the list groups a thread over the whole scope, so a thread unread in two of the folders it
 *    covers is counted twice and drawn once. Counting it right needs a repository query, which the
 *    drawer does not make;
 *  - on IMAP `unreadForList` is the SERVER's stored counter (`MailboxMapper`), not a local
 *    aggregate over a windowed cache, so the total can diverge from what the same tree shows on a
 *    JMAP account. It is what the folder rows themselves show, which is the property that matters.
 *    Today that counter is a hard 0 on every IMAP folder, so this sums to 0 and the row shows no
 *    badge at all — true, and WYSIWYG. It is also why [defaultCollapsedFolderIds] folds nothing by
 *    itself there: this function is unchanged by that, it just has nothing to add up.
 */
internal fun drawerUnreadCount(mailbox: Mailbox, mailboxes: List<Mailbox>, collapsed: Set<String>): Int {
    if (mailbox.id !in collapsed) return mailbox.unreadForList
    val byId = mailboxes.associateBy { it.id }
    val childrenOf = mailboxes.groupBy { folderParentId(it, byId) }
    var total = mailbox.unreadForList
    val visited = mutableSetOf(mailbox.id)
    fun visit(parent: String) {
        childrenOf[parent].orEmpty().forEach { child ->
            if (!visited.add(child.id)) return@forEach // guard against pathological cycles
            total += child.unreadForList
            visit(child.id)
        }
    }
    visit(mailbox.id)
    return total
}
