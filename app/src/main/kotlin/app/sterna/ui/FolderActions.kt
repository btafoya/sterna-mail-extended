package app.sterna.ui

/** Folders that hold the user's OWN outgoing mail. Triage actions that only make sense on incoming
 *  mail are hidden there (#82); a null or unknown role keeps them, the rule hiding them only where
 *  they are provably meaningless. */
internal fun isOutgoingFolder(role: String?): Boolean =
    role?.trim()?.lowercase() in OUTGOING_ROLES

private val OUTGOING_ROLES = setOf("drafts", "sent")

/** Whether "Snooze" is worth offering in a folder (#82): a promise about mail you still intend to
 *  deal with, so on top of Drafts and Sent it is dropped in Spam and Trash. Deliberately narrower
 *  than hiding the whole incoming-only group there — "Not spam" is what the Spam folder is for. */
internal fun canSnoozeIn(role: String?): Boolean =
    role?.trim()?.lowercase() !in NO_SNOOZE_ROLES

// "spam" as well as "junk": JMAP names the role `junk` and the IMAP side maps \Junk onto it, but a
// role reaching here unmapped must not slip through on a spelling.
private val NO_SNOOZE_ROLES = OUTGOING_ROLES + setOf("junk", "spam", "trash")

/**
 * Whether a message should be shown by its RECIPIENTS ("To: …") instead of its sender — the one
 * decision the list row and the reader both make.
 *
 * Two things can make a message read as outgoing: the folder it sits in (#59), or the fact that you
 * wrote it (#69). The author test alone was true EVERYWHERE, and a message sent to yourself lands in
 * the Inbox, where it was announced as "To: your own address" (#115), so it is dropped in a context
 * that is explicitly incoming and only there: [role] `inbox`, and [unified], which selects no folder
 * while aggregating Inboxes and nothing else.
 */
internal fun showsRecipients(role: String?, unified: Boolean, selfAuthored: Boolean): Boolean {
    val normalised = role?.trim()?.lowercase()
    if (normalised in OUTGOING_ROLES) return true
    if (unified || normalised == "inbox") return false
    return selfAuthored
}

/**
 * The same decision for a row INSIDE an unfolded conversation — the third surface, which used to
 * carry the pre-#115 rule and so contradicted the reader it opens. The difference is not the rule
 * but the role: an unfolded conversation spans the viewed folder(s) PLUS Sent, so the role looked up
 * here is the message's OWN. [unified] is not a parameter and cannot be, this rule never consulting
 * the selected folder; an unknown folder lands on the author test, the pre-#115 answer.
 */
internal fun showsRecipientsInThread(
    accountId: String?,
    mailboxId: String?,
    roles: Map<Pair<String, String>, String>,
    selfAuthored: Boolean,
): Boolean = showsRecipients(
    role = messageFolderRole(accountId, mailboxId, roles),
    unified = false,
    selfAuthored = selfAuthored,
)

/**
 * Whether a list row carries the "(Draft)" chip — two terms, one not always there and the other not
 * always enough. [isDraft] is the `$draft` keyword, which answers for a draft that has LEFT the
 * Drafts folder (#69) but is not in the row schema: remembered in memory, only for rows a live fetch
 * has just mapped, and on IMAP never recorded at all. The message's OWN folder covers both holes,
 * and is resolved account-qualified (#31/#121), a bare folder id borrowing a sibling account's role.
 */
internal fun showsDraftBadge(
    isDraft: Boolean,
    accountId: String?,
    mailboxId: String?,
    roles: Map<Pair<String, String>, String>,
): Boolean = isDraft || messageFolderRole(accountId, mailboxId, roles)?.trim()?.lowercase() == "drafts"

/**
 * The role of the folder a message is actually filed in, or null when that cannot be said.
 * Account-qualified, because servers number folders per account (#31/#121) and a bare folder id
 * would borrow a sibling account's role.
 */
internal fun messageFolderRole(
    accountId: String?,
    mailboxId: String?,
    roles: Map<Pair<String, String>, String>,
): String? {
    if (accountId == null || mailboxId == null) return null
    return roles[accountId to mailboxId]
}
