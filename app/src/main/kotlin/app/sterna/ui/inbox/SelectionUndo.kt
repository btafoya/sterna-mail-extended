package app.sterna.ui.inbox

import androidx.annotation.PluralsRes

/**
 * One message a bulk action actually wrote, and where the forward action put it ([destMailboxId],
 * null when nothing moved — a snooze — so the drawer-count nudge has nothing to reverse).
 *
 * It carries the [SelectionTarget], not a bare row, because whether the row's folder can be
 * believed is exactly what decides if an Undo may be offered at all.
 */
internal data class UndoCandidate(val target: SelectionTarget, val destMailboxId: String?)

/**
 * The Undo entries a finished batch may offer for the messages it wrote ([succeeded]).
 *
 * Undo is `restoreAll`, i.e. `client.move(..., sourceMailboxId)`, which OVERWRITES the message's
 * `mailboxIds` with `{source: true}` (`JmapClient`). The source therefore has to be the
 * folder the message really was in, and two rows cannot supply it:
 *
 *  - an untrusted row (drawn from the search index, its `mailboxId` frozen at crawl time and
 *    sometimes empty) — undoing it would file the message into a stale folder AND strip every
 *    other folder it belongs to, silently and with no way back;
 *  - a row naming no folder, or an EMPTY one: `mailboxId?.let { }` lets `""` through, and moving
 *    to an empty id is the same irreversible write against a folder that does not exist. Its twin
 *    guard sits on the destruction side (`TrashPurge`).
 *
 * And when even one untrusted row went through, the WHOLE batch's Undo is withheld: an Undo that
 * would put back four of the ten messages the user just archived is a lie about what it undoes.
 * The action itself stays done for all ten — only the offer to reverse it is dropped.
 *
 * Returns plain data rather than posting the snackbar, so this decision runs in a JVM test —
 * `InboxViewModel` is an `AndroidViewModel` and cannot be instantiated in one.
 */
internal fun selectionUndoEntries(succeeded: List<UndoCandidate>): List<UndoEntry> {
    if (succeeded.any { !it.target.folderTrusted }) return emptyList()
    return succeeded.mapNotNull { candidate ->
        val email = candidate.target.email
        val source = email.mailboxId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        UndoEntry(email.id, email.accountId, source, candidate.destMailboxId)
    }
}

/** What a batch's success banner announces, or null when there is no banner. */
internal data class SelectionBanner(@PluralsRes val labelRes: Int, val count: Int)

/**
 * The success banner a finished batch shows, or null when it shows none — no label asked for one
 * (the mixed destroy+move delete, whose Undo is the held-back destroy's), or there is nothing to
 * put back and a success bar with no Undo button on it is a bar the user cannot take back.
 *
 * The count is [wrote], the messages the batch SUCCEEDED on — never [entries].
 * [selectionUndoEntries] returns an empty list as soon as one row is untrusted, and drops every
 * row naming no folder, while those messages left the folder all the same: counting entries
 * announces "4 messages deleted" for ten deleted.
 *
 * Succeeded is not quite "written": over IMAP, `archiveAll` and `moveAllToMailbox` count a
 * message already sitting in the destination as a success without sending anything
 * (`MailRepository`), while `deleteAll` leaves that case out of both sets. So the
 * banner says what the action settled, not what crossed the wire — do not promise otherwise.
 *
 * Plain data rather than the snackbar itself, for the same reason as [selectionUndoEntries]: this
 * decision then runs in a JVM test, which an `AndroidViewModel` cannot.
 */
internal fun selectionBanner(
    @PluralsRes labelRes: Int?,
    wrote: List<UndoCandidate>,
    entries: List<UndoEntry>,
): SelectionBanner? {
    if (labelRes == null || entries.isEmpty()) return null
    return SelectionBanner(labelRes, wrote.size)
}
