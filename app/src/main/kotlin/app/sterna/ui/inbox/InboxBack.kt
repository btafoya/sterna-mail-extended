package app.sterna.ui.inbox

/**
 * What the system Back gesture does on the list screen, in priority order.
 *
 * Back is a global mechanism and every mode the list can be in has to give it back a step
 * before the app itself is left, so the rule is stated once, here, and the screen only binds
 * one handler per outcome. [LEAVE_APP] means no handler is enabled and the system does its
 * usual thing (the list is the start destination, so: leave the app).
 */
internal enum class InboxBackAction {
    /** Multi-select is on: back drops the selection. */
    CLEAR_SELECTION,

    /** A message is open in the reading pane beside the list: back empties the pane (#103). */
    CLOSE_DETAIL,

    /** Inline search is open: back closes it and gives the list back (Codeberg #86). */
    CLOSE_SEARCH,

    /** A folder other than the Inbox is open: back returns to the Inbox. */
    SHOW_INBOX,

    /** Nothing left to undo on this screen — the system handles it. */
    LEAVE_APP,
}

/**
 * The single Back rule of the list screen. Modes are peeled off one at a time, innermost
 * first, so each press undoes exactly the last thing the user turned on.
 *
 * Search sits between the selection and the folder on purpose: searching inside a custom
 * folder takes two presses (leave the search, then leave the folder), which is what closing
 * the search by hand then pressing Back would do.
 *
 * The reading pane sits between the selection and the search: it is the portrait order, where
 * the reader is stacked above the search and the selection bar covers the reader (#103).
 *
 * The drawer is NOT part of this: [androidx.compose.material3.ModalNavigationDrawer] registers
 * its own back handling while it is open, and — being composed after these — it wins, so an
 * open drawer still closes on Back before any of this applies. That is the case under 1 200 dp
 * only: from there the drawer is permanent and has no Back of its own to register — it is never
 * "open", so nothing is peeled off before this rule and [CLOSE_DETAIL] applies exactly as
 * written (#103, volet 6).
 */
internal fun inboxBackAction(
    selectionActive: Boolean,
    detailOpen: Boolean,
    searching: Boolean,
    atInbox: Boolean,
): InboxBackAction = when {
    selectionActive -> InboxBackAction.CLEAR_SELECTION
    detailOpen -> InboxBackAction.CLOSE_DETAIL
    searching -> InboxBackAction.CLOSE_SEARCH
    !atInbox -> InboxBackAction.SHOW_INBOX
    else -> InboxBackAction.LEAVE_APP
}

/**
 * Whether the list is showing the account's Inbox — the `atInbox` flag [inboxBackAction] reads, so this
 * is what decides between "Back returns to the Inbox" and "Back LEAVES THE APP".
 *
 * Structural, never `(sel as? Sel.Folder)?.id == inboxMailboxId`: that form answers
 * `null == null` for ANY selection that is not a folder as soon as the account's inbox has never
 * been synced. Since the selection is restored across a cold start, a reader who left the app in
 * the unread view would come back to it and have her first Back close the app — from a view she
 * chose. Run by `AtInboxTest`; the ViewModel must not restate it inline (`UnreadViewWiringLintTest`).
 *
 * [Sel.Folder] with a null id keeps the answer it always had: it IS the inbox selection the
 * ViewModel builds before the inbox id is known, so `true` when the cached id is null too.
 *
 * [inboxMailboxId] is a lambda, not a `String?`, because the question only arises for a folder and
 * the answer is not free: `AccountStore.inboxMailboxId()` parses the stored account blob on every
 * call. The inline form it replaced short-circuited, so keeping it lazy leaves the unified and
 * unread views exactly as cheap as they were — and this flag is recomputed on every emission of
 * the five flows the list state is combined from.
 */
internal fun isAtInbox(sel: Sel, inboxMailboxId: () -> String?): Boolean =
    sel is Sel.Unified || (sel is Sel.Folder && sel.id == inboxMailboxId())
