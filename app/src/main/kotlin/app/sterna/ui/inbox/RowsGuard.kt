package app.sterna.ui.inbox

import app.sterna.core.data.mail.InboxRow

/**
 * How long the list waits, after the selection changed, before it hands the screen back to whatever
 * rows Paging is presenting. Safety valve only — rule 6 of [advanceRowsGuard] spends it, and only
 * while nothing is loading.
 */
internal const val ROWS_GUARD_GIVE_UP_MS = 800L

/**
 * A floor under [ROWS_GUARD_GIVE_UP_MS], pinned by `RowsGuardTest`, because the constant is a
 * silent one: shortened to a few milliseconds the valve fires before the new pager has even been
 * built — `Sel.Folder` awaits a DataStore credential read before it exists — and the screen goes
 * straight back to the previous folder's rows. Nothing else in the suite reads its value.
 */
internal const val ROWS_GUARD_GIVE_UP_FLOOR_MS = 500L

/**
 * The selection that the rows on screen are supposed to be describing.
 *
 * INVARIANT, and the whole guard rests on it: every member here must also be a member of
 * [PageKey] — [PageKey.accountId] for the first, [PageKey.sel] for the other three (`Sel.Folder`'s
 * id, `Sel.Unified`, `Sel.Unread`). `InboxViewModel` derives both from the SAME two flows
 * (`currentAccountId` and `selection`), so a change here always rebuilds the pager, which is what
 * eventually presents new rows and disarms the guard.
 *
 * A member added here that can move WITHOUT rebuilding the pager would arm the guard with
 * nothing left to disarm it: a loading indicator for ever, on top of a perfectly correct list.
 * Only the give-up valve would end it, and only once the loading stopped.
 */
internal data class ListKey(
    val accountId: String?,
    val selectedMailboxId: String?,
    val unified: Boolean,
    val unreadView: Boolean,
)

/**
 * The cheap identity of the rows Paging is presenting right now: how many, and which row is first.
 *
 * The first row is keyed by ACCOUNT AND ID, never the id alone — servers number messages per
 * account, so the unified inbox and a second account both hold an "e1", and a signature blind to
 * the account would read an account switch as "nothing moved" and leave the guard armed until the
 * valve fired.
 *
 * Enough to tell one folder's page from another's without walking the list, which matters because
 * this runs on every recomposition. It is not a hash of the contents: two DIFFERENT pages that
 * share a count and a first row read the same, and rule 5 of [advanceRowsGuard] is the net for it.
 */
internal fun rowsSignature(itemCount: Int, first: InboxRow?): String =
    "$itemCount|${first?.email?.accountId}|${first?.email?.id}"

/**
 * Whether the rows Paging is presenting demonstrably belong to a selection OTHER than [key] —
 * the one signal of BELONGING the list has, as opposed to the "something moved" signals the rest
 * of [advanceRowsGuard] runs on.
 *
 * Every presented row carries its own `accountId` and `mailboxId`, and the pager's SQL filters the
 * REPRESENTATIVE row on the (accountId, mailboxId) pairs of the scope — `MailRepository`'s
 * `folderScopeSql`, and for the collapsed view the OUTER `WHERE` of `conversationSql` (the Sent
 * alternatives widen only the thread-count sub-query, never the rows). So a presented row always
 * belongs to one of the scope's pairs, in flat and conversation mode alike.
 *
 * BOTH ENDS of the presented snapshot are asked, and `true` needs only one of them, because the
 * FIRST row can be honest while everything under it is not. `pagedListRows` is a
 * `combine(pagedEmails, localDraftRows)` and `withLocalDrafts` PREFIXES rows built by
 * `LocalDraftEntity.toInboxRow(mailboxId)` with the SELECTED folder's id: on Inbox → Drafts the
 * Room query behind the local drafts lands before `pagedEmails` has crossed its DataStore read, so
 * the list presents the phone's own draft — a genuine Drafts row — on top of the inbox's previous
 * page. Asked about the first row alone, that draft vouches for twenty foreign messages, and the
 * user gets the "Drafts" header, her draft, and her inbox under it.
 *
 * The two ends, not a scan, and that is the written reason the signature takes rows rather than
 * a list: this runs on EVERY recomposition and must stay O(1). It is not a proof of purity — a
 * foreign block wedged between two local ends would pass — but it covers the two shapes that
 * actually occur: a whole page of the previous generation (both ends foreign) and a local prefix
 * over that page (last end foreign). A correct list has both ends inside the scope.
 *
 * The question is only DECIDABLE for a single folder, and the guards below are not tidiness:
 *
 *  - "All inboxes" and the unread view span several (account, folder) pairs and have no single
 *    owner, so a row of another account is a perfectly correct row there. Answering `true` for
 *    them would arm the guard over a correct list until the valve, on every recomposition;
 *  - a null account or a null folder id is the selection not being resolved yet (a folder id is
 *    `(sel as? Sel.Folder)?.id`), which decides nothing;
 *  - nothing presented decides nothing either — an empty snapshot is exactly the moment we cannot
 *    tell the old generation from the new, and the other rules are what end the guard there.
 *
 * In all four cases the answer is `false` = "cannot prove foreign", never "proved local".
 */
internal fun rowsForeign(key: ListKey, first: InboxRow?, last: InboxRow?): Boolean = when {
    first == null -> false
    key.unified || key.unreadView -> false
    key.accountId == null || key.selectedMailboxId == null -> false
    else -> listOfNotNull(first, last).any {
        it.email.accountId != key.accountId || it.email.mailboxId != key.selectedMailboxId
    }
}

/**
 * Whether the rows on screen still belong to the PREVIOUS selection.
 *
 * [baseline] is the signature of what was on screen when the selection changed — the thing we are
 * waiting to see move. [sawLoading] remembers that a refresh has been observed running since then.
 */
internal data class RowsGuard(
    val key: ListKey,
    val baseline: String,
    val sawLoading: Boolean,
    val stale: Boolean,
)

/**
 * One step of the guard, run on every recomposition of the list.
 *
 * `LazyPagingItems` keeps presenting the previous `PagingData`'s snapshot until the new one inserts
 * its first page. So on a folder or account switch the header flips at once while `itemCount` is
 * still the OLD folder's, and the rows branch draws another folder's mail under the new folder's
 * name. `stale` is what holds the loading indicator over those rows until Paging has really swapped
 * them.
 *
 * The rules, first that applies:
 *
 *  1. the selection changed — ARM, and record what is on screen as the baseline;
 *  2. the rows on screen are PROVABLY another selection's ([foreign], from [rowsForeign]) — ARM,
 *     or stay armed. It sits in front of the sticky rule on purpose, and that is the whole point
 *     of it: rules 4 to 6 all answer "something moved / a load ran / time passed", and the OLD
 *     generation can satisfy every one of them by itself — `refreshLoading` is the load state of
 *     the PagingData being PRESENTED, i.e. the old one, and an old page that empties for a beat
 *     and comes back reads as movement. So the guard could come down and the old folder's rows
 *     come BACK under the new folder's header, which is exactly the sequence measured at the bench
 *     on 2026-08-29 (blank 0.6-2.3 s, then the wrong rows, then the right ones: 1 folder switch in
 *     4, 1 in 3 offline, 1 account switch in 2). Belonging is the only signal that cannot be
 *     forged by the previous generation, so it must be able to RE-arm;
 *  3. already disarmed for this selection — nothing, for ever. Disarming is STICKY: a later
 *     pull-to-refresh or a delivery must never re-arm, or the list blanks to a spinner on every
 *     refresh (#63, #23). Rule 2 does not weaken this: rows that belong to the selection are not
 *     foreign, so a refresh of the folder already shown never reaches it;
 *  4. Paging presents something other than the baseline — the new page is on screen, DISARM;
 *  5. a whole loading cycle ran since the switch — DISARM. The net for two selections that present
 *     identical rows (single account: "All inboxes" then "Inbox"), where rule 4 can never fire;
 *  6. the valve elapsed AND nothing is loading — DISARM. The `!refreshLoading` is the point of
 *     it: while a load is running we never hand the screen back to the old rows. Say what that
 *     load IS, because the valve is sized against it and not against the network: `refreshLoading`
 *     is the PAGING refresh, i.e. the local Room query. The pager never blocks its first page on
 *     the server (`MailRepository`'s mediator answers `SKIP_INITIAL_REFRESH`, then `Success` for
 *     anything but an APPEND), and the unified and unread views carry no mediator at all. A slow
 *     link widens the window this guard covers; it is not what the window is made of;
 *  7. otherwise carry on, only remembering whether a load has been seen.
 *
 * Rules 5 and 6 are what keeps the guard BOUNDED IN TIME, and rule 2 does not spend them
 * needlessly: the case they exist for — two selections whose rows read alike — is a case where the
 * rows belong to the new selection, so [foreign] is false there and the valve still ends it. The
 * one thing rule 2 refuses to bound is a screen we can PROVE is lying.
 *
 * It must CONVERGE: it is written and read in the same composition pass, so re-running it on its
 * own output with unchanged inputs has to give an equal object, or the screen recomposes for ever.
 * It does: rule 2 answers `prev.copy(stale = true)`, a fixed point after the first pass. And rule
 * 1 relies on the wiring's guarantee that `gaveUp` is false at the moment it fires (it is
 * `remember(listKey)`, reset by the very key change that arms).
 */
internal fun advanceRowsGuard(
    prev: RowsGuard,
    key: ListKey,
    presented: String,
    refreshLoading: Boolean,
    gaveUp: Boolean,
    // NO DEFAULT, on purpose and like MailRepository's `sentMailboxes`: an omitted belonging test
    // is a guard that silently goes back to disarming on the previous selection's own activity.
    // Every call site must say it, and the source lint pins what it says.
    foreign: Boolean,
): RowsGuard = when {
    key != prev.key -> RowsGuard(key, baseline = presented, sawLoading = refreshLoading, stale = true)
    foreign -> prev.copy(stale = true)
    !prev.stale -> prev
    presented != prev.baseline -> prev.copy(stale = false)
    prev.sawLoading && !refreshLoading -> prev.copy(stale = false)
    gaveUp && !refreshLoading -> prev.copy(stale = false)
    else -> prev.copy(sawLoading = prev.sawLoading || refreshLoading)
}
