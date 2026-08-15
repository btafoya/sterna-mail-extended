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
)

/**
 * The cache row as the UI model ([Mailbox]) — ⭐ the ONE crossing from `:core:data` to what the app
 * displays (`MailRepository.observeMailboxes`, its only two call sites).
 *
 * A MARKED role is erased here ([unmarkedRole]): outside this module the folder that lost its trash
 * claim is exactly the role-less folder it was before the fix, and the mark stays what it is — a
 * value of the cache row, read by the search filters and by nothing else.
 *
 * ⛔ The defect this closes, and it is not hypothetical: `ui.inbox.InboxScreen` gates "New
 * subfolder", "Rename" and "Delete" of a folder's ⋮ menu on `mailbox.role == null` — the single
 * place in the app that tells "no role" apart from "a role I do not know". Handed `~trash`, it left
 * the user's shared trash with a one-line menu, no rename and no delete. The search fix must not
 * cost a folder its menu, so the mark never leaves this module rather than every screen learning
 * about it.
 *
 * ⛔ Not a hole in the fix: no search path goes through here. The searched/excluded folder split
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
)
