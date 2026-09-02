package app.sterna.ui.inbox

/**
 * The message the reading pane shows, as the SAME quintuplet the `message/{emailId}?accountId&index&src&thread`
 * route carries (#103). One value for both hosts: under 600 dp the inbox turns it into that route
 * ([toRoute]); from 600 dp it hands it to the pane beside the list. [src] is "list", "search",
 * "thread" or null (a lone message), [index] the tapped entry's position in that context, and
 * [thread] the account-qualified conversation key ([ThreadKey.encode]) when `src == "thread"`.
 */
data class MessageAnchor(
    val emailId: String,
    val accountId: String?,
    val src: String?,
    val index: Int,
    val thread: String?,
) {
    /**
     * EXACTLY the route strings the "inbox" destination used to build by hand: [encode] is
     * `Uri::encode` on Android and the identity in a JVM test, which pins the text itself.
     */
    fun toRoute(encode: (String) -> String): String {
        val base = "message/${encode(emailId)}?accountId=${encode(accountId.orEmpty())}"
        return when (src) {
            "list", "search" -> "$base&index=$index&src=$src"
            "thread" -> "$base&index=$index&src=thread&thread=${encode(thread.orEmpty())}"
            else -> base
        }
    }

    /**
     * Account-qualified, like every per-row lookup in the list: two accounts of one server list a
     * message under the SAME id (#92), and the same id under another account is not this message.
     */
    fun matches(accountId: String?, emailId: String): Boolean =
        this.emailId == emailId && this.accountId == accountId

    companion object {
        /** From the route's arguments, where a blank `accountId` / `thread` stands for none. */
        fun fromRoute(emailId: String, accountId: String?, index: Int, src: String?, thread: String?): MessageAnchor =
            MessageAnchor(emailId, accountId?.ifBlank { null }, src, index, thread?.ifBlank { null })
    }
}

/**
 * What the reading pane shows: nothing ([anchor] null), or a message — and the [session] it is
 * shown in.
 *
 * [session] is the pane's recomposition key. The pane composes the reader under `key(session)`,
 * so a TAP on another row (a new session) rebuilds the pager on the new message, exactly as pushing
 * a new `message/…` route would: fresh per-page ViewModel stores, fresh snapshots of the search
 * results or of the conversation. A SWIPE inside the pane moves the anchor to the neighbouring
 * message but keeps the session: the pager is the one the finger is on, and rebuilding it would
 * reload every body on every page, with a flash of loading ring in between.
 */
data class ReadingPaneState(val anchor: MessageAnchor? = null, val session: Int = 0)

/** The transitions of [ReadingPaneState], pure so a JVM test runs them (#103). */
object ReadingPaneRule {
    /** A tap: the same message already open is a no-op; another message starts a new session. */
    fun open(prev: ReadingPaneState, anchor: MessageAnchor): ReadingPaneState =
        if (prev.anchor?.matches(anchor.accountId, anchor.emailId) == true) prev
        else ReadingPaneState(anchor = anchor, session = prev.session + 1)

    /**
     * The pager settled on a page: the anchor follows the message under the finger, keeping its
     * `src` / `thread` (the context is the same) and — deliberately — its session. Nothing to
     * follow when nothing is open; the same message again changes nothing.
     */
    fun follow(prev: ReadingPaneState, emailId: String, accountId: String?, index: Int): ReadingPaneState {
        val anchor = prev.anchor ?: return prev
        if (anchor.matches(accountId, emailId)) return prev
        return prev.copy(anchor = anchor.copy(emailId = emailId, accountId = accountId, index = index))
    }

    /** Back, or an action that takes the message out of the folder: the pane empties. */
    fun close(prev: ReadingPaneState): ReadingPaneState =
        if (prev.anchor == null) prev else prev.copy(anchor = null)

    /**
     * The list changed scope — another folder, the unified view, the unread view, the Inbox, the
     * current ACCOUNT: the pane empties, so nothing of another list stays on the right (#112 in its
     * two-pane form).
     *
     * ⛔ There is no per-account exception here, and the account switch is not a rule of its own.
     * Sparing the anchor because it already belongs to the arriving account keeps, in the unified
     * inbox, a message opened from a sibling account's row — a message that may be in no folder the
     * new list shows, still carrying the previous list's `src` and index. The ONE thing that
     * survives a switch is the message a tapped notification parked for it, and the ViewModel names
     * it (`InboxViewModel.openFromNotification`) instead of guessing it from an account id.
     */
    fun onViewChanged(prev: ReadingPaneState): ReadingPaneState = close(prev)

    /**
     * The folder a TAPPED NOTIFICATION asks the list to show (#91), landing on the pane a beat
     * before or a beat after the message that same notification posted in it — which of the two
     * depends on when the account's folder list loads, and nothing here may depend on that.
     *
     * So the folder is not asked "was a message parked?" but "is the message in the pane MY
     * message?" ([emailId], [accountId] are the notification's own, account-qualified like every
     * other per-row lookup: two accounts of one server share ids, #92). Yes → the pane is left
     * alone. Anything else is the PREVIOUS folder's message, and a change of folder takes it off
     * the right like every other change of scope ([onViewChanged]).
     *
     * ⛔ Never a flat "keep": a notification tapped while a composer is on top opens its reader on
     * the route and parks nothing, and the folder switch would then leave another folder's message
     * beside the new list — what the person meets on her way out of the reader.
     */
    fun onFolderFromNotification(prev: ReadingPaneState, emailId: String, accountId: String?): ReadingPaneState =
        if (prev.anchor?.matches(accountId, emailId) == true) prev else close(prev)

    /**
     * The message a tapped notification parked for the account switch that same notification asked
     * for, judged against the account that actually arrived: put back on ITS own account, dropped
     * on any other.
     *
     * The account test is the guard, not the park: the park is cleared on every switch, but a park
     * that outlived its own switch (the account never became current) would otherwise be replayed
     * onto a stranger's list — one account's message sitting beside another account's mail (#92).
     * Nothing parked replays nothing.
     */
    fun replayOnAccountChange(parked: MessageAnchor?, arrivingAccountId: String?): MessageAnchor? =
        if (parked?.accountId == arrivingAccountId) parked else null

    /**
     * The anchor read back after process death is a LONE message, without its paging context: the
     * list restarts on its first page, `MessagePaging.resolveInitialPage` would not find the id in
     * that window and fall back on the index, and the pager would open ANOTHER message and mark it
     * read (`SECURITY.md`: nothing happens to a message nobody chose to open).
     */
    fun restored(anchor: MessageAnchor): MessageAnchor = anchor.copy(src = null, index = 0, thread = null)
}
