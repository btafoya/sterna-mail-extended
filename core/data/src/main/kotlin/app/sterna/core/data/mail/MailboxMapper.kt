package app.sterna.core.data.mail

import app.sterna.core.data.db.MailboxEntity
import app.sterna.core.jmap.model.Mailbox

internal fun Mailbox.toEntity(accountId: String): MailboxEntity = MailboxEntity(
    accountId = accountId,
    id = id,
    name = name,
    role = role,
    parentId = parentId,
    sortOrder = sortOrder,
    totalEmails = totalEmails,
    unreadEmails = unreadEmails,
    isSubscribed = isSubscribed,
)

/**
 * The cache row as the UI model ([Mailbox]) — the ONE crossing from `:core:data` to what the app
 * displays (`MailRepository.observeMailboxes`, its only two call sites).
 *
 * A MARKED role is erased here ([unmarkedRole]): outside this module the folder that lost its trash
 * claim carries no role at all, and the MARK stays what it is — a value of the cache row, read by
 * the search filters and, just below, by [isLostRoleClaim]. Nothing else reads it.
 *
 * What crosses instead is the FACT of the lost claim, as a boolean ([isLostRoleClaim] →
 * [Mailbox.lostRoleClaim]) and never as a role. It is not a softening of the erasure: `role` is
 * still null on such a folder, so its ⋮ menu, its icon and its rank are unchanged and it is still
 * the destination of nothing. It exists because "show subscribed folders only" (#174) hides a
 * folder that is unsubscribed, role-less and no routing target — which a losing `Spam` is on all
 * three counts — and that folder is out of search by an older, deliberate decision, so the drawer
 * was the only way back into it.
 *
 * The defect this closes, and it is not hypothetical: `ui.inbox.InboxScreen` gates "New
 * subfolder", "Rename" and "Delete" of a folder's ⋮ menu on `mailbox.role == null` — the single
 * place in the app that tells "no role" apart from "a role I do not know". Handed `~trash`, it left
 * the user's shared trash with a one-line menu, no rename and no delete. The search fix must not
 * cost a folder its menu, so the mark never leaves this module rather than every screen learning
 * about it.
 *
 * Not a hole in the fix: no search path goes through here. The searched/excluded folder split
 * reads `MailboxDao.searchOrder()` and the FTS statements, on the raw stored role.
 */
internal fun MailboxEntity.toMailbox(): Mailbox = Mailbox(
    id = id,
    name = name,
    role = unmarkedRole(role),
    parentId = parentId,
    sortOrder = sortOrder,
    totalEmails = totalEmails,
    unreadEmails = unreadEmails,
    // Default the badge to the stored server counter (the IMAP path); the repository
    // overrides it with the live local count for JMAP accounts.
    unreadForList = unreadEmails,
    // Carried, not decided: what the server said about the subscription crosses to the UI model
    // as it is. Nothing on screen reads it yet (#174).
    isSubscribed = isSubscribed,
    // The one thing that SURVIVES the erasure above, and the reason it may: as a boolean, not as
    // a role. `role` is null on this folder and stays null, so nothing gains a destination, an icon
    // or a menu from it; `visibleFolders` alone reads it, to keep a folder that lost its claim
    // listed when the account shows only subscribed folders. Without it a losing `Spam` left the
    // drawer, both move pickers and the unread view at once while being out of search by design —
    // the folder holding the spam false positives, unreachable, its mail still on the server.
    lostRoleClaim = isLostRoleClaim(role),
)
