package app.sterna.ui.inbox

import app.sterna.core.data.account.AccountStore
import app.sterna.core.jmap.model.Mailbox
import app.sterna.widget.AllInboxesView

/**
 * The drawer's memory of the view you were last in, as pure functions — what to store, what to
 * reopen on, and the header that restored view may honestly claim before anything syncs.
 *
 * The app already remembers the ACCOUNT across a cold start ([AccountStore.currentId] and its
 * fallback) and forgot the VIEW, which was ViewModel-local state rebuilt as "the current account's
 * Inbox" on every launch. This is the same idea, written where a JVM test can run it: the store
 * only ever holds an opaque string, so everything that can be got wrong is here.
 *
 * ⛔ The stored value carries the ACCOUNT ID next to the selection, never a bare folder id. Two
 * accounts on the same server number their mailboxes independently, so a bare id read back under
 * another account opens a sibling's folder under the current account's header (#92 / #121).
 *
 * The separator is `"\n"`, nothing is escaped, and a value carrying one is REFUSED. An account id
 * cannot hold one ([AccountStore] mints them with `UUID.randomUUID()`), but a FOLDER id can: an
 * IMAP path is decoded from modified UTF-7 with nothing filtered out of it (`ImapFolder.path`),
 * and a mailbox whose name holds a line feed travels as `&AAo-` and decodes back to a path
 * containing U+000A (`ImapUtf7`). Such a memory splits into four parts, the `parts.size != 3`
 * guard below drops it, and the app opens the Inbox — a folder we decline to remember, never a
 * folder we reopen wrong.
 */

/**
 * The string to store for [sel] under [accountId], or `null` when there is nothing worth
 * remembering — which the caller stores as `null`, i.e. "fall back on the Inbox next time".
 *
 * ⭐ `Sel.Folder(null)` is one of those: a selection with no folder id is the state a never-synced
 * account is in, and storing it would reopen on a list with no folder instead of landing on the
 * Inbox as soon as its id is known.
 */
internal fun encodeSelection(accountId: String?, sel: Sel): String? {
    if (accountId == null) return null
    return when (sel) {
        is Sel.Folder -> sel.id?.let { "$accountId\nf\n$it" }
        Sel.Unified -> "$accountId\nu\n"
        Sel.Unread -> "$accountId\nn\n"
    }
}

/**
 * The view to reopen on, or `null` to keep the behaviour the app has always had (the current
 * account's Inbox). Every refusal below is a `null`, deliberately: this is a convenience, and the
 * fallback is the state the app was in before it existed.
 *
 * @param stored what [encodeSelection] last wrote, straight out of the preferences.
 * @param currentAccountId the account the app is about to open, i.e. `AccountStore.currentId()`.
 * @param unifiedViewExists whether the app OFFERS the cross-account list at all
 *   (`AllInboxesView.existsFor`). A restored unified view on a single-account install would have no
 *   drawer entry to come back to and a title naming a view that is not on offer.
 *
 * ⛔ A folder that was deleted, renamed or unsubscribed is NOT judged here: `selectionIsUnreachable`
 * already answers that from the folder list, once it has one, and it takes a loaded list to tell
 * "taken away" from "not known yet". Doubling it here, with no list in hand, could only guess.
 */
internal fun restoreSelection(
    stored: String?,
    currentAccountId: String?,
    unifiedViewExists: Boolean,
): Sel? {
    if (stored.isNullOrEmpty() || currentAccountId == null) return null
    val parts = stored.split("\n")
    if (parts.size != 3) return null
    // The central guard: deleted account, account switched between two launches, preferences
    // carried over from another install. Without it the app opens a NEIGHBOUR account's row.
    if (parts[0] != currentAccountId) return null
    val payload = parts[2]
    return when (parts[1]) {
        "f" -> if (payload.isEmpty()) null else Sel.Folder(payload)
        "u" -> if (unifiedViewExists) Sel.Unified else null
        "n" -> Sel.Unread
        else -> null
    }
}

/**
 * [restoreSelection]'s three arguments, fetched from the store — and the ONE place in the app that
 * fetches them.
 *
 * Two sides need the same answer and they are far apart: `InboxViewModel` seeds its selection with
 * it, and `RootViewModel` must know whether the reopened view is the unified one BEFORE any list
 * exists — the push arm inside `refresh()`, which is taken before the NavHost composes anything,
 * and the account switch a notification asks for on a cold start. Each fetching the three
 * arguments for itself is two copies of the same call, both compiling, both plausible, and
 * `store.currentId()` against `store.inboxMailboxId()` is one keystroke apart: the day they drift,
 * the list reopens on one view and the rest of the app reasons about another.
 *
 * ⛔ It decides NOTHING. The rule stays [restoreSelection], pure and run by `SelectionMemoryTest`;
 * this only reads the store, which is why it is the one thing here no JVM test can execute.
 * `SelectionMemoryWiringLintTest` pins its signature and takes two counts over the whole module,
 * bench source sets included: one call to [restoreSelection], and one read of `storedView()` —
 * the second being what catches a reader that decodes the stored string by hand.
 */
internal fun restoredSelection(store: AccountStore): Sel? = restoreSelection(
    store.storedView(),
    store.currentId(),
    AllInboxesView.existsFor(store.accounts()),
)

/**
 * What the top bar shows: the account line, the mailbox name, and the badge's count.
 *
 * ⭐ Lives HERE, at the top level of the package, rather than nested in `InboxViewModel` where it
 * was born: the header a RESTORED view can honestly claim is decided by [restoredMeta] and
 * [restoredFolderMeta] below, and a `private` type nested in a class no JVM test can build puts
 * that decision out of reach. Same type, same three fields, every `Meta(…)` call site unchanged —
 * a transport triple beside it would have been one more type to keep in step (rule 5).
 */
internal data class Meta(val accountName: String, val mailboxName: String, val unread: Int)

/**
 * The internal label the unified inbox's [Meta] carries, in both its name fields.
 *
 * ⛔ ONE literal, moved out of `InboxViewModel`'s companion rather than copied: it is never shown
 * as it stands — `InboxScreen` swaps it for the translated string on `ui.unified`, and
 * `UnifiedTitleWiringLintTest` pins that — so a second copy here would be an English title in nine
 * locales the day the two drift. It sits next to [Meta] because [restoredMeta] needs to name it
 * and the companion holding it is private.
 */
internal const val UNIFIED_LABEL = "All inboxes"

/**
 * The header to start on for the view [sel] that was just restored, from what the store knows
 * WITHOUT a network round trip.
 *
 * The value this replaces was the Inbox's name and the Inbox's count, written flat. That was true
 * while a cold start always landed on the Inbox; with the view restored ([restoreSelection]) a
 * reopened folder was titled "Inbox" over that folder's messages — and in airplane mode it stayed
 * that way, because the only other writer of the header is the SUCCESS path of a refresh.
 *
 * ⚠ How much of the screen this actually moves, measured rather than assumed:
 *  - the NAME — once the folder list has arrived, `InboxScreen` titles the bar with
 *    `mailboxDisplayName(role, ui.mailboxName)`, which answers a localized label for every
 *    special-use role. Trash, Sent, Archive, Drafts and Junk therefore already had a correct title
 *    without this. What the cache fill ([restoredFolderMeta]) changes is a folder the user created
 *    HERSELF, which has no role and falls through to the raw name — and, for every folder alike,
 *    the window before the list arrives.
 *  - the COUNT — `Meta.unread` reaches `MailUi.unreadCount`, and its only reader is the drawer's
 *    "All inboxes" entry under `if (ui.unified && ui.unreadCount > 0)`, where `InboxViewModel`'s
 *    combine has already substituted the cross-account total. So the `0` below corrects no visible
 *    lie: it is a refusal to state a number nobody asked us for, and the honest value to hold if a
 *    badge ever starts reading it.
 *
 * The three arms:
 *  - [Sel.Unified] — what `selectUnified()` already writes. The label is the internal
 *    [UNIFIED_LABEL] the screen translates, and the total is the store's own sum, no network.
 *  - [Sel.Unread] — ⭐ EXACTLY what `selectUnread()` already writes, unchanged. That view's title
 *    is `R.string.inbox_unread_view` and never reads `mailboxName` — ⛔ but ONLY on the
 *    `ui.unreadView` arm, and the placeholder that stands in until the first emission has to SAY
 *    so. What had to be corrected for this arm was that flag, not the name: `InboxViewModel`'s
 *    `initialValue` did not carry `unreadView`, took the field's default `false`, and sent a
 *    restored unread view down the folder arm — the Inbox's raw name over the whole account.
 *  - [Sel.Folder] that is not the Inbox — ⛔ NO name rather than a false one, and no count either
 *    (see above for what each is worth). The name is filled in from the CACHED folder list a
 *    moment later ([restoredFolderMeta]), which is a database read and works offline.
 *
 * `Sel.Folder(null)` falls in the Inbox arm whenever [inboxMailboxId] is null too: that is the
 * "the Inbox, id not known yet" state a never-synced account is in, and it keeps the answer it
 * has always had.
 */
internal fun restoredMeta(
    sel: Sel,
    inboxMailboxId: String?,
    accountLabel: String,
    inboxName: String,
    inboxUnread: Int,
    totalUnread: Int,
): Meta = when (sel) {
    Sel.Unified -> Meta(UNIFIED_LABEL, UNIFIED_LABEL, totalUnread)
    Sel.Unread -> Meta(accountLabel, inboxName, inboxUnread)
    is Sel.Folder ->
        if (sel.id == inboxMailboxId) Meta(accountLabel, inboxName, inboxUnread)
        else Meta(accountLabel, "", 0)
}

/**
 * The answer to "a folder list just arrived — is the restored folder's header settled, and what
 * does it say?", as one value so both halves are testable.
 *
 * @property settled the one shot is spent: stop waiting for a list, whatever [meta] holds.
 * @property meta the header to write, or `null` when nothing must be written at all.
 *
 * ⚠ Two `null`s would have been one `null` too few. "No list yet" and "decided, nothing to write"
 * are the SAME `Meta?` and the opposite instruction: collapsing them lets the empty-list guard be
 * deleted without a single test noticing, since an empty list finds no folder and answers `null`
 * on its own.
 */
internal data class RestoredMetaStep(val settled: Boolean, val meta: Meta?)

/**
 * Fill in the name and count [restoredMeta] deliberately left blank, from the CACHED folder list —
 * a database read, so it lands in airplane mode, where the refresh that used to be the only other
 * writer of the header never gets far enough to write anything.
 *
 * @param restoredId the folder whose name is still missing, or `null` when there is none.
 * @param selection the view on screen NOW.
 * @param folders the list that just arrived, whole.
 *
 * The refusals, in order:
 *  - no folder waiting → nothing, and nothing is spent;
 *  - an EMPTY list is "not known yet", never "the folder is gone" — the same conservative reading
 *    `applyNotificationFolder` and `selectionIsUnreachable` take. Wait for a real list rather than
 *    decide on nothing;
 *  - the reader has moved since (a notification's folder, a tap in the drawer): the header is
 *    hers, not the memory's. Spent, not written;
 *  - the folder is not in the list any more: spent, and left to `inboxFallback`, which is what
 *    judges a folder that was taken away (#89/#174).
 */
internal fun restoredFolderMeta(
    restoredId: String?,
    selection: Sel,
    folders: List<Mailbox>,
    accountLabel: String,
): RestoredMetaStep {
    if (restoredId == null) return RestoredMetaStep(settled = false, meta = null)
    if (folders.isEmpty()) return RestoredMetaStep(settled = false, meta = null)
    if (selection != Sel.Folder(restoredId)) return RestoredMetaStep(settled = true, meta = null)
    val folder = folders.firstOrNull { it.id == restoredId }
        ?: return RestoredMetaStep(settled = true, meta = null)
    return RestoredMetaStep(settled = true, meta = Meta(accountLabel, folder.name, folder.unreadEmails))
}

/**
 * The stored view to keep once [removedIds] have been signed out, or `null` to forget it — which
 * the caller writes as `setStoredView(null)`, and `putString(key, null)` REMOVES the key, so the
 * value leaves the preferences file rather than sitting there empty.
 *
 * `PRIVACY.md` promises that everything is removed when you remove the account, and names what
 * stays behind: the addresses written to and the senders allowed to load their images, filed under
 * no account, plus the messages still waiting to be sent, which a removal does not take. This
 * value is none of those, and it stayed behind undeclared: its first field is an account id, and
 * on IMAP its third is a folder PATH the reader chose and named herself, left in cleartext on the
 * disk after the account was gone. Nothing leaves the device (`allowBackup="false"`, both keys
 * excluded from the extraction rules): what is corrected here is a false promise about the local
 * disk.
 *
 * ⛔ The key is GLOBAL — ONE entry for the whole app, the last view shown, whichever account it
 * belonged to. So it is dropped only when it names a removed account: removing it unconditionally
 * would erase a SURVIVING account's memory every time a neighbour is signed out.
 *
 * ⭐ Decided on the FIRST field alone, not on a well-formed value. An account id cannot hold a
 * `"\n"` ([AccountStore] mints them with `UUID.randomUUID()`) but a folder id can — an IMAP path
 * decoded from modified UTF-7 — so a four-field value, already refused at read time by
 * [restoreSelection], still carries a real account id in front, and is precisely the shape that
 * holds a folder name in cleartext. It has to be pruned too.
 *
 * ⛔ It does NOT replace [restoreSelection]'s account guard. That one keeps another account's view
 * off the SCREEN; this one keeps a removed account's view off the DISK. Both, not one instead of
 * the other.
 */
internal fun prunedView(stored: String?, removedIds: Set<String>): String? {
    if (stored.isNullOrEmpty()) return null
    return if (stored.substringBefore("\n") in removedIds) null else stored
}
