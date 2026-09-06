package app.sterna.core.data.mail

/**
 * Whether deleting a message DESTROYS it rather than moving it to the bin — as a function a JVM
 * test EXECUTES. It is exactly the question its name asks: is this message already in the Trash?
 *
 * `MailRepository` needs Room, a `Context` and a socket, so a rule written inside it is a rule no
 * test ever runs. The repository is left with the folder lookup that feeds this.
 *
 * **Why an absent Trash is `false`.** The rule used to be `roleMailboxId(…) ?: return true`:
 * no Trash on the account meant "deleting will destroy". That was true while `delete()` refused to
 * run without a bin — it is not true any more, since `delete()` resolves the folder and CREATES it
 * when nothing answers (JMAP: the role, then this account's folders by name, then a creation;
 * IMAP: the role, then a creation — its by-name recognition sits inside `ImapClient.roleFromName`
 * and there is no separate by-name step). The caller reads this answer to route the swipe to
 * the held-back destroy (`MessageDestroyWorker`), i.e. permanent destruction behind a 5 s Undo:
 * answering "destroy" for want of a folder erased mail on the most ordinary gesture in the app.
 * With `false`, the delete MOVES; and if the move cannot get its folder, `delete()` throws and the
 * existing failure path says so (`status_action_failed`). Nothing is destroyed in either outcome.
 *
 * **And why an unknown folder is `false` too.** [messageMailboxId] is empty or null on a row
 * that only exists on screen — a search hit, whose folder was frozen at crawl time. Both nulls
 * would otherwise meet in the equality below and answer "destroy" on the account this fix is
 * about. Between destroying a message that may not be in the bin and moving one that may already
 * be there, the second cannot lose the message: the move is a no-op onto the folder it sits in.
 *
 * The two guards below are deliberately redundant, in BOTH directions: whichever one is dropped,
 * the strict equality still answers `false` as long as ONE side is null or blank, so no test can
 * tell them apart one at a time — dropping the second alone leaves the suite green (measured), and
 * dropping the first alone does too. Both gone, `null == null` answers "destroy" on exactly the
 * account this fix is about, and the suite says so. They are kept as two barriers because this is
 * a data-loss path, not because a test distinguishes them.
 *
 * The other half stays live: a message that IS in the Trash still answers `true`, and the delete
 * of a message already binned is what rests on it — without it that delete would "move" the message
 * onto its own folder and it would never go. NOT Empty-trash: `InboxViewModel.emptyTrash()`
 * photographs the folder that is open and never asks this rule anything.
 */
fun deleteDestroysMessage(trashMailboxId: String?, messageMailboxId: String?): Boolean {
    if (trashMailboxId.isNullOrBlank()) return false
    if (messageMailboxId.isNullOrBlank()) return false
    return messageMailboxId == trashMailboxId
}
