package app.sterna.core.data.account

/**
 * The two decisions behind the per-account "show only subscribed folders" switch (#174), kept out
 * of [AccountStore] so they can be EXECUTED: nothing in this module can build that store (it wants
 * a `Context` and the Keystore), so anything folded into it is reachable by a source lint and by
 * nothing else — and the part worth running is precisely the one a lint cannot, the guard that
 * decides which account a tick belongs to.
 */

/**
 * [accounts] with the choice written on the ONE record [id] names, every other one handed back
 * untouched.
 *
 * ⛔ The `it.id == id` guard is the whole function. Without it a tick on one account hides the
 * unsubscribed folders of EVERY account in the app, and the other accounts' screens say nothing —
 * they are not even open. The whole list comes back (the store saves the list as one blob), in the
 * order it came in.
 */
fun withShowOnlySubscribedFolders(
    accounts: List<StoredAccount>,
    id: String,
    enabled: Boolean,
): List<StoredAccount> =
    accounts.map { if (it.id == id) it.copy(showOnlySubscribedFolders = enabled) else it }

/**
 * What the folder lists should do for [account] — false when there is no such account.
 *
 * ⛔ The fallback is FALSE on purpose: `true` would hide folders for an account whose record could
 * not even be resolved, which is the one answer that can make a mailbox vanish from the list with
 * nobody having asked for it (rule 1). Same reasoning as the field's own default,
 * [StoredAccount.showOnlySubscribedFolders].
 */
fun showOnlySubscribedFoldersOf(account: StoredAccount?): Boolean =
    account?.showOnlySubscribedFolders ?: false

/**
 * The folder sync a move of the switch owes the server, or null when it owes none (#174).
 *
 * ⛔ It carries the VALUE it was decided on, [onlySubscribed], instead of leaving the far end to
 * re-read the store. Handing the toggle path a bare "go and refresh the folders" would make the
 * ORDER of the two writes load-bearing — store first, sync second — and a later reordering would
 * send the listing out under the OLD setting, switching the feature off for good with nothing red
 * to show for it.
 */
data class FolderSubscriptionSync(val accountId: String, val onlySubscribed: Boolean)

/**
 * What moving [accountId]'s switch to [enabled] owes: a folder listing that ASKS about
 * subscription when it goes on, nothing at all when it goes off.
 *
 * ⭐ ON is not cosmetic. `LSUB` only goes out on a listing that was asked for it
 * (`ImapClient.listFolders`), so until one happens the cache holds no subscription at all — every
 * folder row still at its `isSubscribed = true` default — and the filter, which is itself correct,
 * has nothing to hide. That is the measured defect: the box moved and the drawer did not.
 *
 * ⛔ OFF owes nothing. What the server said is already cached, the filter switches itself off, and
 * a round trip paid to untick a box is a round trip paid for nothing.
 *
 * ⚠ Not free, and not a bare listing. On IMAP the folder refresh this asks for also SELECTs the
 * inbox, counts its unseen and fetches one envelope it throws away (measured in
 * `SubscriptionToggleAsksTheServerTest`). What it is NOT is a resync: no sync cursor is purged and
 * no message body is cached — see [AccountStore.setShowOnlySubscribedFolders].
 */
fun subscriptionSyncOnToggle(accountId: String, enabled: Boolean): FolderSubscriptionSync? =
    if (enabled) FolderSubscriptionSync(accountId, onlySubscribed = true) else null
