package app.sterna.ui.inbox

import android.app.Application
import android.os.SystemClock
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.sterna.container
import app.sterna.R
import app.sterna.folders.FolderDeleteWorker
import app.sterna.mail.MessageDestroyWorker
import app.sterna.net.ConnectivityWatcher
import app.sterna.net.hasUsableNetwork
import app.sterna.net.isOfflineFailure
import app.sterna.net.ReconnectRefresh
import app.sterna.push.FetchAndNotify
import app.sterna.push.NewMailNotifier
import app.sterna.push.Notifications
import app.sterna.push.PushController
import app.sterna.snooze.Snoozes
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.db.OutboxLogic
import app.sterna.core.data.getOrElseUnlessCancelled
import app.sterna.core.data.mail.CrossAccountMove
import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.FrozenNumbering
import app.sterna.core.data.mail.ImapMailService
import app.sterna.core.data.mail.InboxRow
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.mail.MailSearchResult
import app.sterna.core.data.mail.UidValidity
import app.sterna.core.data.mail.emailKey
import app.sterna.core.data.settings.SortOrder
import app.sterna.core.data.settings.SwipeAction
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.Mailbox
import app.sterna.core.jmap.model.SearchQuery
import app.sterna.send.SendOutbox
import app.sterna.ui.NotificationFolderSwitch
import app.sterna.ui.search.SearchDisplay
import app.sterna.ui.search.searchComplete
import app.sterna.ui.search.searchDisplay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MailUi(
    /** The CURRENT account — the one [mailboxes] belongs to — whatever view is on screen. ⛔ Not
     *  "the account of the list": the unified inbox spans every account and still has a current one
     *  here. Null only while no account is configured, and null is also what a fixture that does not
     *  care gets, so nothing already written has to name it.
     *
     *  ⚠ It is what makes [app.sterna.ui.inbox.unreadRowFolder] able to look a row's folder up in
     *  [mailboxes] AT ALL: that list is one account's, not qualified by account, and servers number
     *  their mailboxes per account (#31/#121) — a bare id would find a neighbour's homonym. That is
     *  also why the lookup compares this field to the ROW's account before trusting the answer. */
    val accountId: String? = null,
    val accountName: String,
    val mailboxName: String,
    val unreadCount: Int,
    val selectedMailboxId: String?,
    /** True when showing the cross-account unified inbox (no single folder selected). */
    val unified: Boolean,
    /** True when showing THIS account's unread mail across its folders ([Sel.Unread]) — no single
     *  folder selected either, so nothing else on screen can tell that view from the unified one. */
    val unreadView: Boolean = false,
    /** For the drawer entry that leads to the view above: the same aggregate the folder rows are
     *  badged with, summed over the folders that view pages ([unreadViewCount], which says what it
     *  does not promise). Zero means the entry carries no number at all. */
    val unreadViewCount: Int = 0,
    /** True when the current view is the inbox home (unified, or the account's Inbox folder).
     *  Drives Back: from any other folder, Back returns here instead of leaving the app. */
    val atInbox: Boolean = true,
    /** The normal browse list is paged separately ([InboxViewModel.pagedEmails]); this
     *  holds the (bounded) results shown while inline search is active. */
    val searchResults: List<Email> = emptyList(),
    /** False when the search stopped short (server cap, or an account that failed and was
     *  dropped): the count above the results then says "at least N". */
    val searchComplete: Boolean = true,
    val mailboxes: List<Mailbox>,
    /** The SAME list minus what the account's "subscribed folders only" setting hides (#174,
     *  [visibleFolders]) — what the drawer draws, and nothing else.
     *
     *  ⛔ A second field, never a replacement for [mailboxes]: that one is read to find a folder's
     *  children before a delete ([InboxViewModel.subfolderIdsOf] — a hidden child left behind is
     *  mail nobody can reach), to tell a DELETED folder from a hidden one (#89), and to name a
     *  row's folder and its role. Filtering at the source poisons all three. */
    val visibleMailboxes: List<Mailbox> = emptyList(),
    val refreshing: Boolean,
    val error: String?,
    /** THE DISPLAY: whether the refresh indicator is drawn — never a synonym of [refreshing].
     *
     *  [refreshing] is the TRUTH ("a reconcile is running") and has two readers that must go on
     *  seeing it the instant it turns true: the arm in `InboxScreen` that keeps the centre of the
     *  screen EMPTY, so the empty-folder scene cannot claim the folder holds nothing while its
     *  first fetch is out (#63); and the seed of this flow's `stateIn`, which is the first frame of
     *  a cold start. This field is the drawing decision alone, held back by a grace and a floor
     *  ([REFRESH_INDICATOR_GRACE_MS], [REFRESH_INDICATOR_MIN_SHOW_MS]) so the tern stops blinking
     *  on every folder tap, account switch and cold start (#178). */
    val showRefreshIndicator: Boolean = false,
    /** Event-driven "no usable network" flag from [ConnectivityWatcher] (#65): true the moment
     *  WiFi/mobile drops, without waiting for a refresh to fail. Drives the offline banner. */
    val offline: Boolean = false,
    /** Inline search-on-the-list state. */
    val searching: Boolean = false,
    val searchQuery: String = "",
    val searchLoading: Boolean = false,
    val sortOrder: SortOrder = SortOrder.DATE_DESC,
    val unreadOnly: Boolean = false,
)

/** Intermediate holder so the search state can be folded into [MailUi] without a 6-arg combine. */
private data class Base(
    /** Folded in after the five-flow combine below, next to [unreadViewCount] and from the same
     *  flow, so it is seeded here and copied there. */
    val accountId: String? = null,
    val mailboxes: List<Mailbox>,
    /** Folded in after the five-flow combine below, from the setting's own flow. */
    val visibleMailboxes: List<Mailbox> = emptyList(),
    val selectedMailboxId: String?,
    val unified: Boolean,
    val unreadView: Boolean,
    /** Folded in after the five-flow combine below, so it is seeded here and copied there. */
    val unreadViewCount: Int = 0,
    val atInbox: Boolean,
    val accountName: String,
    val mailboxName: String,
    val unread: Int,
    val refreshing: Boolean,
    val error: String?,
    /** Folded in after the five-flow combine below, from [refreshIndicatorVisibility]. */
    val showRefreshIndicator: Boolean = false,
)

private data class SearchUi(
    val active: Boolean = false,
    val query: String = "",
    val results: List<Email>? = null,
    val loading: Boolean = false,
    /** False when the server leg stopped short — its cap, or an account that failed and was
     *  dropped. The count then says "at least N" instead of claiming a total (see
     *  [app.sterna.core.data.mail.MailSearchResult]). */
    val complete: Boolean = true,
)

/** Typing pause before the (unioned-in) server full-text search fires; local FTS has no debounce. */
private const val SERVER_SEARCH_DEBOUNCE_MS = 350L

/** Max hits requested from the server search (per query). */
private const val SERVER_SEARCH_LIMIT = 200

/**
 * The Undo window of a local removal: how long the message can be put back, and therefore how long
 * its notification banner outlives the gesture.
 *
 * ⭐ THE only clock. The snackbar is `SnackbarDuration.Indefinite` (`InboxScreen.kt`) and is taken
 * down by this deadline clearing `_undo`, so what the bar shows IS this window. It used to be the
 * other way round — a `Short` bar beside a `delay` here — and the two drifted apart on three
 * reachable paths (a rotation restarts the bar's four seconds, the "Time to take action"
 * accessibility setting stretches a `Short` through `getRecommendedTimeoutMillis`, and the single
 * `SnackbarHostState` queues this bar behind four `Indefinite` ones). Each of them ends with Undo
 * pressed after the banner was cancelled: the message is back in the folder, announced nowhere, and
 * on JMAP its id is already in `NewMailNotifier`'s baseline so it is never announced again.
 *
 * Four seconds of bar plus the margin the old `Short` window used to get for free (its clock only
 * started when Compose showed the bar, this one starts at the server acknowledgement).
 * ⛔ Not a setting: a knob here is the decision handed to the reader.
 */
private const val UNDO_BANNER_DISMISS_MS = 6_000L

/**
 * How many recent messages the "unread" view's pull-to-refresh looks at PER FOLDER
 * ([InboxViewModel.refreshUnreadScope]). The default of
 * `MailRepository.refreshAccountFolders`, spelled out because this caller passes it deliberately.
 *
 * ⛔ NOT the account's sync window, and the difference is not a preference — it is the heap. That
 * refresh syncs N folders in ONE pass, and its IMAP branch reads each folder as a single
 * `fetchPage(offset = 0, limit = limit)` whose envelopes are all held until every folder has been
 * read (`MailRepository.kt` writes after the loop). `ImapClient` and `ImapFolderWalk` both say in
 * as many words that a whole folder is not holdable on a phone's heap. A window of "Everything"
 * (10 000) times a dozen folders is that warning, twelve times over, on the weakest device in the
 * fleet — and it would ALSO downgrade the inbox, which reaches this view through the same one-shot
 * page instead of the write-as-you-go walk `repo.refresh` uses.
 *
 * ⭐ Bounding it truncates NOTHING that was kept before, which is the only reason a bound is safe
 * here: on JMAP the caller does not size the retained set at all — `SyncPaging.fullQuerySizing`
 * reads the account's window from the store and says so explicitly — and on IMAP this path only
 * upserts, it deletes nothing. The number means "how much recent mail to LOOK AT per folder", not
 * "how much to keep".
 *
 * ⚠ The cost, stated: in a folder that was never opened, only the 50 most recent messages enter the
 * cache per pull, so an older unread message is not in this view yet. That is already what the push
 * pass does for a watched folder, with this very same default.
 */
internal const val UNREAD_SCOPE_FOLDER_LIMIT = 50

/** The actions bound to the two swipe directions (from Settings → Reading). */
data class SwipeConfig(val right: SwipeAction, val left: SwipeAction)

/** What [InboxViewModel.swipeConfig] carries before DataStore has answered: nothing bound to
 *  either direction. A swipe landing in that window is a gesture the reader loses, never an action
 *  they did not ask for — and `NONE` is a value they can pick themselves ("None"), so the rows
 *  already handle it. It is deliberately NOT the repository's defaults: seeded with those, this was
 *  a second copy of `SettingsRepository`'s swipe defaults, free to drift from them, and it told the
 *  reader who turned swiping off that the left one still deletes. "Not read yet", not "probably
 *  this". */
internal val SWIPE_CONFIG_UNLOADED = SwipeConfig(SwipeAction.NONE, SwipeAction.NONE)

/** One message that can be moved back to its original mailbox by an Undo. [mailboxId] is the
 *  SOURCE folder to restore to; [destMailboxId] is where the forward action put it (Trash /
 *  Archive / target), so the undo can reverse the drawer-count nudge (null = it was destroyed). */
data class UndoEntry(
    val emailId: String,
    val accountId: String?,
    val mailboxId: String,
    val destMailboxId: String? = null,
)

/**
 * A reversible swipe action, surfaced as an "Undo" snackbar. Holds one entry for a
 * single-message swipe, or every message of a thread for a collapsed-conversation swipe,
 * so undo restores the whole batch at once.
 */
data class UndoAction(
    val entries: List<UndoEntry>,
    val label: String,
)

/** The saved-state keys of the reading pane's anchor (#103), the quintuplet of the `message/…` route. */
private const val PANE_EMAIL_ID = "pane.emailId"
private const val PANE_ACCOUNT_ID = "pane.accountId"
private const val PANE_SRC = "pane.src"
private const val PANE_INDEX = "pane.index"
private const val PANE_THREAD = "pane.thread"

/**
 * Built by the framework through `SavedStateViewModelFactory` — what `viewModel(entry)` on a
 * `NavBackStackEntry` uses — which knows the `(Application, SavedStateHandle)` signature. The
 * handle is the "inbox" entry's own and survives process death; it carries the reading pane's
 * anchor (#103) and nothing else here. ⛔ Nothing in `app/src` instantiates this class by hand.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val repo = application.container.mailRepository
    private val settings = application.container.settingsRepository
    private val outbox = application.container.sendOutbox

    /** Undo-send: a message held in the outbox during its cancellable window. */
    val outboxPending: StateFlow<SendOutbox.Pending?> = outbox.pending
    fun undoSend() = outbox.undo()

    /**
     * The draft handed back when the user undoes a send, waiting to reopen compose. Driving the
     * reopen off this flow (rather than firing it inline from the snackbar's Undo handler) makes
     * it deterministic: `undoSend()` clears `outboxPending`, which tears down the very snackbar
     * coroutine that used to also trigger the reopen, so that navigation could be dropped. A
     * dedicated collector, gated on nothing but this flow, always reopens compose.
     */
    val restoredDraft: StateFlow<SendOutbox.ComposeDraft?> = outbox.restored

    /** Discreet badge: outbox items that failed, or that are still waiting a while after sending. */
    val outboxCount: StateFlow<Int> = repo.outboxActiveCount().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = 0,
    )

    /**
     * The count shown on the Outbox entry inside the overflow menu: what is in the Outbox right
     * now, with no delay (#70). Deliberately NOT [outboxCount] — the dot keeps its grace because it
     * is always in view, this one only exists once the menu has been opened on purpose. So during
     * the grace the dot is absent while this reads 1; see `OutboxLogic.queuedCount`.
     */
    val outboxQueuedCount: StateFlow<Int> = repo.outboxQueuedCount().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = 0,
    )

    /**
     * Whether any outbox item is parked as failed (drives the failure banner).
     *
     * ⛔ Through [OutboxLogic.needsFailureBanner], never by testing a state here: the banner sits
     * across the Inbox and tints the outbox dot red, and it is cleared by nothing. A row saved as a
     * draft on the phone (#95) is deliberately NOT one of these — it is listed and counted, but
     * nothing failed, so nothing shouts.
     */
    val outboxHasFailures: StateFlow<Boolean> = repo.outboxFlow()
        .map { items -> items.any { OutboxLogic.needsFailureBanner(it.state) } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    /** Configured swipe-right / swipe-left actions, observed for the list rows. */
    val swipeConfig: StateFlow<SwipeConfig> =
        combine(settings.swipeRightAction, settings.swipeLeftAction) { right, left ->
            SwipeConfig(right, left)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SWIPE_CONFIG_UNLOADED,
        )

    /** A just-performed swipe action that can be undone (move the message back). */
    private val _undo = MutableStateFlow<UndoAction?>(null)
    val undo: StateFlow<UndoAction?> = _undo.asStateFlow()

    /** The deadline that takes the CURRENT Undo action's banners down, cancelled by [undo]. */
    private var undoDepartureJob: Job? = null

    /** Non-null label while an "empty trash" purge is held back and can still be undone. */
    private val _pendingPurge = MutableStateFlow<String?>(null)
    val pendingPurge: StateFlow<String?> = _pendingPurge.asStateFlow()
    private var purgeJob: Job? = null
    /** The (accountId, Trash) whose purge is currently held back (the worker fires it; Undo
     *  cancels it) — the account keys the unique work, since same-server accounts can share
     *  a mailbox id. */
    private var pendingPurgeTarget: Pair<String, String>? = null

    /** Non-null label while a permanent (Trash) delete is held back and can still be undone. */
    private val _pendingDelete = MutableStateFlow<String?>(null)
    val pendingDelete: StateFlow<String?> = _pendingDelete.asStateFlow()
    private var pendingDeleteJob: Job? = null
    /** The messages whose permanent destroy is currently held back (fired when the window ends):
     *  account, email id, and the folder the message was in when the user confirmed — the third
     *  is what the destroy re-checks against the server before touching anything (#122). */
    private var pendingDeleteTargets: List<Triple<AccountCredentials, String, String>> = emptyList()

    /** The IMAP numbering (UIDVALIDITY) each of those MESSAGES was READ under, keyed by
     *  (accountId, emailId) — the stamp its cached row carries, not the folder's current record
     *  (#99). Null, or simply missing, on JMAP, on a row cached before the column existed and on a
     *  row no longer cached: all three mean "nothing to oppose", which destroys nothing.
     *
     *  ⭐ Per MESSAGE and no longer per (account, folder): the folder's record is realigned by any
     *  background pass that meets a renumbering, while the ids on screen still belong to the old
     *  numbering — opposed to itself it reads SAME and the expunge falls on other messages. The
     *  row's stamp comes from the SELECT that enumerated it, so it cannot drift from its own UID.
     *  Keyed by ACCOUNT TOO because an email id is unique only inside its account (issue #31).
     *
     *  Frozen with the targets and replayed as-is: read it again at flush or at execution and you
     *  read what a refresh recorded AFTER the renumbering, which the server then matches by
     *  construction. */
    private var pendingDeleteNumbering: Map<Pair<String, String>, Long?> = emptyMap()

    /** The held-back messages themselves — kept so [undoDelete] can re-complete their threads,
     *  and so [completeThreadsAfterAction] never re-caches a row whose destroy is pending. */
    private var pendingDeleteEmails: List<Email> = emptyList()

    /** A transient message to surface in a snackbar (e.g. an action error). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    /**
     * A read/flag server write failed after the list had already shown the change optimistically
     * (audit B4): the snapshot now lies, silently. Say so with the same wording the bulk paths use,
     * and log it — dropping these on the floor left the star/unread state disagreeing with the
     * server with no trace. The optimistic patch is left in place; the next sync reconciles it.
     */
    private fun reportActionFailed(op: String, t: Throwable) {
        android.util.Log.w("SternaInbox", "$op failed", t)
        _message.value = getApplication<Application>().getString(R.string.status_action_failed)
    }

    // Briefly highlight, on return to the list, the row of the message the user just opened.
    // The id is staged on open and only promoted to [highlightId] when the list is resumed —
    // so the flash plays on the way back, not under the opening message — then cleared once
    // the row has flashed.
    private var pendingHighlightId: String? = null
    private val _highlightId = MutableStateFlow<String?>(null)
    val highlightId: StateFlow<String?> = _highlightId.asStateFlow()

    /** Stage the just-opened message's row to flash when the user returns to the list. */
    fun onEmailOpened(id: String) {
        pendingHighlightId = id
    }

    /** Promote the staged id when the list resumes (i.e. we're back from the message). */
    fun activatePendingHighlight() {
        pendingHighlightId?.let { _highlightId.value = it; pendingHighlightId = null }
    }

    fun clearHighlight() {
        _highlightId.value = null
    }

    // -- the reading pane beside the list, on a wide window (#103) --------------------------------

    /**
     * The message the pane shows, restored from the saved state after process death so the pane
     * comes back on the message it had (the route would have, on a narrow window). The session
     * restarts at 0: there is no pager alive to keep.
     */
    private val _readingPane = MutableStateFlow(ReadingPaneState(anchor = readSavedAnchor(), session = 0))
    val readingPane: StateFlow<ReadingPaneState> = _readingPane.asStateFlow()

    /**
     * A tap on a row, on a wide window: the message opens in the pane instead of on a route. The
     * flash staged by [onEmailOpened] — which the row calls just before this — is dropped: it was
     * the return-to-list emphasis, and the list never leaves the screen here.
     */
    fun openInPane(anchor: MessageAnchor) {
        setReadingPane(ReadingPaneRule.open(_readingPane.value, anchor))
        pendingHighlightId = null
    }

    /**
     * The message a TAPPED NOTIFICATION posts in the pane — parked as well as opened.
     *
     * A notification for another account switches that account first, and [onAccountChanged]
     * empties the pane exactly like the four other changes of scope: what was open belongs to the
     * list it was opened in. The park is what puts THIS one message back afterwards, so the order
     * in which the host's two effects run stops mattering — the same parade as [notificationFolder]
     * beside it: park, consume once, and every other switch still empties.
     */
    fun openFromNotification(anchor: MessageAnchor) {
        notificationAnchor = anchor
        openInPane(anchor)
    }

    /** The pane's pager settled on a page: the anchor — and the row painted current — follow it. */
    fun followPage(emailId: String, accountId: String?, index: Int) {
        setReadingPane(ReadingPaneRule.follow(_readingPane.value, emailId, accountId, index))
    }

    /**
     * Empties the pane; true if it was showing something. The boolean is the guard against a
     * second tap on Archive / Delete / Move in the pane's own bar: the first tap empties the pane
     * and acts, the second finds nothing to act on. ⛔ Never "the next message" here: the settle
     * marks a message read, and nothing may happen to a message nobody chose to open
     * (`SECURITY.md`).
     */
    fun closePane(): Boolean {
        val had = _readingPane.value.anchor != null
        setReadingPane(ReadingPaneRule.close(_readingPane.value))
        return had
    }

    private fun setReadingPane(next: ReadingPaneState) {
        _readingPane.value = next
        persistAnchor(next.anchor)
    }

    /**
     * A switch of scope empties the pane, on the same line the threads are collapsed and for the
     * same reason: what was open belongs to the list it was opened in. ⛔ Not from a collector on
     * [selection] — the restored selection is emitted at start-up and would erase the restored
     * anchor — and not on the folder a notification came from (`applyNotificationFolder`), which
     * lands AFTER the anchor that notification posted.
     */
    private fun paneOnViewChanged() {
        setReadingPane(ReadingPaneRule.onViewChanged(_readingPane.value))
    }

    /** Through [ReadingPaneRule.restored]: the message comes back alone, never with a pager on a list that restarts. */
    private fun readSavedAnchor(): MessageAnchor? {
        val emailId = savedState.get<String>(PANE_EMAIL_ID) ?: return null
        val saved = MessageAnchor.fromRoute(
            emailId = emailId,
            accountId = savedState[PANE_ACCOUNT_ID],
            index = savedState.get<Int>(PANE_INDEX) ?: 0,
            src = savedState[PANE_SRC],
            thread = savedState[PANE_THREAD],
        )
        return ReadingPaneRule.restored(saved)
    }

    /** Five keys for the five fields; an empty pane removes them all, so nothing stale is restored. */
    private fun persistAnchor(anchor: MessageAnchor?) {
        if (anchor == null) {
            for (key in listOf(PANE_EMAIL_ID, PANE_ACCOUNT_ID, PANE_SRC, PANE_INDEX, PANE_THREAD)) {
                savedState.remove<Any>(key)
            }
            return
        }
        savedState[PANE_EMAIL_ID] = anchor.emailId
        savedState[PANE_ACCOUNT_ID] = anchor.accountId
        savedState[PANE_SRC] = anchor.src
        savedState[PANE_INDEX] = anchor.index
        savedState[PANE_THREAD] = anchor.thread
    }

    /**
     * Rows whose dismissing swipe was played to the edge and whose server write then failed —
     * account-qualified keys, never bare ids: two accounts of one server hand out the same message
     * ids, and comparing ids alone already moved the sibling account's homonymous row (#92).
     *
     * The message never left the table: the repo is network-first, so the local drop sits DOWNSTREAM
     * of the call that failed and is unreachable offline. But the row is gone from the screen —
     * `commitSwipe` plays the whole take-off before calling back, and nothing ever reset those
     * animations — and the list key is stable, so the [refresh] below re-emits the row into the SAME
     * composition, still parked off screen. This is that row being told to come back; it clears its
     * own key through [swipeRewound] once it is home. Same shape as [dropSearchResults] /
     * [restoreSearchResults], for the same reason: the screen has to be told what the table already
     * knows.
     *
     * Two things this set is honest about rather than silent on:
     *
     *  - it is fed by [swipeRemove], which also serves the reader's own move/archive/delete (#73),
     *    where no row ever flew. Those keys are reported too, and the rewind on them is a no-op —
     *    every value it puts back is already at rest. Keep it that way: a value that is NOT
     *    idempotent does not belong in that effect;
     *  - a key is cleared by the row itself, so a key for a row that is no longer composed stays
     *    until that row is composed again — where the rewind will be a no-op and the clear the only
     *    thing that happens. It is a handful of small keys, bounded by the failures one session can
     *    produce, and self-healing; a second clearing path would be more surface than the leak.
     */
    private val _swipeRewind = MutableStateFlow<Set<EmailKey>>(emptySet())
    val swipeRewind: StateFlow<Set<EmailKey>> = _swipeRewind.asStateFlow()

    /** The row [key] is back at rest — forget it, or it rewinds again on every recomposition. */
    fun swipeRewound(key: EmailKey) {
        _swipeRewind.value = _swipeRewind.value - key
    }

    // ---- inline conversation expansion ----

    /**
     * The conversations currently unfolded inline, as (account, thread) keys — see [ThreadKey]:
     * in the unified inbox two accounts can carry the same thread id, and a bare id unfolded both
     * rows at once. Kept here, not in the paged list, so a Paging snapshot swap doesn't reset
     * what's open.
     */
    private val _expandedThreads = MutableStateFlow<Set<ThreadKey>>(emptySet())
    val expandedThreads: StateFlow<Set<ThreadKey>> = _expandedThreads.asStateFlow()

    /**
     * The WHOLE membership of each expanded thread, keyed by thread key — the thread's messages in
     * the viewed folder(s) plus its Sent replies, newest-first: exactly the messages the row's chip
     * counts. Folder-scoped by design: a member deleted to Trash leaves THIS conversation and shows
     * up in the Trash folder's conversation instead.
     *
     * The representative is IN here. The row draws it and the list beneath the row leaves it out —
     * see [ConversationExpansion.membersBelow] — but that subtraction happens where the row is
     * drawn, on the representative that row is drawing at that instant, and nowhere else.
     *
     * A LIVE reading of the local cache, kept in step by [observeThreadMembers] for as long as the
     * row stays unfolded — never a snapshot taken at the tap. The chip above it is a live query
     * too, and a row that announces a number must list that many messages a minute later as well
     * as at the instant it was opened. No network on this path.
     */
    private val _threadMembers = MutableStateFlow<Map<ThreadKey, List<Email>>>(emptyMap())
    val threadMembers: StateFlow<Map<ThreadKey, List<Email>>> = _threadMembers.asStateFlow()

    /** Thread keys already completed from the server this session — fetched at most once each. */
    private val completedThreads = mutableSetOf<ThreadKey>()

    /**
     * The conversation a message was opened FROM, as the reading view's swipe context: the messages
     * the unfolded row was showing at the moment the reader tapped one, in the order it showed them
     * (the representative on the row first, then the members beneath it).
     *
     * Recorded by [recordThreadOrder] at the tap, from the screen — the one place that holds both
     * halves — and deliberately not rebuilt here from a representative remembered since the unfold:
     * the row re-picks its representative on every write, and a remembered one names a message that
     * may no longer be the one on the row.
     *
     * The store itself is [ThreadOrders], out here where it can be exercised: this class is not
     * instantiable in a JVM test, and both halves of the recording — building the order and keeping
     * it — have to be, or the keeping is held by nothing at all.
     */
    private val threadOrder = ThreadOrders()

    /**
     * Record what the unfolded [key] row is showing — [representative] on the row, [members]
     * beneath it — as the swipe context of a message opened from it, and answer with that order so
     * the caller can say which page it is opening.
     */
    fun recordThreadOrder(key: ThreadKey, representative: Email, members: List<Email>): List<Pair<String, String?>> =
        threadOrder.record(key, representative, members)

    /**
     * The conversation a message was opened from. Empty when the thread is unknown — the reader
     * then falls back to showing the single message it was given.
     */
    fun threadEntries(key: ThreadKey): List<Pair<String, String?>> = threadOrder.entries(key)

    /** The conversation an email belongs to: its account plus its threadId (or its own id when
     *  thread-less). Account-qualified — see [ThreadKey]. */
    fun threadKeyOf(email: Email): ThreadKey =
        ConversationExpansion.threadKey(email.accountId, email.threadId, email.id)

    /**
     * Fold/unfold a conversation row in place. The cached members render at once (offline-safe)
     * and then stay live for as long as the row is open — [observeThreadMembers]; for JMAP threads
     * a background Thread/get also fills the cache with received messages that fell outside the
     * folder's short window, which the same live reading picks up.
     */
    fun toggleThreadExpanded(rep: Email) {
        val key = threadKeyOf(rep)
        if (key in _expandedThreads.value) {
            _expandedThreads.value = _expandedThreads.value - key
            return
        }
        _expandedThreads.value = _expandedThreads.value + key
        viewModelScope.launch { expandThread(rep, key) }
    }

    /**
     * The server completion of a freshly unfolded conversation. The cache side needs no work here:
     * the members are an OBSERVED query ([observeThreadMembers]) keyed on the expanded set, so the
     * rows are already on their way in.
     *
     * JMAP only (IMAP has no Thread/get, and thread-less messages have nothing to complete), once
     * per thread. It fetches for its PERSISTENCE: members that fell outside the folder's short sync
     * window are written into the cache under their real folder, and the observed query — the very
     * one the chip counts over — picks them up from there. Nothing is pushed onto the screen from
     * the wire, so what the row lists is what the row counted, always from one table.
     */
    private suspend fun expandThread(rep: Email, key: ThreadKey) {
        if (key in completedThreads) return
        val threadId = rep.threadId ?: return
        val credentials = credentialsFor(rep) ?: return
        // The folder hint that decides where a multi-mailbox member is filed: the folders the list
        // was BUILT with, like everything else this unfold is scoped by.
        val fetched = runCatching { repo.fetchThreadMembers(credentials, threadId, listScope.value.viewedMailboxIds) }
            .getOrDefault(emptyList())
        if (fetched.isEmpty()) return // offline/failed — not completed, so a later expand retries
        completedThreads += key
    }

    /**
     * Keep [_threadMembers] equal to what the cache says the unfolded conversations hold — live,
     * on the same folders and the same write the chips are counted from ([ThreadMemberStream]).
     *
     * Started once, for the ViewModel's life: the stream is idle (a single empty map) while nothing
     * is unfolded, and subscribes exactly to the rows that are.
     */
    private fun observeThreadMembers() {
        viewModelScope.launch {
            ThreadMemberStream.members(
                expanded = _expandedThreads,
                scope = listScope,
                // The snooze table, observed — not asked once per emission. The members are read
                // from `emails` alone, so nothing in that query notices a snooze starting or
                // lapsing, and the reader watched the chip come back up at the due date with
                // nothing appearing under the row. It also takes a decrypt-and-parse of the
                // account store off a path that runs on every write to the message table.
                snoozed = repo.observeActiveSnoozed(),
                fallbackAccountId = { store.load()?.id },
                read = { accountId, folders, threadKey -> repo.observeThreadEmails(accountId, folders, threadKey) },
            ).collect { live -> drawThreadMembers(live) }
        }
    }

    /**
     * Draw a live reading: membership from the cache, content from what is already on screen, minus
     * the members a call in flight has taken away ([ThreadMemberStream.reconcile], [maskedMembers]).
     */
    private fun drawThreadMembers(live: Map<ThreadKey, List<Email>>) {
        val drawn = _threadMembers.value
        val next = live.mapValues { (key, members) ->
            ThreadMemberStream.reconcile(drawn = drawn[key].orEmpty(), live = members, removed = maskedMembers.keys)
        }
        // Equal maps are not re-emitted (StateFlow conflates on equality) and the reconcile keeps
        // the drawn instances, so an unfolded conversation nothing happened to does not recompose.
        _threadMembers.value = next
    }

    /**
     * The members currently masked from that live reading, and the only way to mask one: see
     * [ThreadMemberMask]. Raised for the length of the call that removes a member and lowered by
     * the same call, so no path can leave a message buried.
     */
    private val maskedMembers = ThreadMemberMask()

    /**
     * Take [keys] off the unfolded rows for the length of [op] — the window in which the gesture
     * has already flown the row away and its cache row, which only goes on the server's
     * acknowledgement, would put it straight back.
     *
     * The mask comes down with [op], whichever way it ends — but taking it down is NOT a redraw.
     * Nothing here writes [_threadMembers] back: the member is listed again on the next emission of
     * the live reading, which is the next write to the message table. On success that is exactly
     * right — the repository ops are network-first, the row is already gone, there is nothing to put
     * back. After an op that removed nothing at all — a snooze writes a date and leaves the message
     * where it is — the snooze table's own emission redraws the rows, and the snooze ends by itself.
     *
     * The case that shows is a failure with nothing to emit behind it: offline, swipe a member of an
     * unfolded conversation to the archive and the failure banner appears while the member stays off
     * the row, because the failure path's `refresh()` writes nothing when there is no server to read
     * from. It comes back with the next write, or when the row is folded and unfolded; the chip
     * above it counts it throughout. Not a regression — the mask's earlier shape did not redraw
     * either — and left as is deliberately: putting the members back from here means re-inserting
     * copies this class is holding into a list the cache owns, which is the snapshot this whole path
     * exists to remove.
     */
    private suspend fun <T> hidingThreadMembers(keys: Set<EmailKey>, op: suspend () -> T): T {
        if (_threadMembers.value.isNotEmpty()) {
            _threadMembers.value = _threadMembers.value
                .mapValues { (_, members) -> members.filterNot { it.emailKey() in keys } }
                .filterValues { it.isNotEmpty() }
        }
        return maskedMembers.hiding(keys, op)
    }

    /**
     * Toggle the favourite star on one message inside an expanded conversation. The write reaches
     * the cache only once the server has acknowledged it, so the new state is also written into
     * [_threadMembers] at once — and the live reading keeps the copy already drawn rather than
     * reverting it in the meantime ([ThreadMemberStream.reconcile]).
     */
    fun toggleChildFlag(child: Email) {
        val flagged = !child.isFlagged
        _threadMembers.value = _threadMembers.value.mapValues { (_, members) ->
            members.map { m ->
                // Account-qualified, like every other patch of this snapshot: in the unified
                // view two accounts' conversations can hold the same JMAP id (#92).
                if (m.emailKey() != child.emailKey()) m
                else m.copy(
                    keywords = m.keywords.toMutableMap().apply {
                        if (flagged) put("\$flagged", true) else remove("\$flagged")
                    },
                )
            }
        }
        viewModelScope.launch {
            val credentials = credentialsFor(child) ?: return@launch
            runCatching { repo.setFlagged(credentials, child.id, flagged) }
                .onFailure { reportActionFailed("setFlagged (thread child)", it) }
        }
    }

    /**
     * Optimistically rewrite the seen keyword on any expanded-conversation members among [ids].
     * The cache is written only on the server's acknowledgement, so every read/unread mutation must
     * also be written here or the unfolded rows keep a stale unread dot until then. Ditto the
     * search-results snapshot, whose rows would otherwise keep a stale bold state.
     */
    private fun patchThreadMembersSeen(keys: Set<EmailKey>, seen: Boolean) {
        patchSearchResults(keys) { m ->
            m.copy(
                keywords = m.keywords.toMutableMap().apply {
                    if (seen) put("\$seen", true) else remove("\$seen")
                },
            )
        }
        if (_threadMembers.value.isEmpty()) return
        _threadMembers.value = _threadMembers.value.mapValues { (_, members) ->
            members.map { m ->
                if (m.emailKey() !in keys) m
                else m.copy(
                    keywords = m.keywords.toMutableMap().apply {
                        if (seen) put("\$seen", true) else remove("\$seen")
                    },
                )
            }
        }
    }

    // ---- search-results snapshot patching ----
    // The search results are a static snapshot too (audit r1-F9): every removal must be
    // written back into it or the swiped row stays frozen on screen until search is left.

    /** Results removed by an action, with their position, so an Undo can put them back.
     *  Doubles as a tombstone set: the FTS index outlives evicted/deleted cache rows, so
     *  later crawl/server merges must not resurrect a row the user just removed. Keyed by
     *  account-qualified [EmailKey]: unified search can show two accounts' same-id rows. */
    private val searchRemoved = mutableMapOf<EmailKey, IndexedValue<Email>>()

    /** Remove [keys] from the search-results snapshot, stashing them for a possible Undo. */
    private fun dropSearchResults(keys: Set<EmailKey>) {
        val results = searchState.value.results ?: return
        val remaining = ArrayList<Email>(results.size)
        results.forEachIndexed { index, email ->
            if (email.emailKey() in keys) searchRemoved[email.emailKey()] = IndexedValue(index, email)
            else remaining += email
        }
        if (remaining.size != results.size) searchState.value = searchState.value.copy(results = remaining)
    }

    /** Undo: put the stashed entries among [keys] back at (best-effort) their original position. */
    private fun restoreSearchResults(keys: Collection<EmailKey>) {
        val entries = keys.mapNotNull { searchRemoved.remove(it) }.sortedBy { it.index }
        if (entries.isEmpty()) return
        val restored = searchState.value.results?.toMutableList() ?: return
        entries.forEach { (index, email) -> restored.add(index.coerceAtMost(restored.size), email) }
        searchState.value = searchState.value.copy(results = restored)
    }

    /** Optimistically rewrite any search-result rows among [keys] (read state, star). */
    private fun patchSearchResults(keys: Set<EmailKey>, transform: (Email) -> Email) {
        val results = searchState.value.results ?: return
        if (results.none { it.emailKey() in keys }) return
        searchState.value = searchState.value.copy(
            results = results.map { if (it.emailKey() in keys) transform(it) else it },
        )
    }

    /**
     * Re-sync the expanded conversations' members with the cache. The live reading keeps the copy
     * already drawn (an optimistic star or read toggle must survive it), so a change made OUTSIDE
     * this ViewModel — chiefly the reader marking a child read — needs saying explicitly, or the
     * unfolded row keeps a stale unread dot when the user comes back to the list.
     */
    fun refreshThreadMembers() {
        val snapshot = _threadMembers.value
        if (snapshot.isEmpty()) return
        viewModelScope.launch {
            val keys = snapshot.values.flatten().mapTo(mutableSetOf()) { it.emailKey() }
            val fresh = repo.cachedEmailsByIds(keys).associateBy { it.emailKey() }
            _threadMembers.value = _threadMembers.value.mapValues { (_, members) ->
                members.map { m -> fresh[m.emailKey()]?.let { f -> m.copy(keywords = f.keywords) } ?: m }
            }
        }
    }

    /**
     * The view on screen. Seeded from what was stored last time ([restoredSelection], whose rule
     * [restoreSelection] `SelectionMemoryTest` runs), falling back on the current account's Inbox —
     * which is what this flow always held, and still holds whenever the memory is empty, another
     * account's, or names a view this install does not offer.
     *
     * ⛔ Through [restoredSelection], never a store read written out here: `RootViewModel` needs the
     * same answer before this ViewModel exists at all (the push arm, the notification switch), and
     * two copies of that read are two answers waiting to disagree about the view the app reopened
     * on. The bound it hands over is `AllInboxesView.existsFor`, the line that follows the drawer's
     * own `accounts.size > 1`.
     */
    private val selection = MutableStateFlow<Sel>(
        restoredSelection(store) ?: Sel.Folder(store.inboxMailboxId()),
    )
    private val currentAccountId = MutableStateFlow(store.currentId())
    private val unifiedInboxScopes = MutableStateFlow(store.allInboxScopes())

    /**
     * Whether the CURRENT account hides the folders it is not subscribed to (#174). Off for every
     * account until one ticks the box.
     *
     * Hung off `accountsFlow` — the single point every account write is published from
     * (`saveAccounts`) — so ticking the box in Settings redraws the drawer without a restart. The
     * decision itself is [showOnlySubscribedFor]'s, which a JVM test runs.
     */
    private val showOnlySubscribed: StateFlow<Boolean> =
        combine(store.accountsFlow, currentAccountId) { accounts, id -> showOnlySubscribedFor(id, accounts) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * The last folder list [mailboxes] delivered, WITH the account it belonged to — the raw list,
     * unfiltered.
     *
     * ⚠ This exists because two different things must re-derive the "unread" view's scope: a new
     * folder list, and the setting being toggled. [mailboxes] carries side effects (#91's parked
     * notification folder, #89's vanished-folder fallback) and must be collected ONCE, so the
     * setting cannot be combined into it — the scope is derived from this record instead, and the
     * side effects stay where they are, replayed by neither. The account travels WITH the list:
     * re-reading `currentAccountId.value` at derivation time would, for the tick between an
     * account switch and the new list arriving, pair the new account with the previous account's
     * folder ids (#121/#31).
     */
    private val folderSnapshot = MutableStateFlow<Pair<String?, List<Mailbox>>>(null to emptyList())

    /**
     * The folders the "unread" view ([Sel.Unread]) pages — the current account's VISIBLE ones,
     * minus Trash, Junk/Spam, Sent and Drafts. The decisions are [visibleFolders]'s and
     * [unreadViewScopes]'s, both of which a JVM test runs; this only combines them.
     *
     * Starts EMPTY rather than from a synchronous read of the store: the store knows the account,
     * not its folder list, and an empty scope list is what [MailRepository.pagedMailbox] answers
     * `PagingData.empty()` to. The badge under the entry follows this scope and is not filtered a
     * second time ([unreadViewCount]).
     */
    private val unreadScopes: StateFlow<List<Pair<String, String>>> =
        combine(folderSnapshot, showOnlySubscribed) { (accountId, folders), onlySubscribed ->
            unreadViewScopes(accountId, visibleFolders(folders, onlySubscribed))
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Back to the Inbox when the folder ON SCREEN stops being one the user can reach — the two
     * causes, one rule: it was DELETED (#89), or the account just ticked "show only subscribed
     * folders" and this one is not among them (#174). The decision is [selectionIsUnreachable]'s,
     * which a JVM test runs; this only feeds it and acts on the answer.
     *
     * ⛔ Derived from [folderSnapshot], never from a second collection of [mailboxes]: that flow
     * carries side effects (#91's parked notification folder) and a second collector would replay
     * them every time the box is ticked.
     *
     * ⚠ The setting half reverses what #174 first shipped ("hidden is not deleted"), on the bench
     * pass of 2026-08-25 and on rule 3, WYSIWYG: the list stayed titled `Vieux`, showing `Vieux`,
     * while the setting claimed only subscribed folders were shown and neither the drawer nor
     * "move to" would still offer it — and leaving it was a one-way trip.
     *
     * The WHOLE list goes in, with the flag: [selectionIsUnreachable] needs both lists to keep a
     * folder that was taken away apart from one nobody has loaded yet. [showInbox] is a no-op when
     * the Inbox is already selected, so a repeated answer costs nothing.
     */
    private val inboxFallback: Job =
        combine(folderSnapshot, showOnlySubscribed) { (_, folders), onlySubscribed ->
            selectionIsUnreachable((selection.value as? Sel.Folder)?.id, folders, onlySubscribed)
        }.onEach { unreachable -> if (unreachable) showInbox() }
            .launchIn(viewModelScope)

    /**
     * The header the list opens on, for the view [selection] was RESTORED to — decided by
     * [restoredMeta], which a JVM test runs.
     *
     * ⚠ Reads `selection.value`, so it has to stay declared AFTER [selection]; it is a plain
     * initialiser and the order is the whole guarantee it is not null here.
     *
     * It used to be the Inbox's name and count, written flat, which was true while every cold
     * start landed on the Inbox. Restored onto the Trash it titled the Trash "Inbox" and badged it
     * with the Inbox's unread count — and offline it said so for good, [refreshFolder] being the
     * only other writer and only on success.
     */
    private val meta = MutableStateFlow(
        restoredMeta(
            selection.value,
            store.inboxMailboxId(),
            store.accountLabel(),
            store.inboxMailboxName(),
            store.unreadCount(),
            store.totalUnreadCount(),
        ),
    )
    private val status = MutableStateFlow(Status(refreshing = false, error = null))

    /**
     * The clock every rule about the refresh indicator and the freshness register reads, as a field
     * rather than a constructor parameter: this is an `AndroidViewModel`, the framework builds it
     * (with its `SavedStateHandle`, the only other thing it knows how to hand over), and nothing
     * here could hand it another one. The decisions themselves take `now` as an
     * argument and live in `RefreshIndicatorGrace.kt` and `RefreshFreshness.kt`, where plain JVM
     * tests run them on a virtual clock.
     *
     * ⛔ `elapsedRealtime`, and this line is pinned by `RefreshIndicatorWiringLintTest` — because
     * every other candidate is one word away and wrong in a way no test here can see:
     *  - `System.currentTimeMillis` is a WALL clock. NTP or a hand-set time moves it, while the
     *    `delay` that schedules the wake-ups is monotonic, so a correction landing mid-refresh
     *    either skips the grace or holds the tern dark past it. It also makes the persisted
     *    freshness register believe a stamp from before a clock correction;
     *  - `System.nanoTime` compiles, is monotonic, and is in NANOSECONDS — the 250 ms grace becomes
     *    250 ns, i.e. no grace at all, and #178 comes back whole with the suite green.
     * `elapsedRealtime` is Android's DURATION clock: monotonic, counts through sleep, and survives
     * process death, which is what the freshness register needs across the cold start (#178). It
     * restarts at a reboot, so a stamp written before one reads as being in the future — which
     * `RefreshFreshness.isFreshAt` already treats as stale, i.e. it reconciles. The safe side.
     */
    private val clock: () -> Long = SystemClock::elapsedRealtime

    /** The stretch of refreshing currently on the screen's mind — see [RefreshRun]. */
    private val refreshRun = MutableStateFlow<RefreshRun?>(null)

    /**
     * Set by [refreshRequested] and consumed by the very next [refresh], so the reader's own pull
     * or Retry is drawn with no grace at all. A one-shot flag rather than a parameter on
     * [refresh]: every other caller (the init, the reconnect walk, an account switch, a folder tap)
     * would then have to spell `instant = false`, and the day one of them spells `true` by copy and
     * paste the blink is back with the whole suite green.
     */
    private var refreshAnswersAGesture = false

    /**
     * A folder a tapped notification asked the list to show, as (accountId, mailboxId, emailId),
     * waiting for that account's folder list to arrive. Non-null only between the tap and the
     * first loaded folder list — [applyNotificationFolder] consumes it either way, so it can never
     * resurface later and yank the user out of a folder they picked themselves. Declared before
     * the folder flow that reads it, which is the only place it is judged.
     *
     * The message id travels WITH the folder, and is the notification's own: it is what
     * [ReadingPaneRule.onFolderFromNotification] judges the reading pane against (#103), and it is
     * read from here rather than from [notificationAnchor] precisely because the anchor is a park
     * the account switch spends — which of the two effects lands first is not this rule's business.
     */
    private var notificationFolder: Triple<String, String, String>? = null

    /**
     * The message a tapped notification posted in the reading pane, kept so the account switch that
     * same notification asks for can put it back ([openFromNotification], [onAccountChanged]).
     *
     * ⛔ It is a park for ONE switch and nothing reads it as a flag: [applyNotificationFolder] has
     * the notification's message id of its own ([notificationFolder]) and judges the pane against
     * that, because this field is already spent by the time the folder list arrives (#103).
     */
    private var notificationAnchor: MessageAnchor? = null

    /**
     * The RESTORED folder whose name [restoredMeta] left blank, waiting for a folder list to name
     * it — the cache's list, so this lands in airplane mode too. Non-null only between the cold
     * start and the first loaded list, consumed by [applyRestoredMeta] exactly like #91's parked
     * notification folder, so it can never resurface and retitle a folder the reader chose herself.
     *
     * Armed only when the restored view is a folder that is NOT the Inbox: every other restored
     * view already knows its own name without a list.
     */
    private var restoredFolder: String? =
        (selection.value as? Sel.Folder)?.id?.takeIf { it != store.inboxMailboxId() }

    // The drawer shows the CURRENT account's folders: the cache now keeps every account's
    // rows side by side, so the flow re-scopes when the user switches accounts.
    // ⛔ This flow carries SIDE EFFECTS and is collected exactly once — a second collector
    // would replay them. Everything judged in here is therefore judged when a folder LIST
    // ARRIVES and at no other moment, which is why the Inbox fallback is not here but hangs
    // off the recorded snapshot instead ([inboxFallback]): ticking "show only subscribed
    // folders" hides a folder without any folder list arriving.
    private val mailboxes = currentAccountId.flatMapLatest { accountId ->
        if (accountId == null) flowOf(emptyList()) else repo.observeMailboxes(accountId)
    }.onEach { folders ->
        // Issue #91: a notification opened for a message living in another folder parks its
        // request here until the folder list it has to be judged against actually exists — see
        // [showFolderFromNotification]. It is handed `folders`, the WHOLE list, and only ever on
        // the arrival of one.
        // ⛔ It can no longer select a folder the setting hides ([notificationFolderToShow]): the
        // ORDER of these two lines protects nothing at all, because [inboxFallback] collects
        // asynchronously and resumes after this whole body — it would read the selection this line
        // just made and bounce it to the Inbox. Intermittently, on top of that, since
        // [folderSnapshot] conflates an equal folder list. One rule, both routes: nobody is parked
        // in a folder no list offers.
        applyNotificationFolder(folders)
        // The restored view's missing folder name, filled in from this cached list (volet C).
        // ⛔ AFTER the line above, and the order is the guard: a tapped notification has already
        // written the header through select(), and [restoredFolderMeta]'s selection check then
        // lets it win. Called from INSIDE this onEach and never from a second collection of
        // [mailboxes], which carries side effects (#91).
        applyRestoredMeta(folders)
        // The folder list that just arrived, recorded for what derives from it — the "unread"
        // view's scope and the Inbox fallback, both of which also have to follow the
        // subscription setting ([folderSnapshot], [unreadScopes], [inboxFallback]).
        folderSnapshot.value = currentAccountId.value to folders
    }

    /**
     * (account, folder) → role, for EVERY configured account — what the rows of an unfolded
     * conversation are judged by (#115). Those rows span the viewed folder(s) plus Sent, and in the
     * unified list they belong to accounts that are not the selected one, so neither [mailboxes]
     * (the current account's drawer) nor the selected folder's role can answer for them.
     *
     * Re-scoped off [app.sterna.core.data.account.AccountStore.accountsFlow], which is published
     * from the single choke point every list write goes through (`saveAccounts`): an account added,
     * an account signed out, a linked sub-account minted or pruned by the reconcile. When it emits
     * is [folderRolesFlow]'s, and that is where it is tested.
     *
     * It used to hang off [unifiedInboxScopes], and that was the defect: those are (account, inbox)
     * PAIRS, an account has no inbox id before its first folder sync, and an equal value does not
     * re-emit — so adding an account left the map scoped to the previous ones and its rows fell
     * back to the pre-#115 rule. Switching accounts does not emit here and does not need to: the
     * map covers every configured account, whichever one is selected.
     *
     * ⛔ NEVER add `replayExpirationMillis = 0` to this timeout either — banned here for exactly
     * the reason it is banned on [localDraftRows] downstream, and by transitivity through it: on
     * the way back to the screen this map would restart at `emptyMap`, the local-row flow would
     * read no Drafts role, and the phone's own drafts would blink out of the list while the server
     * row they were hiding comes back. Pinned whole in `LocalDraftListWiringLintTest`.
     */
    val folderRoles: StateFlow<Map<Pair<String, String>, String>> =
        folderRolesFlow(store.accountsFlow) { accountIds -> repo.observeFolderRoles(accountIds) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val searchState = MutableStateFlow(SearchUi())
    private var searchJob: Job? = null

    /** Seeds the local full-text index from cache when a search opens; the first query joins it. */
    private var indexJob: Job? = null

    /** Background crawl of the whole mailbox into the index; re-runs the query when it completes. */
    private var crawlJob: Job? = null

    /** Transient view filter: show only unread on the current view. */
    private val unreadOnly = MutableStateFlow(false)

    /** Multi-select mode: which messages are selected (empty + inactive = off). Account-qualified
     *  keys, not bare ids: the unified inbox can show two accounts' same-id rows, and a bare-id
     *  selection would silently cover — and act on — both (issue #31). */
    private val _selectedKeys = MutableStateFlow<Set<EmailKey>>(emptySet())
    val selectedKeys: StateFlow<Set<EmailKey>> = _selectedKeys.asStateFlow()
    private val _selectionActive = MutableStateFlow(false)
    val selectionActive: StateFlow<Boolean> = _selectionActive.asStateFlow()

    /**
     * The IMAP numbering the row of each selected key carried AT THE MOMENT OF THE TICK (#99).
     *
     * A selection is a set of KEYS, and an IMAP key is `imap:<account>:<folder>:<uid>` — the bare
     * UID, with nothing in it that says which numbering it belongs to. So a server that renumbers
     * the shifting way makes the next background walk `@Upsert` the row bearing that exact text
     * with ANOTHER message's envelope: the tick does not move, and the new envelope is on screen
     * already ticked without a gesture. Nothing downstream can see that — `recordedNumbering` and
     * the SELECT both describe the line as it is NOW, and now it is the new message, consistently.
     * This is the only place the earlier fact can live, because the selection is the only thing
     * that spans the two moments.
     *
     * ⛔ Written from ONE place, the collector on `_selectedKeys` in `init`. A collector IS "every
     * write of the selection", so it covers the five sites that add to it, the ones that will be
     * added later, and it forgets a key the moment it is unticked — none of which five explicit
     * calls would do.
     *
     * ⚠ A null value, and an absent key, both mean "this row has no numbering to offer": every
     * JMAP row has none at all, a row evicted from the cache has none either, and the read below is
     * suspending, so a key confirmed before that read comes back legitimately has no entry. None of
     * the three is evidence of a SWAP, so none of them is refused by the upstream split
     * (`UidValidity.destroyableUnderTheNumberingItWasTickedUnder`) — refusing there would refuse
     * 100 % of JMAP, which is protocol-agnostic code.
     *
     * ⛔ WHAT HAPPENS TO THEM AFTERWARDS DEPENDS ON THE GESTURE, and that is the #189 correction.
     * On the held-back destroy they still merely PASS the split and are stopped downstream by
     * `UidValidity.mayDestroyUnderStatedNumbering`, which confirms nothing without a number. On the
     * move to another account — the one destructive path with no Undo — the value is wrapped in
     * `FrozenNumbering.Frozen(...)` at the call site, absent value included, and
     * `MailRepository` REFUSES it at both ends (the read and the bin). The difference is not the
     * value, it is that a tick happened: `Frozen(null)` and "nothing was frozen" used to be the
     * same `null`.
     *
     * ⛔ That last window is not a fixed few milliseconds and must not be described as one: a
     * "Select all" over a large folder writes the whole selection at once and the read behind it is
     * chunked, so the gesture with the most to lose is the one whose stamps land last. What holds
     * the destroy up meanwhile is the downstream half alone — which is the state of affairs this
     * whole field improves on, not a promise it keeps.
     */
    private val tickedUnderFlow = MutableStateFlow<Map<EmailKey, Long?>>(emptyMap())

    /** The same field, as the rest of this class has always written and read it. ⭐ It is a
     *  property over [tickedUnderFlow] rather than a plain `var` for ONE reason: the two selection
     *  gestures run in a different coroutine from the collector that fills it, so they need to be
     *  able to WAIT for a write instead of sampling whatever is there. Every writer below keeps
     *  publishing by assigning to this name; nothing else changes. */
    private var tickedUnder: Map<EmailKey, Long?>
        get() = tickedUnderFlow.value
        set(value) { tickedUnderFlow.value = value }

    /** True when every selected message is already read — drives the read/unread toggle's icon. */
    private val _selectionAllRead = MutableStateFlow(false)
    val selectionAllRead: StateFlow<Boolean> = _selectionAllRead.asStateFlow()

    /**
     * The account the current selection belongs to: a single account, or null when the selection is
     * empty or spans accounts. The move-to-folder picker offers THIS account's folders — from the
     * unified inbox, a message of a secondary account must land in that account's folders, not the
     * active account's, or the chosen mailbox id (which collides across same-server accounts) sends
     * it nowhere and the move silently does nothing. The reader already resolves folders by the open
     * message's account (#73); this brings the list's picker in line.
     */
    private val selectionAccountId: StateFlow<String?> = _selectedKeys
        .map(::selectionAccount)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The account the selection's move-to-folder picker BELONGS to — the selection's, falling back
     * to the active account's when nothing account-specific is selected (a plain single-folder
     * view move). Its folders are the ones listed unless another account is chosen on the picker's
     * account row (#189), and it is the account the messages actually leave.
     */
    val moveOwnerAccountId: StateFlow<String?> =
        combine(selectionAccountId, currentAccountId) { selId, curId -> selId ?: curId }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The account chosen on the picker's account row (#189), null for the owner's. Held here so it
     * survives a rotation — and it DOES outlive the sheet it was made in: the screen puts it back
     * on the picker's three exits, but a rotation destroys the sheet (`showMoveSheet` is a plain
     * `remember`) while this ViewModel lives on. So nothing reads this field directly; everything
     * reads [moveAccountId], which ignores the choice as soon as the selection no longer resolves
     * a single account ([selectionMoveAccount]).
     */
    private val _moveAccountId = MutableStateFlow<String?>(null)

    /**
     * The choice as the picker and the gesture must see it: neutralised on a selection that spans
     * accounts, where the move only ever takes the retained account's messages (spec.md § 1). This
     * is the ONE place it is neutralised, so what the picker lists ([movePickerAccountId]) and
     * where the tap goes (`InboxScreen`'s `moveSelectedTo`) cannot answer differently from the
     * account row ([selectionPickerAccounts]).
     */
    val moveAccountId: StateFlow<String?> =
        combine(_moveAccountId, _selectedKeys) { chosen, keys -> selectionMoveAccount(chosen, keys) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun chooseMoveAccount(id: String?) {
        _moveAccountId.value = id
    }

    /** The account whose folders the picker LISTS ([pickerAccount]): the chosen one, else the owner. */
    private val movePickerAccountId: StateFlow<String?> =
        combine(moveAccountId, moveOwnerAccountId) { chosen, owner -> pickerAccount(chosen, owner) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Folders the move-to-folder picker offers: ONE account's — [movePickerAccountId]'s — never a
     * mix, because mailbox ids collide between accounts of the same server (#92).
     */
    val selectionMailboxes: StateFlow<List<Mailbox>> =
        movePickerAccountId.flatMapLatest { accountId ->
            if (accountId == null) flowOf(emptyList()) else repo.observeMailboxes(accountId)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Whether the picker above must hide unsubscribed folders — the setting of the SAME account
     * [selectionMailboxes] is scoped to, never [showOnlySubscribed] (the current account's): from
     * the unified inbox the selected message can belong to a sibling account whose answer differs
     * (#92, #174), and the account chosen on the row has its own answer too (#189).
     *
     * Beside the list rather than folded into it, because [moveTargets] takes the folder list and
     * the flag apart: only the targets are filtered, the parent paths shown under them are
     * resolved against the whole list (#109).
     */
    val selectionOnlySubscribed: StateFlow<Boolean> =
        combine(movePickerAccountId, store.accountsFlow) { accountId, accounts -> showOnlySubscribedFor(accountId, accounts) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * The browse list, paged from Room. A single folder uses the RemoteMediator-backed
     * pager (scrolling past the cache fetches older mail from the server); the unified
     * inbox just pages the cached rows across accounts.
     *
     * The key comes from [pageKeyFlow], which is deduped: every emission here cancels the running
     * pager and builds one that starts at the first page again, so an equal key must not reach
     * `flatMapLatest`. [cachedIn] is downstream of that on purpose — it caches the pages, it does
     * not stop a rebuild.
     */
    val pagedEmails: Flow<PagingData<InboxRow>> =
        pageKeyFlow(
            selection = selection,
            unifiedInboxScopes = unifiedInboxScopes,
            unreadViewScopes = unreadScopes,
            sortOrder = settings.sortOrder,
            unreadOnly = unreadOnly,
            conversationView = settings.conversationView,
            currentAccountId = currentAccountId,
        )
        .flatMapLatest { key ->
            when (val sel = key.sel) {
                is Sel.Folder -> {
                    val id = sel.id
                    val credentials = store.load()
                    if (id == null || credentials == null) {
                        flowOf(PagingData.empty())
                    } else {
                        // Conversation chips also count the thread's Sent replies (the chip
                        // equals what the unfolded conversation shows). The Sent folder is
                        // resolved reactively from the folder cache — account-pinned pairs —
                        // so a fresh install's chips pick it up on the first folder sync.
                        sentScopes(key, listOf(credentials.id)).flatMapLatest { sent ->
                            repo.pagedFolder(credentials, id, key.sort, listUnreadOnly(key.sel, key.unreadOnly), key.conversationView, sent)
                        }
                    }
                }
                Sel.Unified -> {
                    // The accounts the key was built from, not a fresh read of the store: the
                    // pager's rows and its Sent scope must describe the same set of accounts.
                    sentScopes(key, key.unifiedScopes.map { it.first }.distinct()).flatMapLatest { sent ->
                        repo.pagedMailbox(key.unifiedScopes, key.sort, listUnreadOnly(key.sel, key.unreadOnly), key.conversationView, sent)
                    }
                }
                Sel.Unread -> {
                    // ⭐ The unread filter is [listUnreadOnly]'s answer, not the key's raw toggle:
                    // this scope is called "Unread" and forces it on whatever the funnel says
                    // (WYSIWYG). The three branches ask the SAME function, and [selectAll] asks it
                    // too — a filter decided twice is the destructive half of #126.
                    // The account comes from the KEY, not from a fresh store read: the pager's rows
                    // and its Sent scope must describe the same account — same rule as Unified.
                    sentScopes(key, listOfNotNull(key.accountId)).flatMapLatest { sent ->
                        repo.pagedMailbox(key.unreadScopes, key.sort, listUnreadOnly(key.sel, key.unreadOnly), key.conversationView, sent)
                    }
                }
            }
        }.cachedIn(viewModelScope)

    /**
     * What `local_drafts` contributes to the list on screen — empty everywhere but this account's
     * Drafts folder (#95). The decision, and the flow itself, are [localDraftRowsFlow]'s, which a
     * JVM test executes; this only hands it the real inputs.
     *
     * ⛔ The role comes from [folderRoles] rather than from [mailboxes]: that one carries `onEach`
     * side effects (a notification's folder, the vanished-folder fallback), so a second collector
     * would re-run them. [folderRoles] is already shared and answers exactly the same question.
     *
     * ⛔ Shared as a `StateFlow` because [selectAll] has to READ IT AT THE TAP, not collect it: the
     * server rows these local drafts replace are hidden from the list, and a bulk action that still
     * counted them would delete a message that was never on screen — the destructive half of #126,
     * re-opened by our own hiding.
     *
     * ⭐ `WhileSubscribed(5_000)` and not `Eagerly`, and it is safe for a reason worth writing down:
     * the ONLY synchronous reader of `.value` is [selectAll], which nothing but `InboxScreen` can
     * reach (the tap is on its selection bar), and that screen is collecting [pagedListRows] — this
     * flow's own downstream — for the whole LIFE OF THE SCREEN, and not merely while somebody is
     * looking at it: `collectAsLazyPagingItems` is bound to the composition, not to the lifecycle
     * like the `collectAsStateWithLifecycle` calls beside it, so it keeps collecting in the
     * background too. So there is no tap without a subscriber.
     * `Eagerly` here bought nothing and cost the upstream: [folderRoles] unsubscribes 5 s after its
     * last collector, and an eager one downstream pinned that Room query subscribed for the entire
     * life of the ViewModel.
     *
     * ⛔ NEVER add `replayExpirationMillis = 0` to "tidy up" the timeout. Without it the last value
     * is kept while nobody collects; with it the cache drops back to an empty [LocalDraftRows], and
     * a [selectAll] landing in the re-subscription window subtracts NOTHING — "Select all" then
     * "Delete" sends to the Trash a server draft the list was hiding and the user never saw. That
     * is the destructive half of #126 all over again. The DAO is in any case only subscribed while
     * the scope really is a Drafts folder ([localDraftRowsFlow] answers the empty rows without
     * touching the store anywhere else).
     */
    private val localDraftRows: StateFlow<LocalDraftRows> =
        localDraftRowsFlow(
            currentAccountId = currentAccountId,
            selectedMailboxId = selection.map { (it as? Sel.Folder)?.id },
            folderRoles = folderRoles,
            unreadOnly = unreadOnly,
            localDrafts = { accountId -> repo.observeLocalDrafts(accountId) },
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalDraftRows())

    /**
     * ⭐ **What the LIST screen pages, and nothing else may.** [pagedEmails] with the phone's own
     * drafts on top and the server copies they replace taken out — see [withLocalDrafts] for why
     * this is strictly downstream of `cachedIn` and why it is a `combine` and not a `map`.
     *
     * ⛔ `MessageScreen` keeps paging [pagedEmails]. Handing the reader this one puts a local-draft
     * id in its pager, where a sideways swipe opens a page that cannot load and a swipe-delete
     * announces a deletion IMAP never performed.
     */
    val pagedListRows: Flow<PagingData<InboxRow>> =
        combine(pagedEmails, localDraftRows) { paged, local -> withLocalDrafts(paged, local) }

    /** The accounts' Sent folders backing the chip's "plus Sent replies" scope — live from the
     *  folder cache in conversation mode (deduped upstream, so the pager only rebuilds when a
     *  Sent id actually changes); flat mode needs none. Every emission is also kept in
     *  [listScope], together with the folder(s) [key] is paging, because unfolding a row must
     *  reuse what that row's chip counted — both halves of it. The viewed folders come from the
     *  paging key, never from a fresh read of the selection: see [ListScope]. */
    private fun sentScopes(key: PageKey, accountIds: List<String>): Flow<List<Pair<String, String>>> =
        (if (key.conversationView) repo.observeSentMailboxes(accountIds) else flowOf(emptyList<Pair<String, String>>()))
            .onEach { listScope.value = ListScope(viewedMailboxIds(key), it) }

    /** The folder(s) [key] pages — one folder, or every account's inbox when unified. Bare ids
     *  here on purpose: this feeds the unfold's folder set ([ConversationScope.folders]), which
     *  is account-pinned by its own caller; the LIST's scope keeps the account (see [PageKey]). */
    private fun viewedMailboxIds(key: PageKey): List<String> = when (val sel = key.sel) {
        is Sel.Folder -> listOfNotNull(sel.id)
        Sel.Unified -> key.unifiedScopes.map { it.second }.distinct()
        Sel.Unread -> key.unreadScopes.map { it.second }.distinct()
    }

    /**
     * The scope the list currently on screen was built with — the folders it pages and the Sent
     * pairs its chips counted over. Recorded here on the way into the pager ([sentScopes]) and
     * read back by the live member stream ([observeThreadMembers], through [ListScope.folders]),
     * so the unfold and the chip describe one conversation rather than two.
     *
     * A scope that changes rebuilds the pager, which redraws the chips, AND re-reads the members
     * of every row still unfolded — the recorded value, the drawn chips and the messages beneath
     * them move together. A StateFlow, not a plain field, because "the resolution changed while a
     * row was open" is exactly the case the snapshot could not answer.
     */
    private val listScope = MutableStateFlow(ListScope())

    // "All inboxes (N)" sums the SAME live per-inbox aggregates the folder badges below it
    // read (mode-aware: unread threads in conversation view, unread messages in flat view),
    // so the two numbers in the drawer agree by construction for JMAP; IMAP inboxes keep
    // contributing their stored server counter (their windowed cache would under-count).
    // The no-argument overload derives its own scopes from the stored accounts, so the drawer
    // reads this number the same way a home-screen widget will — one derivation, not two.
    private val unifiedUnread = repo.observeUnifiedInboxUnread()

    /** Codeberg #65: the offline empty state promises a resync, so watch the network — the
     *  transports *and* the route this app is given, so a VPN tunnel coming back counts — and
     *  re-run the current view's refresh once connectivity actually returns. Its [online] flow
     *  also drives the offline banner, from the callbacks corrected by what [refresh] lived, so a
     *  killswitch tunnel that the framework cannot see is still shown honestly.
     *  Declared before [state] so its flow is available when the combined state is built. */
    private val connectivity = ConnectivityWatcher(application) { onReconnected() }
    private var reconnectJob: Job? = null

    private val baseState = combine(mailboxes, selection, meta, status, unifiedUnread) { mailboxes, sel, meta, status, unifiedUnread ->
        Base(
            mailboxes = mailboxes,
            selectedMailboxId = (sel as? Sel.Folder)?.id,
            unified = sel is Sel.Unified,
            unreadView = sel is Sel.Unread,
            // ⛔ The rule lives in [isAtInbox] and is NOT restated here: atInbox true means the
            // system Back gesture LEAVES THE APP, and the inline form that answered null == null
            // for any non-folder selection did exactly that from a view the reader chose.
            atInbox = isAtInbox(sel) { store.inboxMailboxId() },
            accountName = meta.accountName,
            mailboxName = meta.mailboxName,
            unread = if (sel is Sel.Unified) unifiedUnread else meta.unread,
            refreshing = status.refreshing,
            error = status.error,
        )
    }

    /**
     * The drawer's unread badge, folded into [baseState] rather than combined into [state]: the
     * typed `combine` stops at five flows and both are already at five.
     *
     * The folder list comes back out of [Base] and is NOT collected again — [mailboxes] carries side
     * effects (#91's parked notification folder, #89's vanished-folder fallback) that a second
     * collector would replay. That is also why the subscription filter (#174) is applied HERE, on
     * the list [Base] carries, and why its answer is a SECOND field: the drawer draws
     * [MailUi.visibleMailboxes], everything else — a row's folder and role, the children of a
     * folder about to be deleted, #89's fallback — keeps reading the whole [MailUi.mailboxes].
     *
     * The count is [unreadViewCount]'s, run by `UnreadViewCountTest`, and it
     * is handed the same scope the pager is built from — so the entry offers the folders the list
     * pages, and carries the same aggregate as the folder rows under it. ⛔ Not "the number of rows
     * you will see": in conversation view a thread unread in two folders of the scope is summed
     * twice here and drawn once. See [unreadViewCount].
     */
    private val indicatorShowing = refreshIndicatorVisibility(refreshRun, clock)

    private val badgedState = combine(baseState, unreadScopes, currentAccountId, showOnlySubscribed, indicatorShowing) { base, scopes, accountId, onlySubscribed, showing ->
        base.copy(accountId = accountId, unreadViewCount = unreadViewCount(accountId, scopes, base.mailboxes), visibleMailboxes = visibleFolders(base.mailboxes, onlySubscribed), showRefreshIndicator = showing)
    }

    val state: StateFlow<MailUi> = combine(badgedState, searchState, settings.sortOrder, unreadOnly, connectivity.online) { base, search, sortOrder, unreadOnly, online ->
        MailUi(
            accountId = base.accountId,
            accountName = base.accountName,
            mailboxName = base.mailboxName,
            unreadCount = base.unread,
            selectedMailboxId = base.selectedMailboxId,
            unified = base.unified,
            unreadView = base.unreadView,
            unreadViewCount = base.unreadViewCount,
            atInbox = base.atInbox,
            searchResults = search.results.orEmpty(),
            searchComplete = search.complete,
            mailboxes = base.mailboxes,
            visibleMailboxes = base.visibleMailboxes,
            refreshing = base.refreshing,
            error = base.error,
            showRefreshIndicator = base.showRefreshIndicator,
            offline = !online,
            searching = search.active,
            searchQuery = search.query,
            searchLoading = search.loading,
            sortOrder = sortOrder,
            unreadOnly = unreadOnly,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MailUi(
            accountName = meta.value.accountName,
            mailboxName = meta.value.mailboxName,
            unreadCount = meta.value.unread,
            selectedMailboxId = (selection.value as? Sel.Folder)?.id,
            unified = selection.value is Sel.Unified,
            // ⛔ NOT the field's default: this placeholder is a SECOND SITE of every rule the
            // combine above states, and it is what the reader sees until the first emission. Left
            // out, a RESTORED unread view starts on `unreadView = false`, so InboxScreen takes the
            // FOLDER arm and titles a list spanning the whole account with mailboxName — i.e. the
            // Inbox's raw, untranslated name — and the search hint says "Search in <that>" too.
            unreadView = selection.value is Sel.Unread,
            atInbox = isAtInbox(selection.value) { store.inboxMailboxId() },
            mailboxes = emptyList(),
            refreshing = true,
            // ⛔ Spelled out, not left to the field's default: this placeholder is a SECOND SITE of
            // every rule the combine above states. 'refreshing = true' just above is the TRUTH and
            // stays — it empties the centre while the first fetch is out (#63) — and this is the
            // DISPLAY, which must not light before the grace has passed, cold start included (#178).
            showRefreshIndicator = false,
            error = null,
            offline = !connectivity.online.value,
        ),
    )

    init {
        refreshUnlessFresh()
        connectivity.start()
        observeThreadMembers()
        // Recompute the read/unread toggle state whenever the selection set changes.
        viewModelScope.launch {
            _selectedKeys.collect { refreshSelectionReadState(it) }
        }
        // Remember, for every key AS IT IS TICKED, the numbering its row carries — the fact a
        // destruction opposes later to what the row carries then (#99). Here and nowhere else.
        viewModelScope.launch {
            _selectedKeys.collect { rememberTickedNumbering(it) }
        }
        // Tell the push side which inbox is on screen. A user-initiated arm may silently reseed
        // the baseline of the inbox the user is looking at, and only that one — in the unified
        // view that is every account's (see shouldResetBaseline, in the push package). This is
        // the only place the answer exists: the selection is view state and nothing persists it.
        viewModelScope.launch {
            selection.collect { PushController.unifiedInboxVisible = it is Sel.Unified }
        }
        // Remember the view so a cold start reopens on it. The account is read HERE, at the moment
        // of the write, and not captured: what makes the pair the arriving account's on a switch is
        // RootViewModel.switchAccount(), which calls accountStore.setCurrent(id) BEFORE InboxScreen's
        // LaunchedEffect(currentAccountId) gets to onAccountChanged() — so by the time the new
        // selection is written, store.currentId() already answers the account being switched to.
        // ⚠ That leaves a window: between setCurrent(B) and onAccountChanged(), the store already
        // says B while [selection] still holds a view of A, so a refreshFolder() landing inside it
        // would store (B, a folder id B's own server returned) — an id valid in B, i.e. a memory
        // that can be stale but never crossed between accounts, with restoreSelection's account
        // guard as the net.
        viewModelScope.launch {
            selection.collect { store.setStoredView(encodeSelection(store.currentId(), it)) }
        }
        // The END of an indicator stretch is DERIVED from the truth, in one place, rather than
        // written into the three arms of refresh()'s `when` — those are pinned word for word by
        // BoundedRefreshWiringLintTest and carry #130's decisions, and a fourth writer of the same
        // fact is a fourth chance to forget one. Every path that stops a refresh goes through
        // `status`, including the ones refresh() never sees (a superseding refresh's arms).
        viewModelScope.launch {
            status.map { it.refreshing }.distinctUntilChanged().collect { busy ->
                if (!busy) refreshRun.value = refreshRun.value?.copy(endedAt = clock())
            }
        }
    }

    override fun onCleared() {
        connectivity.stop()
        // No list on screen: fall back to the safe answer (announce rather than swallow).
        PushController.unifiedInboxVisible = false
        super.onCleared()
    }

    /**
     * Refresh a beat after the network came back — settled, so a flapping connection coalesces
     * into a single reconcile (and [refresh] itself cancels-and-replaces any refresh already in
     * flight), then retried a few times on a widening gap ([ReconnectRefresh]).
     *
     * An error left over from a refresh that failed *during* the outage survives the outage
     * itself and keeps a notice up until some later refresh happens to succeed (the user pulling
     * to refresh) — since [refreshNotice] shows a failure once the device is back online, that
     * stale error would now be shown as its own technical failure rather than as the outage it
     * really was. Connectivity coming back makes it stale, so drop it right away; the attempts
     * below put a real one back if the server is genuinely unreachable.
     */
    private fun onReconnected() {
        reconnectJob?.cancel()
        clearRefreshError()
        reconnectJob = viewModelScope.launch {
            // The watcher fires as soon as the link is back, without waiting for the system's
            // captive-portal validation (#65: users who block those probes never get it). The
            // link may not be routable for a beat — a VPN tunnel takes seconds to re-handshake —
            // so an early attempt is expected to fail and is retried rather than reported. The
            // banner stays up throughout: the gate only calls it online once one of these
            // attempts comes back.
            repeat(ReconnectRefresh.MAX_TRIES) { attempt ->
                delay(ReconnectRefresh.delayBeforeMs(attempt))
                refresh()
                val mine = refreshJob
                mine?.join()
                // Someone refreshed on top of us (a pull, an account switch): that result is the
                // one the user is looking at — leave it alone and stop retrying behind their back.
                if (refreshJob !== mine) return@launch
                val error = status.value.error ?: return@launch
                status.value = status.value.copy(
                    error = ReconnectRefresh.errorAfterAttempt(attempt, error),
                )
            }
        }
    }

    /** Drop a stale refresh failure, leaving the in-flight state alone. */
    private fun clearRefreshError() {
        if (status.value.error != null) status.value = status.value.copy(error = null)
    }

    /**
     * Re-point the inbox at the now-current account when the user switches accounts.
     * Selection + header are reset from the *cached* metadata immediately (so the list
     * shows the new account's cached mail at once, consistent with the header, instead of
     * lingering on the previous account's mail until a slow/failed network refresh), then
     * a refresh fetches newer mail.
     */
    fun onAccountChanged() {
        collapseThreads()
        // The pane empties with the account, on the same line the threads are collapsed and by the
        // SAME door as the four other switches of scope (#103). ⛔ Never "keep it when the anchor
        // is already the arriving account's": in the unified inbox a row of account B is opened
        // while A is current, and switching to B by the drawer would then keep a message that may
        // be in no folder the new list shows, with A's paging index still on it.
        paneOnViewChanged()
        // What a tapped notification asked for is the exception, and it is an exception this
        // method can NAME: the message that notification parked in [openFromNotification]. Put
        // back only onto the account it belongs to, and dropped WHATEVER the verdict — a park kept
        // past its own switch is replayed onto a stranger's list the next time she switches (#92).
        ReadingPaneRule.replayOnAccountChange(notificationAnchor, store.currentId())?.let { openInPane(it) }
        notificationAnchor = null
        currentAccountId.value = store.currentId()
        selection.value = Sel.Folder(store.inboxMailboxId())
        unifiedInboxScopes.value = store.allInboxScopes()
        // Cleared, not recomputed: the folder list in hand is the PREVIOUS account's, and pairs
        // built from it under the new account's id would page a sibling account's folders (#121).
        // The [mailboxes] flow re-scopes on currentAccountId and refills this within the tick.
        // Clearing the RECORD, not the scope derived from it: the scope is a combine now (it also
        // follows the subscription setting), so anything written straight into it is overwritten
        // by the next derivation — with the stale list still in the record.
        folderSnapshot.value = null to emptyList()
        meta.value = Meta(store.accountLabel(), store.inboxMailboxName(), store.unreadCount())
        refreshWatchedFolders()
        // The arriving account's own fold choices. IMAP ids are paths, so keeping the departing
        // account's registry folds its namesake folders here (#92/#121).
        refreshCollapsedFolders()
        refreshUnlessFresh()
    }

    /** The in-flight refresh, so a new one cancels-and-replaces it (never two reconciles at once). */
    private var refreshJob: Job? = null

    /**
     * The refresh the READER asked for in so many words: the pull gesture, and the two Retry
     * buttons. Its indicator is the answer to the gesture, so it is drawn at once, with none of the
     * grace that keeps the tern off a folder tap's 80 ms fetch (#178).
     *
     * ⛔ The flag is raised BEFORE the call and not after: `viewModelScope` is `Main.immediate`, so
     * the body of refresh()'s `launch` has already begun by the time refresh() returns — there is
     * no safe instant "after". It is cleared by the refresh that consumes it, so the next
     * background reconcile is graced again.
     */
    fun refreshRequested() {
        refreshAnswersAGesture = true
        refresh()
    }

    /**
     * Reconcile, UNLESS this very view came back successfully less than 30 s ago (#178).
     *
     * The reporter opens and closes the app all day, and every launch, account switch and folder
     * tap fired a full reconcile she never asked for. The register is persisted and the decision is
     * [isFresh]'s, run by `RefreshFreshnessTest`; this only reads the store and the clock.
     *
     * ⛔ This door is for the SIX reconciles that merely accompany a change of view. It is never
     * put in front of [onReconnected] — the only thing that ever clears a FAILED reachability,
     * which reads [refreshJob] straight after each call and would stop at its first attempt with
     * the offline banner up (#65) — nor in front of [refreshRequested] (the pull and the two Retry
     * buttons: a gesture is always answered), nor [forceRefresh], nor any of the reconciles that
     * follow a write. `RefreshFreshnessWiringLintTest` holds that list.
     *
     * ⭐ The stale error is dropped FIRST, unconditionally: a change of view never inherits the
     * failure of the view before it. A fresh view returns above [refresh]'s opening line, the one
     * that resets [status] whole, so one account's failed reconcile kept its red notice over
     * another account's correct, up-to-date rows for the 30 s of the window — and over a folder
     * with nothing cached, the whole error scene with its Retry button stood in for the empty-folder
     * one. At the head and not in front of the `return`: the same screen either way (the path taken
     * rewrites [status] whole anyway), one branch fewer, and the invariant said in one word.
     */
    private fun refreshUnlessFresh() {
        clearRefreshError()
        if (isFresh(decodeFreshness(store.refreshFreshness()), currentScopes(), clock())) return
        refresh()
    }

    /**
     * Stamp [scopes] as reconciled now, pruning whatever fell out of the window on the way through
     * ([recordFresh]) — which is what keeps the register the size of one reader's last 30 seconds.
     *
     * Called from ONE place: the Delivered arm of [refresh]. A failed or abandoned refresh brought
     * nothing back, and stamping it would hold a stale list on screen for 30 s while the reader
     * watches it fail to update.
     */
    private fun rememberReconciled(scopes: List<Pair<String, String>>) {
        val register = recordFresh(decodeFreshness(store.refreshFreshness()), scopes, clock())
        store.setRefreshFreshness(encodeFreshness(register))
    }

    fun refresh() {
        refreshJob?.cancel()
        val instant = refreshAnswersAGesture
        refreshAnswersAGesture = false
        refreshRun.value = startRefreshRun(refreshRun.value, clock(), instant)
        status.value = Status(refreshing = true, error = null)
        refreshJob = viewModelScope.launch {
            // ⚠ Read HERE, not in the Delivered arm: the selection can move while this refresh is
            // in flight (a folder tap, a tapped notification, an account switch), and stamping a
            // folder this refresh never read is exactly how a stale list would pass for fresh.
            val reconciled = currentScopes()
            try {
                // #130: the fourth end of a refresh — never coming back — used to be covered by
                // nothing, and the list then announced a refresh in progress for ever. The bound
                // and the reason for its size live in [REFRESH_BUDGET_MS]; nothing is passed here
                // on purpose, so the shipped budget has exactly one home.
                val outcome = refreshWithin {
                    when (val sel = selection.value) {
                        Sel.Unified -> refreshUnified()
                        Sel.Unread -> refreshUnreadScope()
                        is Sel.Folder -> refreshFolder(sel.id)
                    }
                }
                when (outcome) {
                    RefreshOutcome.Delivered -> {
                        status.value = Status(refreshing = false, error = null)
                        // #65: every refresh doubles as the connectivity probe. A killswitch VPN is
                        // invisible to the framework (the Wi-Fi under the tunnel stays up and NOT_VPN),
                        // so what the requests live is the only thing that can correct what it claims.
                        connectivity.reportSuccess()
                        // #178: only a refresh that LANDED answers for its view. The scopes are
                        // the ones read when this refresh started.
                        rememberReconciled(reconciled)
                    }
                    is RefreshOutcome.Failed -> {
                        val cause = outcome.cause
                        status.value = Status(refreshing = false, error = cause.message ?: cause.javaClass.simpleName)
                        connectivity.reportFailure(cause)
                    }
                    RefreshOutcome.AbandonedAtBudget -> {
                        // ⛔ No connectivity.reportFailure here, deliberately: nothing has said the
                        // device is offline — the refresh simply never came back — and the offline
                        // banner on a phone that is plainly online IS defect #65. An abandoned wait
                        // is an error, with its own wording, which is what RefreshNotice expects.
                        val timedOut = getApplication<Application>().getString(R.string.sync_error_timed_out)
                        status.value = Status(refreshing = false, error = timedOut)
                    }
                }
            } catch (c: CancellationException) {
                throw c // a superseding refresh cancelled us — don't stomp its status
            } catch (t: Throwable) {
                status.value = Status(refreshing = false, error = t.message ?: t.javaClass.simpleName)
                connectivity.reportFailure(t)
            }
        }
    }

    private suspend fun refreshFolder(mailboxId: String?) {
        val credentials = store.load() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
        val window = store.syncWindow(credentials.id)
        val pruneBefore = window.maxAgeDays?.let {
            System.currentTimeMillis() - it.toLong() * MILLIS_PER_DAY
        }
        val updated = repo.refresh(credentials, mailboxId, window.limit, pruneBefore)
        if (updated.mailboxId == store.inboxMailboxId() || mailboxId == null) {
            // Keep the cached inbox metadata fresh for offline display.
            store.saveInboxMeta(updated.mailboxId, updated.mailboxName, updated.accountName, updated.unreadCount)
            // Codeberg #50: a reply landed by THIS refresh must trigger unarchive-on-reply
            // too — with the app in the foreground this is the path new mail arrives by.
            // No-op unless the toggle is on; never fails the refresh.
            runCatching { FetchAndNotify.onInboxRefreshed(getApplication(), credentials, updated.mailboxId) }
        }
        selection.value = Sel.Folder(updated.mailboxId)
        meta.value = Meta(updated.accountName, updated.mailboxName, updated.unreadCount)
    }

    /**
     * Pull-to-refresh in the "unread" view: the account's inbox AND every other folder the view
     * pages, in one pass.
     *
     * ⭐ Why every folder. This view pages the `emails` cache, and that cache only ever holds the
     * inbox, the folders the user has actually VISITED, and the IMAP watched ones. A user folder
     * never opened is simply not in it, so a refresh that synced the inbox alone would leave the
     * view true by construction and false in fact: it would show nothing from a folder it never
     * asked the server about, under a title promising the account's unread mail.
     *
     * ⭐ ONE call, [app.sterna.core.data.mail.MailRepository.refreshAccountFolders], because it is
     * the only entry point that covers both protocols — IMAP through `imap.loadWatchedFolders`,
     * JMAP through one `syncMailbox` per target — and both write the same `emails` cache the pager
     * reads. A JMAP-only path written here would be a second thing to keep in step with the first.
     *
     * ⛔ The `limit` is [UNREAD_SCOPE_FOLDER_LIMIT] and NOT the account's sync window, unlike
     * [refreshFolder]'s, because it does not mean the same thing here: one folder's window is "how
     * much of it to keep", this one is "how much recent mail to LOOK AT in each of N folders read
     * in a single pass that holds them all". The whole reason, and why bounding it truncates
     * nothing, is written on the constant.
     *
     * ⛔ It does NOT write [selection] and it does NOT write [meta], and that is the whole reason it
     * is a separate function rather than a call to [refreshFolder]: that one ends on
     * `selection.value = Sel.Folder(updated.mailboxId)`, so the first pull-to-refresh in this view
     * would drop the user into the Inbox folder — the view ejecting the reader who opened it. The
     * cache is what this fills; the pager reacts to the cache on its own.
     *
     * ⛔ `onMissing` is left at its no-op default. Its production caller ([FetchAndNotify]) uses it
     * to prune the account's WATCHED flag for a folder the server no longer lists — that flag is a
     * user intent about push, not this view's business, and turning it off because a list built for
     * a different purpose could not find the folder would be the view spending somebody else's
     * setting. The folder simply drops out of the scope on the next folder sync.
     *
     * ⚠ Two things this deliberately does NOT do, both of which [refreshFolder] does. It writes no
     * `saveInboxMeta` (`refreshAccountFolders` returns folder pages, not an unread count — the count
     * on the account row stays whatever the last inbox sync left, and the drawer's own counters are
     * written by the repository). And it applies no AGE PURGE: `pruneBefore` is a parameter of
     * `repo.refresh` and there is none here. ⛔ That is not the same as "this path deletes nothing"
     * — on JMAP `syncMailbox` removes rows by three routes of its own (a server-stated deletion, the
     * ghost sweep, the mailbox reconcile), and what it RETAINS is sized by the account's window read
     * from the store, never by [UNREAD_SCOPE_FOLDER_LIMIT] (`SyncPaging.fullQuerySizing`).
     */
    private suspend fun refreshUnreadScope() {
        val credentials = store.load() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
        // ⚠ [refreshAccountFolders] opens on `rekeyWatchedFolders`, which PERSISTS the account (a
        // `saveAccounts`, hence an `accountsFlow` emission) for every id whose `decodeMailboxPath`
        // differs from itself. Nearly always none: the IMAP ids in the folder cache are already that
        // same function's output (`ImapClient.parseListFolders`) and JMAP ids carry no `&`. A cache
        // written by a version that stored the raw wire form and not re-listed since is the case
        // where it does fire — one account write per pull until the next folder listing, and no loss
        // either way: `AccountStore.replaceWatchedFolder` maps over the watched set that EXISTS, so
        // it can rename an entry but can never add a watch this view invented.
        // The set itself is [unreadRefreshTargets]'s decision, which `UnreadRefreshTargetsTest` runs.
        val refreshes = repo.refreshAccountFolders(
            credentials,
            extraFolderIds = unreadRefreshTargets(credentials.id, unreadScopes.value, store.inboxMailboxId()),
            includeInbox = true,
            limit = UNREAD_SCOPE_FOLDER_LIMIT,
        )
        // Codeberg #50: a reply landed by THIS refresh must trigger unarchive-on-reply too, exactly
        // as [refreshFolder] and [refreshUnified] do — with the app in the foreground this is the
        // path new mail arrives by, so leaving it out would stop the re-file for as long as the
        // reader stays in this view, and say nothing.
        // ⛔ The inbox is CHOSEN out of what came back ([refreshedInboxId]), not taken as the first
        // refresh: that ordering is an invariant of another module, and this hook writes — a wrong
        // aim advances another folder's notification baseline and re-files its threads. Same
        // question `refreshFolder` asks at its own hook, asked of a list.
        // Never fails the refresh; no-op unless the toggle is on.
        val refreshedInbox = refreshedInboxId(refreshes.map { it.mailboxId }, store.inboxMailboxId())
        if (refreshedInbox != null) {
            runCatching { FetchAndNotify.onInboxRefreshed(getApplication(), credentials, refreshedInbox) }
        }
    }

    private suspend fun refreshUnified() {
        val credentials = store.allCredentials()
        val result = repo.refreshAllInboxes(credentials)
        val metas = result.metas
        metas.forEach { store.saveInboxMetaFor(it.accountId, it.mailboxId, it.mailboxName, it.accountName, it.unreadCount) }
        // Codeberg #50: same foreground trigger as refreshFolder, per refreshed inbox.
        metas.forEach { meta ->
            credentials.firstOrNull { it.id == meta.accountId }?.let { cred ->
                runCatching { FetchAndNotify.onInboxRefreshed(getApplication(), cred, meta.mailboxId) }
            }
        }
        unifiedInboxScopes.value = store.allInboxScopes()
        meta.value = Meta(UNIFIED_LABEL, UNIFIED_LABEL, store.totalUnreadCount())
        // #65/#92: the unified refresh is also the connectivity probe. When every account failed and
        // none synced, treat it as the failure it is — otherwise refresh() below calls reportSuccess
        // and erases the offline banner the killswitch should have raised. A single unreachable
        // account does not trip this (some mail still came back), so the banner won't flicker.
        if (result.isConnectivityFailure) throw result.failures.first()
    }

    /** Switch to the cross-account unified inbox. */
    fun selectUnified() {
        if (selection.value is Sel.Unified) return
        collapseThreads()
        paneOnViewChanged()
        selection.value = Sel.Unified
        unifiedInboxScopes.value = store.allInboxScopes()
        meta.value = Meta(UNIFIED_LABEL, UNIFIED_LABEL, store.totalUnreadCount())
        refreshUnlessFresh()
    }

    /**
     * Switch to this account's unread mail across its folders ([Sel.Unread]).
     *
     * Threads are collapsed like every other switch of scope: an unfolded conversation belongs to
     * the list it was unfolded in, and its members are streamed for that list's folders.
     *
     * ⭐ [meta] is REWRITTEN, and the account label is the whole reason. This view keeps the top
     * bar's second line — it is a view of one account and that line is the only thing on screen
     * saying which — while `meta.accountName` holds the internal literal `UNIFIED_LABEL` for as long
     * as the unified inbox was the last thing shown. Arriving here from it, the subtitle would read
     * "All inboxes", in English, under a title that promises one account's mail.
     *
     * ⛔ The scope itself is NOT recomputed here. It is written in one place, the [mailboxes]
     * `onEach`, from the folder list that flow was scoped to, and it is already current — a second
     * resolution, from whatever the store says at this instant, is the #121 mistake and would be a
     * third writer of a value `PageKeyWiringLintTest` pins to two.
     */
    fun selectUnread() {
        if (selection.value is Sel.Unread) return
        collapseThreads()
        paneOnViewChanged()
        selection.value = Sel.Unread
        meta.value = Meta(store.accountLabel(), store.inboxMailboxName(), store.unreadCount())
        refreshUnlessFresh()
    }

    fun select(mailbox: Mailbox) {
        select(mailbox, emptyPane = true)
    }

    /**
     * [emptyPane] is false on ONE path: the folder a tapped notification came from (#91),
     * [applyNotificationFolder]. It lands after the anchor that notification posted in the pane —
     * on a cold start or after an account switch the folder list arrives a beat later — and
     * emptying there would take the very message the tap asked for off the right (#103).
     */
    private fun select(mailbox: Mailbox, emptyPane: Boolean) {
        if (selection.value == Sel.Folder(mailbox.id)) return
        collapseThreads()
        if (emptyPane) paneOnViewChanged()
        selection.value = Sel.Folder(mailbox.id)
        meta.value = Meta(store.accountLabel(), mailbox.name, mailbox.unreadEmails)
        refreshUnlessFresh()
    }

    /**
     * Put the list on the folder a tapped new-mail notification came from (issue #91), so Back
     * out of the message lands in a list that actually holds it. The account half of the same
     * rule is [app.sterna.ui.NotificationAccountSwitch], applied by the caller just before this.
     *
     * The verdict itself is [app.sterna.ui.NotificationFolderSwitch] and needs the account's
     * folder list, which is exactly what may not be loaded yet at this instant: a notification
     * for ANOTHER account switches the account first, and the new account's folders only arrive
     * a beat later (an empty list means "not known yet", never "the folder is gone"). So the
     * request is parked and judged on the first loaded folder list — the one already on hand
     * when the notification is for the current account, the one that follows the switch
     * otherwise. Consumed on that first verdict whatever it is: a request that cannot be
     * honoured (its account never became current) is dropped rather than kept to fire later.
     */
    fun showFolderFromNotification(accountId: String, mailboxId: String, emailId: String) {
        notificationFolder = Triple(accountId, mailboxId, emailId)
        if (currentAccountId.value == accountId) applyNotificationFolder(state.value.mailboxes)
    }

    /** Judge a parked [notificationFolder] against a freshly loaded [folders] list. */
    private fun applyNotificationFolder(folders: List<Mailbox>) {
        val (accountId, mailboxId, emailId) = notificationFolder ?: return
        // An empty list is "not known yet" — same conservative reading as [selectionIsGone].
        // Keep waiting rather than deciding on nothing.
        if (folders.isEmpty()) return
        notificationFolder = null
        if (currentAccountId.value != accountId) return
        val target = NotificationFolderSwitch.resolve(
            notificationMailboxId = mailboxId,
            selectedMailboxId = (selection.value as? Sel.Folder)?.id,
            unifiedView = selection.value is Sel.Unified,
            knownMailboxIds = folders.map { it.id },
        )
        // ⛔ [NotificationFolderSwitch.resolve] gets the WHOLE list — a folder the setting hides is
        // not a folder it does not know, and its "unknown" branch means deleted. It is its RESULT
        // that is judged, by the same rule the fallback runs ([notificationFolderToShow]): the flag
        // is the current account's, which the guard two lines up is what makes true.
        val show = notificationFolderToShow(target, folders, showOnlySubscribed.value) ?: return
        // ⛔ Not a flat `emptyPane = false`, and no longer "was a message parked?" either — that
        // question is answered by a park the ACCOUNT SWITCH spends, so a notification for another
        // account read `null` here and emptied the very message it had just posted. The rule below
        // asks the only order-proof question instead: is what the pane holds MY message? Applied
        // BEFORE select(), which is then told to leave the pane alone, having nothing left to
        // decide about it.
        setReadingPane(ReadingPaneRule.onFolderFromNotification(_readingPane.value, emailId, accountId))
        folders.firstOrNull { it.id == show }?.let { select(it, emptyPane = false) }
    }

    /**
     * Name the RESTORED folder from a freshly loaded [folders] list. The decision is
     * [restoredFolderMeta]'s, which a JVM test runs; this only feeds it, spends the one shot when
     * it says the question is settled, and writes what it hands back.
     *
     * ⚠ Known and deliberately unguarded: should Room's FIRST [mailboxes] emission land AFTER a
     * successful [refreshFolder], this overwrites that refresh's header with `folder.unreadEmails`
     * where it wrote `updated.unreadCount` — the selection check cannot see it, both readings come
     * out of the same database, and the count is drawn nowhere today. No guard for it (rules 5, 6).
     */
    private fun applyRestoredMeta(folders: List<Mailbox>) {
        val step = restoredFolderMeta(restoredFolder, selection.value, folders, store.accountLabel())
        if (!step.settled) return
        restoredFolder = null
        step.meta?.let { meta.value = it }
    }

    /** Return to the account's Inbox — Back from any other folder lands here. */
    fun showInbox() {
        val inboxId = store.inboxMailboxId()
        if (selection.value == Sel.Folder(inboxId)) return
        collapseThreads()
        paneOnViewChanged()
        selection.value = Sel.Folder(inboxId)
        meta.value = Meta(store.accountLabel(), store.inboxMailboxName(), store.unreadCount())
        refreshUnlessFresh()
    }

    /** Drop all inline-expansion state — e.g. when the list's contents change underneath it. */
    private fun collapseThreads() {
        _expandedThreads.value = emptySet()
        _threadMembers.value = emptyMap()
        completedThreads.clear()
        threadOrder.clear()
        maskedMembers.clear()
    }

    /** Swipe action: toggle read/unread (cache update drives the list). */
    fun toggleRead(email: Email) {
        val targetSeen = !email.isSeen
        patchThreadMembersSeen(setOf(email.emailKey()), targetSeen)
        viewModelScope.launch {
            val credentials = credentialsFor(email) ?: return@launch
            runCatching { repo.setRead(credentials, email.id, targetSeen) }
                .onFailure { reportActionFailed("setRead (swipe)", it) }
            if (targetSeen) dismissReadNotifications(listOf(email.emailKey()))
        }
    }

    /** Swipe action: delete (move to Trash). */
    fun delete(email: Email) {
        val credentials = credentialsFor(email) ?: return
        viewModelScope.launch {
            // A delete that only moves to Trash is reversible immediately (Undo moves it back).
            // A delete that would DESTROY (the message is already in the Trash) is held behind the
            // Undo window instead, so it is undoable too (Codeberg #23) — and its snackbar says so.
            // The probe can hit the network (folder lookup); if it fails (offline), fall back to
            // the move path, whose own failure is handled and can never destroy anything.
            if (runCatching { repo.deleteWouldDestroy(credentials, email) }.getOrDefault(false)) {
                heldBackDestroy(listOf(email), getApplication<Application>().getString(R.string.status_message_deleted_forever))
            } else {
                swipeRemove(email, getApplication<Application>().getString(R.string.status_message_deleted)) { c, id -> repo.delete(c, id) }
            }
        }
    }

    /**
     * Permanently destroy [emails], but hold the destroy back behind the Undo window: evict the
     * rows now, fire the destroy when the window elapses, and let [undoDelete] cancel it. Same
     * model as Empty trash, shared by swipe delete and bulk delete so every delete UX behaves the
     * same (Codeberg #23). A new held-back delete supersedes a pending one (the earlier set, left
     * un-undone, is destroyed at once). The destroy itself is PERSISTED WorkManager work with an
     * initial delay — it survives this ViewModel and the process, so a confirmed permanent delete
     * can no longer be silently dropped by killing the app inside the window; the inner coroutine
     * only times the snackbar. Batched per account (one Email/set / UID STORE+EXPUNGE per chunk),
     * so holding back several hundred in-Trash messages destroys them in a few shots (#29).
     */
    private suspend fun heldBackDestroy(emails: List<Email>, label: String) {
        val targets = emails.mapNotNull { e -> credentialsFor(e)?.let { Triple(it, e.id, e.mailboxId.orEmpty()) } }
        if (targets.isEmpty()) return
        // Frozen HERE, with the confirmation, and carried by the work: an IMAP UID means something
        // only inside one numbering, and the destroy fires minutes later (#99). Read at execution
        // instead, it would be the numbering of whatever the folder became.
        val numbering = recordedNumbering(targets)
        flushPendingDestroy()
        pendingDeleteTargets = targets
        pendingDeleteNumbering = numbering
        pendingDeleteEmails = emails
        viewModelScope.launch {
            // The eviction is local and immediate, so the mask only has to cover it: once the rows
            // are out of the cache the live reading has nothing to put back, and if the destroy is
            // later undone — or the worker gives up and the folder re-syncs — the members return
            // on their own instead of staying under a mask the Undo path alone knew how to lift.
            hidingThreadMembers(emails.mapTo(mutableSetOf()) { it.emailKey() }) {
                // Batched per account, like the destroy that follows: this is the bulk delete path,
                // and evicting one id at a time scanned the whole search index once per message.
                targets.groupBy({ it.first.id }, { it.second }).forEach { (accountId, ids) ->
                    repo.evictAll(accountId, ids)
                }
            }
            dropSearchResults(emails.mapTo(mutableSetOf()) { it.emailKey() })
            _pendingDelete.value = label
            targets.groupBy { it.first.id }.forEach { (accountId, rows) ->
                MessageDestroyWorker.schedule(getApplication(), accountId, foldersToDestroy(accountId, rows, numbering), PURGE_HOLD_BACK_MS)
            }
            pendingDeleteJob = viewModelScope.launch {
                delay(PURGE_HOLD_BACK_MS)
                pendingDeleteTargets = emptyList()
                pendingDeleteNumbering = emptyMap()
                pendingDeleteEmails = emptyList()
                _pendingDelete.value = null
            }
        }
    }

    /**
     * One account's held-back rows, split into the requests the worker will fire: by the folder
     * each message was in when the user confirmed — what the destroy is checked against on JMAP
     * (#122) — AND by the numbering that message was READ under, which is what an IMAP destroy
     * opposes to the server (#99). An empty folder key means the cached row named no folder: the
     * destroy then has nothing to verify its order with, and destroys nothing — the safe end.
     *
     * ⭐ The split itself is [UidValidity.imapDestroyRoutes], a pure function, so the rule can be
     * RUN by a test instead of restated by one: this class needs an `Application` and a Room
     * database, and a routing nothing executes is a routing a mutation walks through. Two rows of
     * ONE folder read under two numberings therefore become two requests, each opposing its own
     * number — one command cannot oppose both. On JMAP every numbering is null and the split is
     * exactly the by-folder grouping this replaced.
     */
    private fun foldersToDestroy(
        accountId: String,
        rows: List<Triple<AccountCredentials, String, String>>,
        numbering: Map<Pair<String, String>, Long?>,
    ): List<MessageDestroyWorker.FolderDestroy> {
        val routes = UidValidity.imapDestroyRoutes(rows.map { it.second to it.third }) { numbering[accountId to it] }
        return routes.map { MessageDestroyWorker.FolderDestroy(it.mailboxId, it.emailIds, it.uidValidity) }
    }

    /** The IMAP numbering every message in [targets] was READ under — the stamp on its own cached
     *  row, read at the confirmation, which is the only moment this may be read (see
     *  [pendingDeleteNumbering]). ⛔ Not the folder's record: a background pass that met a
     *  renumbering has already realigned that one, and opposing it to itself licenses the expunge
     *  this exists to refuse (#99). One read per account, not per message; a row the cache no
     *  longer holds is absent from the answer, i.e. nothing to oppose. */
    private suspend fun recordedNumbering(
        targets: List<Triple<AccountCredentials, String, String>>,
    ): Map<Pair<String, String>, Long?> {
        return targets.groupBy({ it.first }, { it.second })
            .flatMap { (credentials, emailIds) ->
                repo.numberingRowsWereReadUnder(credentials, emailIds).map { (emailId, uidValidity) -> (credentials.id to emailId) to uidValidity }
            }.toMap()
    }

    /** Commit any held-back destroy immediately (a new delete supersedes the pending one). */
    private fun flushPendingDestroy() {
        pendingDeleteJob?.cancel()
        pendingDeleteJob = null
        val targets = pendingDeleteTargets
        // Not a suspending function, and it must not become one: what it replays is the destroy
        // the user confirmed EARLIER, so it re-enqueues the numbering captured then (#99).
        val numbering = pendingDeleteNumbering
        pendingDeleteTargets = emptyList()
        pendingDeleteNumbering = emptyMap()
        pendingDeleteEmails = emptyList()
        _pendingDelete.value = null
        targets.groupBy { it.first.id }.forEach { (accountId, rows) ->
            MessageDestroyWorker.flushNow(getApplication(), accountId, foldersToDestroy(accountId, rows, numbering))
        }
    }

    /** Cancel the held-back destroy and restore the rows (nothing was destroyed yet). */
    fun undoDelete() {
        pendingDeleteJob?.cancel()
        pendingDeleteJob = null
        pendingDeleteTargets.map { it.first.id }.distinct().forEach {
            MessageDestroyWorker.cancelDestroy(getApplication(), it)
        }
        val restored = pendingDeleteEmails
        pendingDeleteTargets = emptyList()
        pendingDeleteNumbering = emptyMap()
        pendingDeleteEmails = emptyList()
        _pendingDelete.value = null
        restoreSearchResults(restored.map { it.emailKey() })
        // Nothing to un-mask: the mask came down with the eviction that raised it. The rows the
        // re-query brings back are listed again by the live reading, like any other write.
        // A full re-query, not an incremental refresh: the messages were only evicted locally and
        // are still in Trash on the server, so queryChanges reports no change and would leave the
        // view empty. Dropping the sync cursors forces a fresh query that brings them back.
        forceRefresh()
        // The re-query brings back collapsed representatives only — complete the restored
        // conversations (after the refresh) so every member is cached and reachable again.
        completeThreadsAfterAction(restored)
    }

    /** Re-query the current folder from scratch (drops sync cursors first) so locally-evicted but
     *  server-present messages reappear — an incremental refresh alone re-fetches nothing. */
    private fun forceRefresh() {
        repo.resetSyncState()
        refresh()
    }

    /** Swipe action: archive. */
    fun archive(email: Email) = swipeRemove(email, getApplication<Application>().getString(R.string.status_message_archived)) { c, id -> repo.archive(c, id) }

    /** Swipe action when already inside Archive: move the message back to the Inbox. */
    fun unarchive(email: Email, inboxId: String) = swipeRemove(email, getApplication<Application>().getString(R.string.status_message_unarchived)) { c, id -> repo.moveToMailbox(c, id, inboxId) }

    /**
     * Move ONE message to [targetMailboxId] — the reader's move-to-folder (#73).
     *
     * Deliberately the same [swipeRemove] path archive and delete from the reader take: the
     * count nudge, the row leaving on the server's ack, and above all the Undo (its snackbar
     * shows on the list the reader pops back to). [email] carries its own account, so a message
     * read from the unified inbox moves inside ITS account — and the picker that produced
     * [targetMailboxId] listed that same account's folders (#92).
     */
    fun moveTo(email: Email, targetMailboxId: String, targetAccountId: String? = null) {
        // Into another account's folder (#189): a different path entirely, with no Undo.
        if (isCrossAccountMove(targetAccountId, email.accountId)) {
            moveToAccount(email, requireNotNull(targetAccountId), targetMailboxId)
            return
        }
        swipeRemove(email, getApplication<Application>().getString(R.string.status_message_moved)) { c, id ->
            repo.moveToMailbox(c, id, targetMailboxId)
        }
    }

    /**
     * Move ONE message into a folder of ANOTHER account (#189): the copy is confirmed on B, then
     * the original goes to A's bin ([MailRepository.moveToAccount]); nothing is ever destroyed.
     *
     * ⛔ No Undo, and none to come (A3): taking it back would be a second two-sided operation,
     * with the original in A's bin and the copy on B, both of them on screen. So `_undo` is never
     * written here — ONE banner says what happened, in the words of the verdict:
     *  - [CrossAccountMove.Moved]: "Moved to B › folder";
     *  - [CrossAccountMove.CopiedNotRemoved]: the copy exists and A's original stayed — a duplicate
     *    the user can see, never a loss, and the banner says exactly that;
     *  - [CrossAccountMove.Failed]: nothing was written; the row comes back like a failed swipe.
     *
     * The verdict's reason goes to logcat, where diagnosis needs it; the banner does not carry it.
     * The repository turns every exception into a verdict, so a failure here carries no throwable
     * to read "offline" off: the banner says offline when the phone has no usable network, failed
     * otherwise.
     */
    private fun moveToAccount(email: Email, targetAccountId: String, targetMailboxId: String) {
        val source = credentialsFor(email) ?: return
        val app = getApplication<Application>()
        val target = store.credentials(targetAccountId)
        if (target == null) {
            _message.value = app.getString(R.string.status_action_failed)
            return
        }
        viewModelScope.launch {
            val key = email.emailKey()
            dropSearchResults(setOf(key))
            val result = hidingThreadMembers(setOf(key)) {
                // ⛔ NOTHING FROZEN, and said so out loud IN THE TYPE. This gesture starts on the
                // message that is OPEN: there is no tick, so there is no earlier numbering to
                // oppose, and NothingFrozen is what keeps main's behaviour — the read and the bin
                // fall back on the folder's record. It is NOT `Frozen(null)`: that shape means "a
                // tick froze nothing at all" and is refused. Written rather than left to the
                // default so that this line and the selection's differ by a token a paste changes.
                runCatching { repo.moveToAccount(source, email, target, targetMailboxId, FrozenNumbering.NothingFrozen) }
                    .getOrElseUnlessCancelled { CrossAccountMove.Failed(it.toString()) }
            }
            val folder = folderLabel(target.id, targetMailboxId)
            // Which sentence belongs to which verdict is crossAccountMessageRes's, executed by a
            // test: "moved" over a copy whose original stayed is a duplicate announced as a move.
            val words = crossAccountMessageRes(result, online = hasUsableNetwork(app))
            when (result) {
                CrossAccountMove.Moved ->
                    _message.value = app.getString(words, accountLabel(target.id), folder)
                is CrossAccountMove.CopiedNotRemoved -> {
                    android.util.Log.w("SternaInbox", "move to account: copied, original not removed: ${result.reason}")
                    restoreSearchResults(listOf(key))
                    _message.value = app.getString(words, accountLabel(target.id), folder, accountLabel(source.id))
                }
                is CrossAccountMove.Failed -> {
                    android.util.Log.w("SternaInbox", "move to account failed: ${result.reason}")
                    _message.value = app.getString(words)
                    restoreSearchResults(listOf(key))
                    // As swipeRemove's failure leg, minus the rewind: this move starts in the
                    // reader, no row flew off. The row was never dropped locally — reconcile.
                    refresh()
                }
            }
        }
    }

    /** How the banners name an account: the drawer's own label, or the id when it is gone. */
    private fun accountLabel(accountId: String?): String =
        store.accountsFlow.value.firstOrNull { it.id == accountId }?.label() ?: accountId.orEmpty()

    /** How the banners name a folder: as the picker showed it ([mailboxLabel]), from the cache. */
    private suspend fun folderLabel(accountId: String, mailboxId: String): String {
        val mailbox = repo.observeMailboxes(accountId).first().firstOrNull { it.id == mailboxId }
        return mailbox?.let { mailboxLabel(getApplication<Application>(), it.role, it.name) } ?: mailboxId
    }

    // ---- whole-thread swipe (collapsed conversation rows act on the whole conversation) ----

    /** A thread's full membership from the cache (representative included), or [rep] alone. */
    private suspend fun threadMessages(rep: Email): List<Email> {
        val accountId = rep.accountId ?: store.load()?.id ?: return listOf(rep)
        return repo.cachedThreadEmails(accountId, currentMailboxIds(), threadKeyOf(rep).threadId)
            .ifEmpty { listOf(rep) }
    }

    /** Toggle read across a whole thread: mark all read if any is unread, else all unread. */
    fun toggleReadThread(rep: Email) {
        viewModelScope.launch {
            val members = threadMessages(rep)
            val targetSeen = members.any { !it.isSeen }
            patchThreadMembersSeen(members.mapTo(mutableSetOf()) { it.emailKey() }, targetSeen)
            members.forEach { m ->
                val credentials = credentialsFor(m) ?: return@forEach
                runCatching { repo.setRead(credentials, m.id, targetSeen) }
                    .onFailure { reportActionFailed("setRead (thread)", it) }
            }
            if (targetSeen) dismissReadNotifications(members.map { it.emailKey() })
        }
    }

    /** Toggle flag across a whole thread, following the representative's current state. */
    fun toggleFlagThread(rep: Email) {
        viewModelScope.launch {
            val flagged = !rep.isFlagged
            threadMessages(rep).forEach { m ->
                val credentials = credentialsFor(m) ?: return@forEach
                runCatching { repo.setFlagged(credentials, m.id, flagged) }
                    .onFailure { reportActionFailed("setFlagged (thread)", it) }
            }
        }
    }

    /**
     * Delete a whole conversation. Members whose delete would permanently destroy them (they are
     * already in the Trash) go through the held-back destroy — a real, cancelable Undo window,
     * never an inline destroy — while the rest take the move-to-Trash path with a regular Undo.
     * The per-member decision only ever picks held-back vs move, so a stale cached folder (or a
     * probe that fails offline) can at worst hold a move back behind Undo, never destroy a
     * message that wasn't in Trash.
     */
    fun deleteThread(rep: Email) {
        viewModelScope.launch {
            val members = threadMessages(rep)
            val (destroy, move) = members.partition { m ->
                credentialsFor(m)?.let { c -> runCatching { repo.deleteWouldDestroy(c, m) }.getOrDefault(false) } ?: false
            }
            dropThreadExpansion(threadKeyOf(rep))
            if (destroy.isNotEmpty()) {
                heldBackDestroy(destroy, getApplication<Application>().getString(R.string.status_message_deleted_forever))
            }
            if (move.isNotEmpty()) {
                // Mixed destroy+move: only the held-back destroy offers Undo — two snackbars
                // would queue on one host while the destroy clock runs, so the move executes
                // without its own (a pure move keeps it).
                threadSwipeRemove(rep, R.string.status_conversation_deleted, members = move, offerUndo = destroy.isEmpty()) { c, id -> repo.delete(c, id) }
            }
        }
    }
    fun archiveThread(rep: Email) = threadSwipeRemove(rep, R.string.status_conversation_archived) { c, id -> repo.archive(c, id) }
    fun unarchiveThread(rep: Email, inboxId: String) =
        threadSwipeRemove(rep, R.string.status_conversation_unarchived) { c, id -> repo.moveToMailbox(c, id, inboxId) }

    /** Drop a thread's inline-expansion state — its conversation is leaving the list. */
    private fun dropThreadExpansion(key: ThreadKey) {
        _expandedThreads.value = _expandedThreads.value - key
        _threadMembers.value = _threadMembers.value - key
        completedThreads -= key
        threadOrder.drop(key)
    }

    /**
     * Best-effort follow-up to an action that moved or restored whole conversations: for each
     * affected thread, drop the stale expansion snapshot and re-fetch the full membership so
     * every member is re-cached under its REAL current folder. Without this only the row(s) the
     * target folder's next sync happens to keep stay cached — the moved conversation collapses
     * to a chip-less single row whose other members are unreachable (the expand repair path is
     * gated on threadTotal > 1). Waits out any in-flight [refresh] first (the op's own
     * resetSyncState()+refresh() full re-query would prune rows cached before it ran), skips
     * threads with a held-back destroy pending (re-caching would resurrect the evicted rows
     * inside the Undo window), caps the fan-out (a select-all can span hundreds of threads),
     * and silently does nothing offline — the cache is then no worse than before.
     */
    private fun completeThreadsAfterAction(emails: List<Email>) {
        val heldKeys = pendingDeleteEmails.mapTo(mutableSetOf()) { threadKeyOf(it) }
        val reps = emails
            .filter { it.threadId != null && threadKeyOf(it) !in heldKeys }
            .distinctBy { threadKeyOf(it) }
        if (reps.isEmpty()) return
        // Invalidate every affected snapshot (cheap, local) so the next expand reloads fresh,
        // even for threads past the fetch cap.
        reps.forEach { dropThreadExpansion(threadKeyOf(it)) }
        viewModelScope.launch {
            refreshJob?.join()
            reps.take(MAX_THREAD_COMPLETIONS).forEach { rep ->
                val credentials = credentialsFor(rep) ?: return@forEach
                val threadId = rep.threadId ?: return@forEach
                runCatching { repo.fetchThreadMembers(credentials, threadId, currentMailboxIds()) }
            }
        }
    }

    /**
     * Remove every message of a thread ([members], or its full cached membership), run [op] per
     * message, then offer one Undo that restores the whole batch. Mirrors [swipeRemove] but for
     * a collapsed conversation.
     */
    private fun threadSwipeRemove(
        rep: Email,
        labelRes: Int,
        members: List<Email>? = null,
        offerUndo: Boolean = true,
        op: suspend (AccountCredentials, String) -> String?,
    ) {
        viewModelScope.launch {
            val acting = members ?: threadMessages(rep)
            dropThreadExpansion(threadKeyOf(rep))
            val entries = mutableListOf<UndoEntry>()
            var failed = false
            acting.forEach { m ->
                val credentials = credentialsFor(m) ?: return@forEach
                val mailboxId = m.mailboxId ?: return@forEach
                // The repo op is network-first: it drops the row and nudges counts on ack.
                runCatching { op(credentials, m.id) }
                    .onSuccess { dest -> entries += UndoEntry(m.id, m.accountId, mailboxId, dest) }
                    .onFailure { failed = true }
            }
            if (offerUndo && entries.isNotEmpty()) {
                _undo.value = UndoAction(entries, getApplication<Application>().getString(labelRes))
                scheduleUndoneDeparture()
            }
            if (failed) {
                _message.value = getApplication<Application>().getString(R.string.status_action_failed)
                refresh() // failed rows were never dropped locally — just reconcile the list
            }
            // Re-cache the moved conversation's members under their new folder (after any
            // reconcile above), so its row there keeps a truthful, expandable chip.
            completeThreadsAfterAction(acting)
        }
    }

    /**
     * Remove [email] optimistically (so the row leaves instantly — never stuck mid-swipe), run
     * the server [op], then either offer Undo on success or restore the row + report the error.
     */
    private fun swipeRemove(email: Email, label: String, op: suspend (AccountCredentials, String) -> String?) {
        val credentials = credentialsFor(email) ?: return
        viewModelScope.launch {
            // No pre-evict: the repo op is network-first — it drops the row AND nudges the drawer
            // counts from the true source once the server acknowledged. The swipe animation has
            // already flown the row off; it leaves the list when the ack lands. The op returns
            // the destination the message went to, for the Undo.
            dropSearchResults(setOf(email.emailKey()))
            // The UI row may not carry its source folder (e.g. a server-fetched thread member) —
            // fall back to the cached row, captured before the op drops it, so a moved message
            // always gets its Undo.
            val source = email.mailboxId ?: repo.cachedEmail(credentials.id, email.id)?.mailboxId
            hidingThreadMembers(setOf(email.emailKey())) { runCatching { op(credentials, email.id) } }
                .onSuccess { dest ->
                    if (source != null) {
                        _undo.value = UndoAction(listOf(UndoEntry(email.id, email.accountId, source, dest)), label)
                        scheduleUndoneDeparture()
                    }
                }
                .onFailure {
                    val app = getApplication<Application>()
                    // The exception text is a resolver/HTTP string — offline it was the raw
                    // 'Unable to resolve host', in English whatever the app's language, and it
                    // named nothing the reader could act on. It goes to logcat, where diagnosis
                    // needs it, whichever of the two sentences ends up on the snackbar.
                    android.util.Log.w("SternaInbox", "swipe action failed", it)
                    _message.value = app.getString(actionFailureMessage(it, online = hasUsableNetwork(app)))
                    restoreSearchResults(listOf(email.emailKey()))
                    // …and the browse list needs the same courtesy, but its row was never removed:
                    // it FLEW OFF, and the flight is not undone by re-emitting the row. The list
                    // key is stable, so the refresh below hands the same composition back with its
                    // animations at their take-off values. Say the swipe did not take; the row
                    // rewinds and clears the key.
                    _swipeRewind.value = _swipeRewind.value + email.emailKey()
                    refresh() // the failed row was never dropped locally — just reconcile the list
                }
        }
    }

    /** Move the last deleted/archived message(s) back to their original mailbox(es). */
    fun undo() {
        val action = _undo.value ?: return
        _undo.value = null
        // The gesture is being reversed, so its banners must stay: kill the deadline before the
        // restore, not after — the restore suspends, and the window is measured in seconds.
        undoDepartureJob?.cancel()
        restoreSearchResults(action.entries.map { EmailKey(it.accountId, it.emailId) })
        viewModelScope.launch {
            // Group by account and restore each account's whole set in one batch (one UID MOVE /
            // Email/set per source folder), so undoing a large selection doesn't hit the same
            // per-message server limits the forward batch avoids (Codeberg #29).
            var unrestored = 0
            action.entries.groupBy { it.accountId }.forEach { (accountId, entries) ->
                val credentials = accountId?.let { store.credentials(it) } ?: store.load() ?: return@forEach
                // Optimistic restore that STICKS: restoreAll re-tags the rows, marks them
                // recently-mutated (so the reconcile spares them) and reverses the count nudge.
                // Deliberately NO refresh here — a reconciling re-query would race the move-back.
                runCatching {
                    repo.restoreAll(
                        credentials,
                        entries.map { MailRepository.RestoreTarget(it.emailId, it.mailboxId, it.destMailboxId) },
                    )
                }
                    .onSuccess { unrestored += it.size }
                    .onFailure {
                        unrestored += entries.size
                        val app = getApplication<Application>()
                        // Same as the swipe, plus one thing of its own: this banner must never be
                        // null. A null error is RefreshNotice.NONE — a BANNER saying nothing,
                        // which is what a transport exception with no message used to produce
                        // here. The snackbar below still says "couldn't restore some messages"
                        // either way, so the Undo was never silent outright; the banner was.
                        // Note also that in airplane mode the reader sees the offline banner
                        // rather than this sentence: RefreshNotice puts OFFLINE ahead of ERROR
                        // on purpose (#65), and that order is not this fix's to change.
                        android.util.Log.w("SternaInbox", "undo restore failed", it)
                        status.value = Status(refreshing = false, error = app.getString(actionFailureMessage(it, online = hasUsableNetwork(app))))
                    }
            }
            if (unrestored > 0) {
                _message.value = getApplication<Application>().getString(R.string.status_restore_failed)
            }
            // restoreAll re-cached only the restored rows themselves — complete their
            // conversations so the members are filed truthfully again (no refresh involved,
            // so this can't race the optimistic restore).
            completeThreadsAfterAction(repo.cachedEmailsByIds(action.entries.mapTo(mutableSetOf()) { EmailKey(it.accountId, it.emailId) }))
        }
    }

    fun clearUndo() {
        _undo.value = null
    }

    /**
     * Take the banners of the Undo action just offered down once its window closes unused.
     *
     * The gesture is the ONLY thing that knows a message departed on the user's own instruction: a
     * local removal reaches no sync signal that is authoritative about it (`FolderRefresh
     * .departedIds` is empty by construction on IMAP, late and spare-shadowed on JMAP), and
     * ⛔ `departedIds` may not be fed from here — see `Notifications.departuresToCancel`'s KDoc. So
     * the banner is cancelled BESIDE the sync, from the gesture, on the keys captured at the
     * gesture ([undoBannerKeys]).
     *
     * ⛔ In `viewModelScope`, not in an effect of the screen. `clearUndo()` has one caller — the
     * snackbar's "no Undo" branch — and when the list leaves the composition that `LaunchedEffect`
     * is cancelled and NEITHER branch runs; hung there, a message deleted just before leaving the
     * list keeps its banner for good.
     *
     * ⛔ And the PREVIOUS gesture's deadline is deliberately not cancelled here. A new gesture
     * replaces the [UndoAction] on screen, but it does not undo the old one: that message did
     * depart, so its banner must still come down. Only [undo] cancels, and only the deadline of the
     * action it is reversing.
     *
     * ⚠ That [undoDepartureJob] is that same action's deadline holds because EVERY writer of
     * `_undo` that can put a bar on screen calls this function on the line right after: the two
     * swipes and [bulkBatched]. The one that does not is [bulk], and it is only reachable through
     * `snoozeSelected`, which passes no `undoLabelRes` — so `_undo` is never written there. ⛔ Give
     * [bulk] a labelled caller and this stops being true twice over: its bar would be `Indefinite`
     * with nothing to close it, and this field would name someone else's deadline.
     */
    private fun scheduleUndoneDeparture() {
        val action = _undo.value ?: return
        val keys = undoBannerKeys(action.entries)
        undoDepartureJob = viewModelScope.launch {
            delay(UNDO_BANNER_DISMISS_MS)
            // The window closes HERE, not on screen: the bar is Indefinite and LaunchedEffect is
            // keyed on `undo`, so clearing it takes the bar down with the banners — one clock.
            // Identity, and on the very action this deadline was posted for: a second gesture
            // performed inside the window owns `_undo` now, and its own window is not this one's
            // to close. `dismissBanners` no-ops on an empty list, so it needs no guard of its own.
            if (_undo.value === action) _undo.value = null
            dismissBanners(keys)
        }
    }

    /**
     * Empty the current Trash folder. The view clears as soon as the destroy list is taken,
     * but the actual permanent delete is held back for a few seconds so it can be undone
     * (like the delete snackbar). If not undone, the messages are destroyed on the server: the
     * purge is PERSISTED WorkManager work with an initial delay — it survives this
     * ViewModel and the process, like the folder delete — and the coroutine below
     * only times the snackbar.
     *
     * What gets destroyed is decided HERE, not when the work runs (Codeberg #99): the folder's
     * ids are snapshotted at confirmation and the purge destroys exactly that list. Moving a
     * message to Trash during the undo window used to hand it to the pending purge, which
     * re-read the folder and destroyed it too — mail the user had never designated, gone for
     * good on the server. Anything arriving after the tap is now simply not on the list.
     *
     * Order matters: snapshot BEFORE evicting the cached rows. The IMAP path (and the offline
     * JMAP fallback) reads the snapshot from that cache, so evicting first would freeze an
     * empty list and quietly empty nothing.
     *
     * The whole thing is scoped to `credentials.id`: the Trash of the account whose folder is
     * open, never a sibling account's same-numbered folder (issue #31). And a second tap on a
     * Trash already being emptied does nothing at all — see below.
     */
    fun emptyTrash() {
        val trashId = (selection.value as? Sel.Folder)?.id ?: return
        val credentials = store.load() ?: return
        val target = credentials.id to trashId
        // Tapping Empty again on a Trash that is ALREADY being emptied changes NOTHING: the order
        // stands, its snackbar keeps its own deadline, no second photo is taken, nothing is
        // cancelled (#99).
        //
        // Re-confirming used to freeze an EMPTY list — the first tap evicted the cached rows that
        // the IMAP path and the offline JMAP fallback read — and enqueue it under the same unique
        // work name, where REPLACE cancelled the first, real purge: two taps destroyed nothing at
        // all. Merely refusing to schedule that empty list was not enough: the second tap moves the
        // snackbar's dismissal to 5 s after ITS OWN tap (the label is identical, so the flow
        // conflates it and the snackbar simply stays up) while the destroy still fires 6 s after
        // the FIRST tap — for those seconds the screen offers an Undo on mail already destroyed,
        // and tapping it does nothing at all.
        //
        // Doing nothing is truthful (the Trash IS being emptied), it is the smallest possible
        // rule, and it keeps the Undo window aligned with the destroy that will actually fire.
        if (pendingPurgeTarget == target) return
        _pendingPurge.value = getApplication<Application>().getString(R.string.status_trash_emptied)
        pendingPurgeTarget = target
        purgeJob?.cancel()
        purgeJob = viewModelScope.launch {
            val purge = runCatching { repo.snapshotTrashPurge(credentials, trashId) }.getOrElseUnlessCancelled {
                // A cancellation is NOT a snapshot failure: this job is cancelled by an Undo, or
                // by a confirmation on another folder, and the teardown below would then clear a
                // pending purge that no longer belongs to it — snackbar gone, target null, the
                // destroy left unopposed. Hence getOrElseUnlessCancelled and not getOrElse.
                //
                // Nothing was recorded, so nothing is scheduled and nothing was evicted: the
                // Trash is untouched. Drop the snackbar rather than promise a destroy.
                if (pendingPurgeTarget == target) {
                    pendingPurgeTarget = null
                    _pendingPurge.value = null
                }
                return@launch
            }
            // One batched eviction of the whole folder: a full Trash is the case this path exists
            // for, and per-message eviction rescanned the search index once per message.
            repo.evictAll(credentials.id, repo.cachedIds(listOf(target)).map { it.emailId })
            // An empty photo is not an order (#99). The purge destroys the snapshot and nothing
            // else, so work with no ids behind it has nothing to do: a Trash that held nothing,
            // or one whose rows were never cached, arms no destroy at all.
            if (purge.messageCount > 0) {
                MessageDestroyWorker.schedulePurge(
                    getApplication(), credentials.id, trashId, purge.purgeId, PURGE_HOLD_BACK_MS,
                )
            }
            delay(PURGE_HOLD_BACK_MS)
            if (pendingPurgeTarget == target) {
                pendingPurgeTarget = null
                _pendingPurge.value = null
            }
        }
    }

    /** Cancel a held-back trash purge and restore the rows (nothing was destroyed yet). */
    fun undoEmptyTrash() {
        val target = pendingPurgeTarget
        pendingPurgeTarget = null
        purgeJob?.cancel()
        purgeJob = null
        _pendingPurge.value = null
        target?.let { (accountId, trashId) ->
            MessageDestroyWorker.cancelPurge(getApplication(), accountId, trashId)
            // Erase the destroy list itself, not just the job: the snapshot IS the order, so a
            // purge that somehow ran anyway finds nothing to destroy — and a snapshot still
            // being written when Undo was tapped does not survive the cancellation (#99).
            viewModelScope.launch { repo.discardTrashPurge(accountId, trashId) }
        }
        // Full re-query, not incremental: the rows were only evicted locally and are still on the
        // server, so a delta refresh brings nothing back (Codeberg #23).
        forceRefresh()
    }

    /** Swipe action: toggle flag/star. */
    fun toggleFlag(email: Email) {
        val flagged = !email.isFlagged
        patchSearchResults(setOf(email.emailKey())) { m ->
            m.copy(
                keywords = m.keywords.toMutableMap().apply {
                    if (flagged) put("\$flagged", true) else remove("\$flagged")
                },
            )
        }
        viewModelScope.launch {
            val credentials = credentialsFor(email) ?: return@launch
            runCatching { repo.setFlagged(credentials, email.id, flagged) }
                .onFailure { reportActionFailed("setFlagged (swipe)", it) }
        }
    }

    /** Route an action to the email's own account (unified inbox), else the current one. */
    private fun credentialsFor(email: Email): AccountCredentials? = credentialsFor(email.emailKey())

    private fun credentialsFor(key: EmailKey): AccountCredentials? =
        key.accountId?.let { store.credentials(it) } ?: store.load()

    /**
     * Clear the new-mail notifications of the messages [keys] just marked read locally, grouped
     * by account so the group summary is refreshed once per account (Codeberg #19).
     *
     * Keys and not messages: "mark all read" reaches this with a folder's worth of them, and the
     * account and the id are all that is dismissed on.
     */
    private fun dismissReadNotifications(keys: List<EmailKey>) = dismissBanners(keys)

    /**
     * The same thing without "read" in its name: the account-qualified dismissal of [keys]'
     * banners. Nothing in it ever depended on the messages having been read — the departure path
     * uses it exactly as the mark-read path does.
     */
    private fun dismissBanners(keys: List<EmailKey>) {
        if (keys.isEmpty()) return
        val app = getApplication<Application>()
        keys.mapNotNull { e -> credentialsFor(e)?.let { it.id to it.username to e.emailId } }
            .groupBy({ it.first }, { it.second })
            .forEach { (account, ids) ->
                val (accountId, label) = account
                Notifications.dismiss(app, accountId, label, ids)
            }
    }

    /** Mailbox ids backing the current view (one folder, or all inboxes when unified). */
    private fun currentMailboxIds(): List<String> = when (val sel = selection.value) {
        is Sel.Folder -> listOfNotNull(sel.id)
        Sel.Unified -> unifiedInboxScopes.value.map { it.second }.distinct()
        Sel.Unread -> unreadScopes.value.map { it.second }.distinct()
    }

    /** (account id, mailbox id) scopes backing the current view: the current account's folder,
     *  or each account's own inbox when unified. Bulk cache reads must carry BOTH ids — same-
     *  server accounts can share a mailbox id (Stalwart numbers them per-account), and a
     *  mailbox-only read would sweep a sibling account's messages into the operation. */
    private fun currentScopes(): List<Pair<String, String>> = when (val sel = selection.value) {
        is Sel.Folder -> {
            val accountId = store.currentId()
            if (accountId != null && sel.id != null) listOf(accountId to sel.id) else emptyList()
        }
        Sel.Unified -> store.allInboxScopes()
        // ⚠ The LIVE value, and it can differ from the one the pager on screen was built with:
        // the `mailboxes` onEach rewrites it as soon as a folder list arrives, while the pager is
        // still being rebuilt. So a gesture landing in that window acts over a folder that appeared
        // seconds ago and is not yet drawn. That is accepted, and it is only acceptable because
        // [listUnreadOnly] forces the filter on for this scope: what the window can add is UNREAD
        // mail of a folder this view covers — exactly what the view claims to show — never read
        // mail and never a folder outside the scope. The alternative, [listScope], is not safer:
        // it keeps the PREVIOUS view's folders until the new pager emits, which is staler.
        Sel.Unread -> unreadScopes.value
    }

    // ---- sort / filter / bulk ----

    fun setSortOrder(order: SortOrder) {
        viewModelScope.launch { settings.setSortOrder(order) }
    }

    fun toggleUnreadOnly() {
        unreadOnly.value = !unreadOnly.value
    }

    /** Mark every message in the current view as read. */
    fun markAllRead() {
        viewModelScope.launch {
            val scopes = currentScopes()
            // The unread keys, resolved by the statement: reading every cached row of every scope
            // only to drop the read ones was a whole folder (times the number of accounts, in the
            // unified view) for the fraction of it this actually patches and dismisses.
            val cachedUnread = repo.cachedUnreadKeys(scopes)
            patchThreadMembersSeen(cachedUnread.toSet(), true)
            var allMarked = true
            scopes.forEach { (accountId, mailboxId) ->
                val credentials = store.credentials(accountId) ?: return@forEach
                // Server-resolved targets (cached fallback offline): acting on the cached rows
                // alone leaves non-representative and out-of-window unread untouched, and the
                // badge springs back to their count at the next sync. One bulk repo call per
                // folder (chunked Email/set inside), not one round trip per message.
                val ids = repo.unreadIds(credentials, mailboxId)
                runCatching { repo.setReadAll(credentials, ids, seen = true) }
                    .onFailure { allMarked = false; reportActionFailed("markAllRead ($accountId)", it) }
            }
            // Clear the notifications (the user's to-do list) only if everything was actually marked
            // (audit B5): offline, setReadAll fails and nothing was read, so dropping them would hide
            // mail that is still unread. If anything failed, keep them all — the next sync reconciles.
            if (allMarked) dismissReadNotifications(cachedUnread)
            // Reconcile: rows marked beyond the cache don't nudge the badge (no cached seen
            // state), so converge counters and list on server truth now instead of later.
            refresh()
        }
    }

    // ---- multi-select ----

    fun enterSelection(email: Email) {
        // ⛔ A draft only this phone holds is not selectable: every action a selection leads to
        // (delete, archive, move, mark read) is a no-op or an error over an id no server knows,
        // and none of them removes the local row. See [isLocalDraftRow].
        if (isLocalDraftRow(email.id)) return
        _selectionActive.value = true
        _selectedKeys.value = setOf(email.emailKey())
    }

    fun toggleSelect(email: Email) {
        // Same refusal as [enterSelection]: "Select all" already skips these rows (selectableIds
        // reads `emails` alone), and a tap in selection mode must not put one in either.
        if (isLocalDraftRow(email.id)) return
        val key = email.emailKey()
        val next = _selectedKeys.value.toMutableSet().apply {
            if (!add(key)) remove(key)
        }
        _selectedKeys.value = next
        // Deselecting the last message leaves selection mode (otherwise the row stays
        // in a 0-selected state where swipes hit the drawer instead of the message).
        if (next.isEmpty()) _selectionActive.value = false
    }

    /**
     * Enter selection mode from a collapsed conversation row: like the whole-thread swipe,
     * the selection covers every cached member of the thread, not just the representative —
     * so the later bulk action treats the row the way it reads (one conversation).
     */
    fun enterSelectionThread(rep: Email) {
        _selectionActive.value = true
        _selectedKeys.value = setOf(rep.emailKey())
        viewModelScope.launch {
            _selectedKeys.value = _selectedKeys.value + threadMessages(rep).map { it.emailKey() }
        }
    }

    /** Toggle a collapsed conversation row in/out of the selection — all members at once. */
    fun toggleSelectThread(rep: Email) {
        viewModelScope.launch {
            val keys = threadMessages(rep).mapTo(mutableSetOf()) { it.emailKey() } + rep.emailKey()
            val current = _selectedKeys.value
            val next = if (rep.emailKey() in current) current - keys else current + keys
            _selectedKeys.value = next
            if (next.isEmpty()) _selectionActive.value = false
        }
    }

    /**
     * Select everything the list on screen is showing — the search results when they are what is
     * drawn, the folder otherwise (#126). The choice is [selectAllKeys], so it can be run in a test;
     * this reads the same [searchState] the screen is drawn from, live, at the moment of the tap.
     *
     * Outside a search the folder is read through [MailRepository.selectableIds], which pages the
     * list's OWN WHERE clause: with the "unread only" funnel on, the list shows unread mail and so
     * does the selection. The unfiltered read that used to sit here handed the bulk action every
     * read message of the folder too — the second half of #126, and the destructive half: select
     * all + delete moved messages that were never on screen to the Trash.
     *
     * ⛔ And the same half again, re-opened from our own side by #95: a server draft that a local
     * one is standing in for is TAKEN OUT of the list but is still filed in `emails`, so
     * [selectableIds] hands it over like any other. [selectableKeysMinusHidden] subtracts it — the
     * counter must not say N+1, and "Delete" must not reach a row nobody was shown.
     */
    fun selectAll() {
        _selectionActive.value = true
        val search = searchState.value
        // ⭐ The SAME decision the pager was built with ([listUnreadOnly]), never the raw toggle:
        // in a scope that forces the filter on, reading the funnel here asks selectableIds for the
        // folders UNFILTERED and hands "Delete" every read message of every folder in scope — mail
        // that was never on screen, which is the destructive half of #126.
        val filtered = listUnreadOnly(selection.value, unreadOnly.value)
        val hidden = localDraftRows.value.replacedServerIds
        viewModelScope.launch {
            _selectedKeys.value = selectAllKeys(
                searching = search.active,
                query = search.query,
                results = search.results?.map { it.emailKey() },
                loading = search.loading,
                complete = search.complete,
                folderKeys = selectableKeysMinusHidden(repo.selectableIds(currentScopes(), filtered), hidden),
            )
        }
    }

    fun clearSelection() {
        _selectionActive.value = false
        _selectedKeys.value = emptySet()
    }

    /**
     * Keep [tickedUnder] in step with [keys]: drop what is no longer ticked, and read the numbering
     * of the rows that have just been (#99).
     *
     * Only the NEW keys are read — a re-read of an old one would answer what the row carries now,
     * which is exactly the value this field exists not to be. One read per ACCOUNT, never one per
     * message: a select-all of a large folder would otherwise be thousands of database reads on
     * every tick.
     *
     * ⚠ The read suspends, so the selection can have moved on by the time it returns; only keys
     * STILL ticked are written back. A key ticked and confirmed inside that window keeps no stamp
     * at all — an absent stamp opposes nothing. It still passes the upstream split, which needs two
     * real numbers to call a row swapped; on the move to another account it is carried as
     * `FrozenNumbering.Frozen(null)` and refused there, because that path destroys without an Undo
     * and a row with nothing to oppose cannot be confirmed.
     */
    private suspend fun rememberTickedNumbering(keys: Set<EmailKey>) {
        val known = tickedUnder.filterKeys { it in keys }
        val fresh = keys.filterNot { it in known }
        val read = mutableMapOf<EmailKey, Long?>()
        fresh.groupBy { credentialsFor(it) }.forEach { (credentials, group) ->
            if (credentials == null) return@forEach
            val numbering = repo.numberingRowsWereReadUnder(credentials, group.map { it.emailId })
            group.forEach { key -> read[key] = numbering[key.emailId] }
        }
        val still = _selectedKeys.value
        tickedUnder = (known + read).filterKeys { it in still }
    }

    /**
     * THE ONE BARRIER, and both selection gestures go through it (#99, #189).
     *
     * [rememberTickedNumbering] above runs in a coroutine of its own and SUSPENDS; a "Select all"
     * over a large folder writes the whole selection in one emission and publishes the stamps a
     * dozen database queries later. A gesture that sampled the field instead of waiting for it got
     * an empty or half-full map — which used to be a silent loss (a row with no stamp opposed
     * nothing and went) and is now a refusal, because a ticked row with no numbering is refused at
     * both ends of the move between accounts. That is why the wait is this branch's to add.
     *
     * ⛔ A WAIT, NEVER A RE-READ. What is awaited is the publication of the read STARTED AT THE
     * TICK. Reading the rows again here would hand the guard the realigned number and make it
     * decorative: a folder renumbered since the tick would be compared with itself, agree, and
     * every line would travel.
     *
     * ⛔ ONE function, called from both gestures: a barrier posted on one side leaves the other
     * open, and the two sides destroy the same mail. The rule and the bound are in
     * [tickedNumberingWhenCovered], which a JVM test RUNS.
     */
    private suspend fun awaitTickedNumbering(keys: Set<EmailKey>): Map<EmailKey, Long?> =
        tickedNumberingWhenCovered(keys, tickedUnderFlow)

    /**
     * Apply a bulk action to the selected messages; exits selection mode unless [clearAfter] is
     * false.
     *
     * Every selected key is resolved first ([resolveSelectionTargets]), rows drawn by a search
     * included: asking the cache and then walking the ANSWER dropped every hit with no row in the
     * local `emails` table, so from a search this acted on part of the selection and said nothing
     * about the rest (bench, 2026-08-08).
     *
     * This path's op is the snooze, which is a local write plus a worker: a message with no row in
     * that same `emails` table cannot be snoozed at all, so [planSelectionSnooze] refuses it, leaves
     * it untouched, and it is counted as an attempt AND a failure — never written half-way and
     * announced as a success.
     */
    private fun bulk(
        clearAfter: Boolean = true,
        undoLabel: String? = null,
        op: suspend (AccountCredentials, String) -> Unit,
    ) {
        val keys = _selectedKeys.value
        if (clearAfter) clearSelection()
        viewModelScope.launch {
            val resolved = resolveSelectionTargets(
                keys = keys,
                cached = repo.cachedEmailsByIds(keys),
                displayed = searchState.value.results,
            )
            // A message with no local row cannot be snoozed: SnoozeWorker looks it up in that very
            // table at the due date, so the reminder would never ring and the "Snoozed" entry
            // would be deleted on the way. Refused rather than half-written — see
            // [planSelectionSnooze].
            val plan = planSelectionSnooze(resolved.targets)
            // A key nothing resolves, and a row the plan refused, are attempts that failed: neither
            // was written, and both are counted as such — or a selection nothing at all could be
            // written for lands on "nothing failed" and says nothing.
            var failed = resolved.unresolved.size + plan.refused.size
            // For a reversible bulk op (move to Trash/Archive/folder), capture each message the op
            // wrote so the whole batch can be moved back — the same Undo a swipe of one message
            // already offers, so bulk and swipe behave the same (Codeberg #23). Whether an Undo
            // may be offered at all is [selectionUndoEntries]' decision, not this loop's: the
            // source folder of a row that only exists on screen cannot be believed.
            val undoCandidates = mutableListOf<UndoCandidate>()
            // The selection leaves the unfolded rows for the length of the batch and no longer:
            // this path's op is a snooze, which removes nothing at all — it writes a date — so a
            // mask left standing here was the one thing keeping the message off the screen, and
            // it kept it off for good, past the due date the chip had already counted it back in.
            hidingThreadMembers(keys) {
                plan.snooze.forEach { target ->
                    val email = target.email
                    val credentials = credentialsFor(email)
                    if (credentials == null) { failed++; return@forEach }
                    runCatching { op(credentials, email.id) }
                        .onSuccess { undoCandidates += UndoCandidate(target, null) }
                        .onFailure {
                            failed++
                            android.util.Log.w("SternaBulk", "bulk op failed for ${email.id}", it)
                        }
                }
            }
            // A large selection can empty the whole loaded window of a huge folder. The rows
            // are gone locally, but an incremental refresh re-fetches nothing (the tens of
            // thousands of untouched server messages aren't "changes"), so the folder would
            // show its empty state until it is left and re-entered. Drop the sync cursors and
            // re-query so a full page repopulates the window from the server.
            repo.resetSyncState()
            refresh()
            val undoEntries = selectionUndoEntries(undoCandidates)
            if (undoLabel != null && undoEntries.isNotEmpty()) {
                _undo.value = UndoAction(undoEntries, undoLabel)
            }
            // Don't fail silently — but partial and total are not the same news: a snooze that went
            // through nine times out of ten used to report a total failure here.
            when (bulkOutcome(attempted = keys.size, failed = failed)) {
                BulkOutcome.NONE -> Unit
                BulkOutcome.TOTAL -> _message.value = getApplication<Application>().getString(R.string.status_action_failed)
                BulkOutcome.PARTIAL -> _message.value = getApplication<Application>().getString(R.string.status_action_partly_failed)
            }
        }
    }

    /**
     * Batched bulk action: group the selection by account, then hand each account's ids to a
     * single repo call that itself batches per source folder — one `UID MOVE <set>` (IMAP)
     * or one `Email/set` (JMAP) instead of one server command per message. This is the fix
     * for large selections failing on IMAP (Codeberg #29). Everything #23 added is preserved:
     * the members leaving the unfolded rows, an Undo entry per surviving message for a reversible
     * op, resetSyncState()+refresh() (a big move can empty the loaded window of a huge folder),
     * and the failure toast only when something actually failed.
     *
     * The batches receive the WHOLE selection ([resolveSelectionTargets]), rows drawn by a search
     * included: asking the cache and then walking the ANSWER archived four of the ten results the
     * user had selected and said nothing about the other six (bench, 2026-08-08).
     *
     * [attemptedBefore] and [failedBefore] are what a delegating caller handled or lost before
     * getting here, so ONE honest message comes out instead of two toasts chasing each other on a
     * `StateFlow`. They are counted separately on purpose: a delete that destroyed 99 messages and
     * lost one has 100 attempts and 1 failure, and folding both into the same number would call
     * that a total failure while the 99 destructions are on their way.
     */
    private fun bulkBatched(
        clearAfter: Boolean = true,
        @PluralsRes undoLabelRes: Int? = null,
        keys: Set<EmailKey>? = null,
        attemptedBefore: Int = 0,
        failedBefore: Int = 0,
        batchOp: suspend (AccountCredentials, List<String>) -> MailRepository.BulkResult,
    ) {
        val targetKeys = keys ?: _selectedKeys.value
        if (clearAfter) clearSelection()
        viewModelScope.launch {
            val resolved = resolveSelectionTargets(
                keys = targetKeys,
                cached = repo.cachedEmailsByIds(targetKeys),
                displayed = searchState.value.results,
            )
            val targets = resolved.targets
            // Only the acted-on rows leave the search snapshot — never a row the batch below won't
            // touch; the failed ones are restored once the batch settles. That set is now wider on
            // purpose: a hit with no cached row is acted on, so it must leave the screen too.
            dropSearchResults(targets.mapTo(mutableSetOf()) { it.email.emailKey() })
            val failedKeys = mutableSetOf<EmailKey>()
            val undoCandidates = mutableListOf<UndoCandidate>()
            // The FIRST throwable a batch lets escape, kept for the message at the end. First and
            // not last: the loop below is per ACCOUNT, so the failure that cut the gesture is the
            // one that came first, and the accounts behind it fail against a link that was already
            // gone. Null is the ordinary case — a server refusal, an over-quota, a numbering the
            // server would not confirm all come back as `BulkResult.failed`, with no exception.
            var batchFailure: Throwable? = null
            hidingThreadMembers(targetKeys) {
                // AccountCredentials is a data class, so all of an account's messages group
                // together — and each account's batch receives exactly ITS ids, never a
                // colliding sibling's.
                targets.groupBy { credentialsFor(it.email) }.forEach { (credentials, group) ->
                    if (credentials == null) { failedKeys += group.map { it.email.emailKey() }; return@forEach }
                    val result = runCatching { batchOp(credentials, group.map { it.email.id }) }
                        .getOrElse {
                            android.util.Log.w("SternaBulk", "batch op failed for ${credentials.id}", it)
                            if (batchFailure == null) batchFailure = it
                            MailRepository.BulkResult(emptySet(), group.mapTo(mutableSetOf()) { e -> e.email.id })
                        }
                    failedKeys += group.filter { it.email.id in result.failed }.map { it.email.emailKey() }
                    if (undoLabelRes != null) {
                        group.forEach { target ->
                            if (target.email.id in result.succeeded) undoCandidates += UndoCandidate(target, result.dest)
                        }
                    }
                }
            }
            restoreSearchResults(failedKeys)
            repo.resetSyncState()
            refresh()
            // After the full re-query settles: re-cache the touched conversations' members
            // under their new folders so their rows keep truthful, expandable chips.
            completeThreadsAfterAction(targets.map { it.email })
            // Whether an Undo may be offered is [selectionUndoEntries]' decision: a source folder
            // read off a row that only exists on screen sends the message somewhere it never was
            // and strips every other folder it is in, with no way back.
            val undoEntries = selectionUndoEntries(undoCandidates)
            // What the banner says is [selectionBanner]'s decision too, and it counts the CANDIDATES:
            // a batch that wrote ten messages of which only four can be put back still deleted ten.
            selectionBanner(undoLabelRes, undoCandidates, undoEntries)?.let { banner ->
                val resources = getApplication<Application>().resources
                // Twice on purpose: the first picks the CLDR plural category, the second fills %1$d.
                val label = resources.getQuantityString(banner.labelRes, banner.count, banner.count)
                _undo.value = UndoAction(undoEntries, label)
                scheduleUndoneDeparture()
            }
            // Partial and total are not the same news: a select-all past the server's
            // maxObjectsInSet used to archive most of the selection and still say "Couldn't
            // complete the action". Counted over the WHOLE selection, on both sides: a key nothing
            // resolved for was attempted and failed, or a batch that reached none of its messages
            // would land on `failed <= 0 -> NONE` and stay silent (see [bulkOutcome]).
            val failed = failedKeys.size + resolved.unresolved.size + failedBefore
            // And which sentence a TOTAL failure gets is the same decision the swipe makes, asked
            // the same way: the throwable that failed, plus the device's live connectivity. Offline
            // there is no queue at all, so the bulk simply fails — and it used to say "Couldn't
            // complete the action" while the swipe beside it, and the banner above the list, both
            // said the device was offline. Only when a throwable escaped, and only here: PARTIAL
            // cannot be an outage (offline nothing succeeds by halves), and the COUNT of failures
            // says nothing about the transport — a refusal, an over-quota or an unconfirmed
            // numbering fails every message of a batch without any exception at all.
            val app = getApplication<Application>()
            val failure = batchFailure
            when (bulkOutcome(attempted = targetKeys.size + attemptedBefore, failed = failed)) {
                BulkOutcome.NONE -> Unit
                BulkOutcome.TOTAL -> _message.value = app.getString(if (failure == null) R.string.status_action_failed else actionFailureMessage(failure, online = hasUsableNetwork(app)))
                BulkOutcome.PARTIAL -> _message.value = getApplication<Application>().getString(R.string.status_action_partly_failed)
            }
        }
    }

    /**
     * The IMAP numbering of every source folder [keys] name, as recorded RIGHT NOW — i.e. at the
     * gesture, which is the only moment this may be read. Account first, then folder: the same
     * mailbox id in two accounts is two different folders with two different numberings, and a
     * unified selection spans both.
     *
     * The folder comes from the ID, exactly as `MailRepository` groups its batches by it — not
     * from the cached row's `mailboxId`, which the sync writes and which can disagree with the id
     * a batch will actually be routed by.
     *
     * One read per (account, folder), never per message: a select-all is thousands of ids over a
     * handful of folders. Empty for a JMAP account — `recordedUidValidity` answers null there, an
     * id surviving anything a mailbox can go through — and empty is what the batch then opposes,
     * which on JMAP is nothing to oppose and nothing to refuse.
     *
     * Twin of [recordedNumbering], which does the same for the held-back destroy; kept apart
     * because that one is keyed by the row's folder and this one by the id's.
     */
    private suspend fun selectionNumbering(keys: Collection<EmailKey>): Map<String, Map<String, Long?>> {
        val byAccount = mutableMapOf<String, MutableMap<String, Long?>>()
        keys.forEach { key ->
            val credentials = credentialsFor(key) ?: return@forEach
            val folder = ImapMailService.mailboxOf(key.emailId) ?: return@forEach
            val folders = byAccount.getOrPut(credentials.id) { mutableMapOf() }
            if (folder !in folders) folders[folder] = repo.recordedUidValidity(credentials, folder)
        }
        return byAccount
    }

    /**
     * Delete the selection: the subset whose delete would permanently destroy (in Trash, or no
     * Trash) is held back behind Undo exactly like a swipe delete — never destroyed inline — so
     * bulk delete is consistent (Codeberg #23); the rest keeps the move-to-Trash bulk path.
     *
     * Which row is in which subset is [planSelectionDelete]'s decision, over the WHOLE selection
     * ([resolveSelectionTargets]) and never from an untrusted row's folder: a search hit is not in
     * the Trash (search excludes it), so it is moved there, and on a Trash-less account it is left
     * alone rather than destroyed on a supposition. What is left alone is counted and announced
     * once — by the delegate when there is something to move, here when there is not.
     */
    fun deleteSelected() {
        val keys = _selectedKeys.value
        viewModelScope.launch {
            // The numbering each ticked row carried WHEN IT WAS TICKED — AWAITED, not sampled, and
            // FIRST INSIDE THE LAUNCH (#99, #189).
            //
            // ⛔ Why it is no longer taken outside the launch, which is what this used to say. The
            // read that produces these stamps starts at the TICK, in another coroutine, and it
            // suspends: a "Select all" publishes it a dozen queries later, so a tap in between
            // sampled an empty or half-full map. Waiting suspends, so it has to be in here.
            // ⛔ Why FIRST. Everything below either suspends or clears the selection, and clearing
            // it wakes the collector, which empties the field: a wait posted any later would race
            // its own erasure and hand back nothing.
            // ⛔ Still never a re-read: this awaits the publication of the tick's own read. A
            // re-read here IS the realigned number, and the guard would agree with itself.
            val ticked = awaitTickedNumbering(keys)
            // Frozen AT THE GESTURE, and carried into the batch: an IMAP UID means something only
            // inside one numbering, and a bulk delete is a bulk MOVE to the Trash that runs after
            // the whole selection has been resolved (#99). Read at execution instead, it would be
            // the numbering of whatever the folder had become by then.
            val numbering = selectionNumbering(keys)
            val resolved = resolveSelectionTargets(
                keys = keys,
                cached = repo.cachedEmailsByIds(keys),
                displayed = searchState.value.results,
            )
            val plan = planSelectionDelete(
                targets = resolved.targets,
                hasTrash = { email -> credentialsFor(email)?.let { c -> runCatching { repo.accountHasTrash(c) }.getOrDefault(false) } ?: false },
                wouldDestroy = { email -> credentialsFor(email)?.let { c -> runCatching { repo.deleteWouldDestroy(c, email) }.getOrDefault(false) } ?: false },
            )
            // ⛔ THE UPSTREAM HALF OF #99, and the only half that can see a row SWAPPED UNDER A
            // TICK. `recordedNumbering` answers what each row to be destroyed carries NOW — per
            // row, grouped per account, the same read the hold-back freezes with. Opposed to the
            // stamp the tick was taken under, a disagreement means the line was replaced since the
            // user pointed at it, and the destroy may not have it: same UID text, another message.
            val destroyTargets = plan.destroy.mapNotNull { e -> credentialsFor(e)?.let { Triple(it, e.id, e.mailboxId.orEmpty()) } }
            val now = recordedNumbering(destroyTargets)
            val split = UidValidity.destroyableUnderTheNumberingItWasTickedUnder(
                rows = plan.destroy,
                tickedUnder = { ticked[it.emailKey()] },
                readUnderNow = { email -> credentialsFor(email)?.let { now[it.id to email.id] } },
            )
            clearSelection()
            val lostBefore = resolved.unresolved.size + plan.untreated.size
            // The rows the split refused were ATTEMPTED — they are inside plan.destroy.size — and
            // they failed. Counting them in both places would make the message say twice as much
            // was tried as was, which is why only the failures side grows.
            val lost = lostBefore + split.drifted.size
            if (split.kept.isNotEmpty()) {
                heldBackDestroy(split.kept, getApplication<Application>().getString(R.string.status_message_deleted_forever))
            }
            if (plan.move.isNotEmpty()) {
                // Mixed destroy+move: only the held-back destroy offers Undo (see deleteThread).
                // The whole selection travels with the delegation — the destroyed messages were
                // attempts too — so the single message it prints is about the whole delete.
                // ⚠ The question is whether a destroy is actually PENDING, i.e. `split.kept`, and
                // not whether one was planned: a destroy leg the split refused whole shows no bar
                // at all, and reading `plan.destroy` there would withhold the move's Undo in
                // favour of a snackbar nobody is going to see.
                bulkBatched(
                    undoLabelRes = R.plurals.status_selection_deleted.takeIf { split.kept.isEmpty() },
                    keys = plan.move.mapTo(mutableSetOf()) { it.emailKey() },
                    attemptedBefore = plan.destroy.size + lostBefore,
                    failedBefore = lost,
                ) { c, batch -> repo.deleteAll(c, batch, numbering[c.id].orEmpty()) }
            } else {
                // Nothing to move: delegating an empty batch just to speak would drop the sync
                // cursors and re-query the whole folder for an action that wrote nothing. Say it
                // here instead — one message either way, since the delegate never runs.
                when (bulkOutcome(attempted = keys.size, failed = lost)) {
                    BulkOutcome.NONE -> Unit
                    BulkOutcome.TOTAL -> _message.value = getApplication<Application>().getString(R.string.status_action_failed)
                    BulkOutcome.PARTIAL -> _message.value = getApplication<Application>().getString(R.string.status_action_partly_failed)
                }
            }
            // ⛔ LAST, after the branch above and never before it: `bulkBatched` calls
            // clearSelection() synchronously at the call, so a re-selection written earlier would
            // be wiped by the very delegation that follows it. What was refused goes back under the
            // user's eyes, still ticked — a destruction that quietly dropped part of a confirmed
            // selection would be a lie the screen agrees with.
            if (split.drifted.isNotEmpty()) {
                // ⛔ THE STAMPS GO BACK FIRST, and they are the stamps OF THE TICK. The collector
                // above only reads keys it does not already know, so stamps written AFTER the keys
                // — or not written at all — leave it looking at rows it has never seen: it reads
                // their numbering AS IT IS NOW, the second tap compares the new number with
                // itself, agrees, and destroys the very messages this refusal just saved. A
                // refusal has to survive being told about. Nothing suspends between these two
                // lines, which is what makes the order enough.
                tickedUnder = split.drifted.associate { it.emailKey() to ticked[it.emailKey()] }
                _selectionActive.value = true
                _selectedKeys.value = split.drifted.mapTo(mutableSetOf()) { it.emailKey() }
            }
        }
    }
    /** Archive the selection. A block, not an expression body: freezing the source folders'
     *  numbering is a suspending read, and it has to happen HERE, at the tap (#99).
     *
     *  ⚠ The selection is cleared SYNCHRONOUSLY, before that read. `bulkBatched` clears it too,
     *  but it now runs a suspension later: leaving the bar up in between lets a second tap fire
     *  the whole batch again, on the same keys. */
    fun archiveSelected() {
        val keys = _selectedKeys.value
        clearSelection()
        viewModelScope.launch {
            val numbering = selectionNumbering(keys)
            bulkBatched(
                undoLabelRes = R.plurals.status_selection_archived,
                keys = keys,
            ) { c, ids -> repo.archiveAll(c, ids, numbering[c.id].orEmpty()) }
        }
    }

    /**
     * Move the selection to [targetMailboxId] (used for unarchive → Inbox and move-to-folder).
     *
     * The messages another account's folder cannot take are set aside, counted, and announced
     * once — by the delegate when there is something left to move, here when there is not.
     */
    fun moveSelectedTo(targetMailboxId: String, targetAccountId: String? = null) {
        val keys = _selectedKeys.value
        viewModelScope.launch {
            // AWAITED, not sampled, and FIRST INSIDE THE LAUNCH — [deleteSelected]'s rule, and for
            // its reasons (#99, #189). The one extra reason on this side: [moveSelectedToAccount]
            // calls clearSelection() at its head, which wakes the collector on _selectedKeys and
            // empties the field, so a wait posted below this line would race its own erasure.
            // ⛔ The KEYS stay captured outside the launch: they say WHICH selection is awaited,
            // and read a suspension later they would be another one.
            val ticked = awaitTickedNumbering(keys)
            // The picker offered the SELECTION's account folders (see [selectionMailboxes]), so the
            // move targets that account: a unified selection of a secondary account now lands in its
            // OWN folders instead of being skipped for not being the active account (#73). Only that
            // account's messages move; any from another account (a mixed selection falls back to the
            // active account) are left untouched and reported — the same target id in a sibling
            // account is a different or nonexistent folder, since same-server mailbox ids collide.
            val moveAccountId = selectionAccount(keys) ?: store.currentId()
            // Into another account's folder (#189): its own path, one message after the other.
            if (isCrossAccountMove(targetAccountId, moveAccountId)) {
                moveSelectedToAccount(keys, moveAccountId, requireNotNull(targetAccountId), targetMailboxId, ticked)
                return@launch
            }
            val (movable, skipped) = keys.partition { it.accountId == moveAccountId }
            clearSelection()
            if (movable.isNotEmpty()) {
                // Frozen at the gesture, for the movable subset only — the other account's
                // messages are not moved and have no folder here to oppose (#99).
                val numbering = selectionNumbering(movable)
                // Undoable like archive and delete: moving from the reader offers it (#73), and the
                // same gesture in the list must not be the one you cannot take back.
                // The set-aside messages travel with the delegation — they were attempts, and they
                // were not moved — so the ONE message that comes out counts the whole selection
                // instead of a second toast racing the first on the same StateFlow.
                bulkBatched(
                    undoLabelRes = R.plurals.status_selection_moved,
                    keys = movable.toMutableSet(),
                    attemptedBefore = skipped.size,
                    failedBefore = skipped.size,
                ) { c, batch -> repo.moveAllToMailbox(c, batch, targetMailboxId, numbering[c.id].orEmpty()) }
            } else if (skipped.isNotEmpty()) {
                // Nothing movable: delegating an empty batch just to speak would drop the sync
                // cursors and re-query the whole folder for an action that wrote nothing. Say it
                // here instead, and say the specific sentence — in THIS case it is exactly true,
                // every message was another account's.
                _message.value = getApplication<Application>().getString(R.string.status_move_other_account)
            }
        }
    }

    /**
     * The selection into a folder of ANOTHER account (#189), one message after the other through
     * [MailRepository.moveToAccount] — each a copy confirmed on B, then the original to A's bin.
     * The partition is [moveSelectedTo]'s: only the messages of the selection's account go, the
     * others are set aside and counted. ⛔ No Undo (see [moveToAccount]). ONE banner at the end,
     * chosen by [crossAccountBanner]: a copy whose original stayed is the truth said first.
     *
     * And whatever wrote NOTHING on account B comes back ticked, stamps first
     * ([crossAccountGiveBack]) — every outcome that is neither `Moved` nor `CopiedNotRemoved`,
     * whichever refusal produced it, because a move that quietly dropped part of a selection would
     * be a lie the screen agrees with and there is no Undo here to correct it with. Not "whatever
     * did not leave A": a `CopiedNotRemoved` did not leave A either — its original stayed there,
     * that IS the verdict — and handing it back would put a second copy on B at the next tap.
     */
    private suspend fun moveSelectedToAccount(
        keys: Set<EmailKey>,
        ownerAccountId: String?,
        targetAccountId: String,
        targetMailboxId: String,
        ticked: Map<EmailKey, Long?>,
    ) {
        val (movable, skipped) = keys.partition { it.accountId == ownerAccountId }
        // ⛔ THE TARGET IS READ, AND REFUSED, BEFORE THE SELECTION IS TOUCHED. Account B can be
        // signed out between the tap that opened the picker and the tap that chose the folder;
        // `store.credentials` then answers null and this body returns with nothing sent and not one
        // verdict written. Below `clearSelection()`, that return left the tick and `tickedUnder`
        // already wiped and no give-back to run behind it — the whole selection dropped in
        // silence, and the promise above false for a refusal that exists today. Read first and
        // nothing has been taken, so there is nothing to give back. `store.credentials` does not
        // suspend, so the clear loses nothing by waiting for it.
        val app = getApplication<Application>()
        val target = store.credentials(targetAccountId)
        if (target == null) {
            _message.value = app.getString(R.string.status_action_failed)
            return
        }
        clearSelection()
        val emails = repo.cachedEmailsByIds(movable).associateBy { it.emailKey() }
        // ⛔ THE HALF THAT CAN SEE A ROW SWAPPED UNDER A TICK, the one [deleteSelected] already
        // carries (#99). `recordedNumbering` answers what each row about to leave carries NOW —
        // per row, grouped per account. Opposed to the stamp of the tick, a disagreement means the
        // line was replaced since the user pointed at it: same UID text, another message, and this
        // move would copy it to B and put the original in A's bin, with no Undo to take it back.
        // ⛔ Not the folder's own record: a background pass that met the renumbering has already
        // realigned that one, and opposing it to itself licenses exactly what this refuses.
        val crossAccountTargets = emails.values.mapNotNull { e -> credentialsFor(e)?.let { Triple(it, e.id, e.mailboxId.orEmpty()) } }
        val now = recordedNumbering(crossAccountTargets)
        val split = UidValidity.destroyableUnderTheNumberingItWasTickedUnder(
            rows = emails.values.toList(),
            tickedUnder = { ticked[it.emailKey()] },
            readUnderNow = { email -> credentialsFor(email)?.let { now[it.id to email.id] } },
        )
        val refused = split.drifted.mapTo(mutableSetOf()) { it.emailKey() }
        // ⛔ KEYED, AND FILLED AT EVERY EXIT OF THIS LOOP. `CrossAccountMove.Failed` carries a log
        // string and nothing else — no key, no type, no throwable — so a bare list of verdicts
        // answers HOW MANY did not move and never WHICH. The give-back at the end needs the WHICH,
        // and `results[i]` matching `movable[i]` is a positional invariant nobody wrote down: one
        // `continue` added above without its record and the two lists drift apart in silence, with
        // no Undo behind the gesture that would then tick the wrong rows.
        val outcomes = LinkedHashMap<EmailKey, CrossAccountMove>(movable.size)
        for (key in movable) {
            val source = credentialsFor(key)
            val email = emails[key]
            if (source == null || email == null) {
                outcomes[key] = CrossAccountMove.Failed("no cached row for ${key.emailId}")
                continue
            }
            // NOT ATTEMPTED: no copy on B, no Trash on A, and no search-result surgery either —
            // the row on screen is not the row that was ticked. It counts as a failure, and
            // crossAccountBanner turns that verdict into an existing line either way: the
            // partial-failure one when something else in the batch did go through, the plain
            // "couldn't complete the action" one when nothing did. No new sentence either way, no
            // ninth translation.
            if (key in refused) {
                outcomes[key] = CrossAccountMove.Failed("renumbered since it was ticked: ${key.emailId}")
                continue
            }
            dropSearchResults(setOf(key))
            val result = hidingThreadMembers(setOf(key)) {
                // ⛔ AND THE STAMP GOES DOWN THE WIRE. The split above refuses the rows whose
                // numbering had ALREADY drifted when the loop reached them; this carries the same
                // stamp into the two gestures that touch A's mail, so a folder renumbered WHILE
                // the loop runs is refused too — by the server, in the SELECT the read and the bin
                // go out under. Without it both fall back on the folder's record, which the first
                // iteration that met the renumbering has already realigned: the record is then
                // compared with itself, agrees, and every later row travels. `ticked[key]`, never
                // a re-read: the re-read IS the realigned number.
                // ⛔ AND IT IS WRAPPED IN `Frozen`, absent value included. A tick DID freeze here,
                // so a `ticked[key]` of null means "frozen, and there was nothing" — refused at
                // both ends downstream — and not "nothing was frozen", which is the reader's case
                // and falls back on the record. Under a bare `Long?` those two were one argument,
                // and the second silently borrowed the first's fallback: the octets of a message
                // nobody ticked went to B and hers went to A's bin, with no Undo.
                runCatching { repo.moveToAccount(source, email, target, targetMailboxId, FrozenNumbering.Frozen(ticked[key])) }
                    .getOrElseUnlessCancelled { CrossAccountMove.Failed(it.toString()) }
            }
            if (result !is CrossAccountMove.Moved) {
                android.util.Log.w("SternaInbox", "move to account, ${key.emailId}: $result")
                restoreSearchResults(listOf(key))
            }
            outcomes[key] = result
        }
        val results = outcomes.values.toList()
        // Which line goes with which outcome is crossAccountSelectionPlural's and
        // crossAccountSelectionFailedRes's, both executed by a test; only the counts and the
        // labels are built here.
        val outcome = crossAccountBanner(results, skipped.size)
        when (outcome) {
            CrossAccountOutcome.ALL_MOVED ->
                _message.value = app.resources.getQuantityString(
                    checkNotNull(crossAccountSelectionPlural(outcome)), results.size, results.size,
                )
            CrossAccountOutcome.SOME_COPIED_NOT_REMOVED -> {
                val copied = results.count { it is CrossAccountMove.CopiedNotRemoved }
                val where = crossAccountTargetLabel(accountLabel(target.id), folderLabel(target.id, targetMailboxId))
                _message.value = app.resources.getQuantityString(
                    checkNotNull(crossAccountSelectionPlural(outcome)), copied, copied, where, accountLabel(ownerAccountId),
                )
            }
            CrossAccountOutcome.PARTLY_FAILED, CrossAccountOutcome.ALL_FAILED ->
                _message.value = app.getString(checkNotNull(crossAccountSelectionFailedRes(outcome)))
        }
        if (results.any { it is CrossAccountMove.Failed }) refresh()
        // ⛔ LAST, after the banner and never before it: clearSelection() runs at the head of this
        // body, so a re-selection written earlier would be wiped by the gesture it belongs to.
        // What did not move goes back under the user's eyes, still ticked — a move that quietly
        // dropped part of a selection would be a lie the screen agrees with, and this path has no
        // Undo to correct it with.
        // ⛔ AND IT IS INDEXED ON THE OUTCOME, NOT ON A CLASS OF REFUSAL ([crossAccountGiveBack],
        // pure and executed by a test). Keyed on `split.drifted`, this give-back was empty by
        // construction for the refusal raised at the READ, which comes back an ordinary `Failed`:
        // those lines did not travel, did not come back ticked, and nothing said which ones — or
        // that anything had happened at all. The rule is not re-written here; the decision is
        // asked for, with the outcomes of this batch and the rows still in cache.
        val givenBack = crossAccountGiveBack(outcomes, emails.keys)
        if (givenBack.isNotEmpty()) {
            // ⛔ THE STAMPS GO BACK FIRST, and they are the stamps OF THE TICK. The collector on
            // _selectedKeys only reads keys it does not already know, so stamps written AFTER the
            // keys — or not at all — leave it looking at rows it has never seen: it reads their
            // numbering AS IT IS NOW, the second tap on Move compares the new number with itself,
            // agrees, and sends to B exactly what this refusal just saved. Nothing suspends
            // between these two lines, which is what makes the order enough.
            // ⛔ AND THE ENTRY IS WRITTEN EVEN WHEN THE STAMP IS null: it is the PRESENCE of the
            // key that stops the collector re-reading, not its value. A row given back with a null
            // stamp is refused again at the second tap and nothing is written on B; a row given
            // back with NO entry is re-stamped with the realigned numbering and travels.
            tickedUnder = givenBack.associateWith { ticked[it] }
            _selectionActive.value = true
            _selectedKeys.value = givenBack.toMutableSet()
        }
    }

    /**
     * Report the selection as spam / take it back out of Junk — both MOVES on IMAP, and both were
     * left out of the numbering freeze at first. That cost the whole gesture: with nothing frozen
     * the batch refuses every id, the bar says the action failed, nothing moves — and
     * `markReadOnMoveOutOfInbox` has already flagged them `\Seen` on the server (#99).
     *
     * Blocks, not expression bodies, for [archiveSelected]'s reason: freezing is a suspending read.
     */
    fun reportSpamSelected() {
        val keys = _selectedKeys.value
        clearSelection()
        viewModelScope.launch {
            val numbering = selectionNumbering(keys)
            bulkBatched(keys = keys) { c, ids -> repo.reportSpamAll(c, ids, numbering[c.id].orEmpty()) }
        }
    }

    fun notSpamSelected() {
        val keys = _selectedKeys.value
        clearSelection()
        viewModelScope.launch {
            val numbering = selectionNumbering(keys)
            bulkBatched(keys = keys) { c, ids -> repo.notSpamAll(c, ids, numbering[c.id].orEmpty()) }
        }
    }

    /** Snooze the whole selection until [until] (hidden now, returns to the inbox then). */
    fun snoozeSelected(until: Long) = bulk { c, id ->
        repo.snooze(id, c.id, until)
        Snoozes.enqueue(getApplication(), id, c.id, until)
    }

    // ---- folder management ----

    fun createFolder(name: String, parentId: String? = null) = folderOp { c -> repo.createFolder(c, name, parentId) }

    fun renameFolder(mailboxId: String, newName: String) = folderOp { c ->
        val newId = repo.renameFolder(c, mailboxId, newName)
        // IMAP ids are paths: the repo re-keyed the watch flags; follow with the baseline.
        if (newId != mailboxId) NewMailNotifier.rename(getApplication(), c.id, mailboxId, newId)
        // ⭐ The fold registry IS re-read here, because the repo now re-keys it exactly like the
        // watch flags (`AccountStore.replaceCollapsedFolder`, IMAP ids are paths). Without the
        // re-read the flow still holds the OLD key, the renamed folder reads as "nobody decided"
        // and the DEFAULT folds it on the spot — the folder the user just unfolded closes itself
        // under its new name (issue #185).
        refreshWatchedFolders()
        refreshCollapsedFolders()
    }

    /**
     * Subfolders (recursive) of a folder, from the cached drawer list — JMAP `parentId`
     * or the IMAP path prefix, mirroring the drawer's own tree building.
     */
    fun subfolderIdsOf(mailboxId: String): List<String> {
        val all = state.value.mailboxes
        val result = mutableListOf<String>()
        fun childrenOf(id: String): List<Mailbox> = all.filter { m ->
            if (m.id == id) return@filter false
            if (m.parentId != null) return@filter m.parentId == id
            val delim = when {
                m.id.contains('/') -> "/"
                m.id.contains('.') -> "."
                else -> return@filter false
            }
            m.id.substringBeforeLast(delim, "") == id
        }
        fun visit(id: String) {
            childrenOf(id).forEach { child ->
                if (result.add(child.id)) visit(child.id)
            }
        }
        visit(mailboxId)
        return result
    }

    private var folderDeleteJob: Job? = null
    /** (accountId, mailboxId) of the folder whose held-back delete is undoable. */
    private var pendingDeleteMailboxId: Pair<String, String>? = null
    private val _pendingFolderDelete = MutableStateFlow<String?>(null)

    /** Snackbar label while a folder delete waits out its undo window; null = none. */
    val pendingFolderDelete: StateFlow<String?> = _pendingFolderDelete.asStateFlow()

    /**
     * Delete a folder (and its subfolders) with an undo window: the folder leaves the
     * drawer immediately, and the server delete is PERSISTED WorkManager work with an
     * initial delay — it survives this ViewModel and the process, so a confirmed delete
     * can no longer be silently dropped. The coroutine below only times the snackbar.
     */
    fun deleteFolder(mailboxId: String, folderName: String) {
        val credentials = store.load() ?: return
        val ids = listOf(mailboxId) + subfolderIdsOf(mailboxId)
        viewModelScope.launch { repo.hideMailboxesLocally(credentials.id, ids) }
        _pendingFolderDelete.value =
            getApplication<Application>().getString(R.string.inbox_folder_deleted, folderName)
        FolderDeleteWorker.schedule(getApplication(), credentials.id, mailboxId, PURGE_HOLD_BACK_MS)
        pendingDeleteMailboxId = credentials.id to mailboxId
        folderDeleteJob?.cancel()
        folderDeleteJob = viewModelScope.launch {
            delay(PURGE_HOLD_BACK_MS)
            if (pendingDeleteMailboxId == credentials.id to mailboxId) {
                pendingDeleteMailboxId = null
                _pendingFolderDelete.value = null
                refreshWatchedFolders()
            }
        }
    }

    /** Cancel a held-back folder delete (nothing was destroyed yet) and restore the drawer. */
    fun undoDeleteFolder() {
        pendingDeleteMailboxId?.let { (accountId, mailboxId) -> FolderDeleteWorker.cancel(getApplication(), accountId, mailboxId) }
        pendingDeleteMailboxId = null
        folderDeleteJob?.cancel()
        folderDeleteJob = null
        _pendingFolderDelete.value = null
        refresh()
    }

    // ---- folder watch (multi-folder push, issue #16) ----

    // Initialised inline, NOT from the init block: init runs before this declaration's
    // initialiser, so touching the flow there would NPE during ViewModel construction.
    private val _watchedFolders =
        MutableStateFlow(store.currentId()?.let { store.watchedFolders(it) } ?: emptySet())

    /** Folders watched for new mail on the current account (the inbox is always watched). */
    val watchedFolders: StateFlow<Set<String>> = _watchedFolders

    private fun refreshWatchedFolders() {
        _watchedFolders.value = store.currentId()?.let { store.watchedFolders(it) } ?: emptySet()
    }

    // ---- drawer folder tree: the fold/unfold choice, kept per account ----

    // Initialised inline for the same reason as [_watchedFolders] above: init runs before this
    // declaration's initialiser.
    private val _collapsedFolders =
        MutableStateFlow(store.currentId()?.let { store.collapsedFolders(it) } ?: emptyMap())

    /**
     * The current account's EXPLICIT fold/unfold choice per drawer folder; a folder with no key is
     * one nobody decided anything about. Per account, and re-read on every account switch: on IMAP
     * the ids are paths, so a namesake folder on the neighbouring account would otherwise be drawn
     * folded with nobody having asked (#92/#121).
     */
    val collapsedFolders: StateFlow<Map<String, Boolean>> = _collapsedFolders

    // Initialised inline for the same reason as [_collapsedFolders] above: init runs before this
    // declaration's initialiser.
    //
    // ⛔ `false` with no current account, and that is the SAFE side, not the familiar one. `false`
    // means "fold nothing by itself"; `true` means "fold, hiding mail behind a row that may have no
    // badge". `AccountStore.accounts()` answers an EMPTY list both when there is no account AND
    // when the stored blob failed to decode (see `AccountStillConfigured`), so this fallback is
    // reachable with folders on screen. Nothing is ever hidden by taking it.
    private val _folderRowsBadgeUnread =
        MutableStateFlow(store.currentId()?.let { repo.folderRowsBadgeUnread(it) } ?: false)

    /**
     * Whether the current account's drawer folder rows can carry an unread count at all
     * ([MailRepository.folderRowsBadgeUnread]) — false on IMAP, whose rows hold a hard 0.
     *
     * The drawer needs it because the DEFAULT fold rests on that badge: a folder folded on the
     * first run is honest only while the folded row says how much mail it is hiding. Per account
     * and re-read on every account switch, exactly like the registry beside it: a JMAP and an IMAP
     * account in the same app do not answer the same thing (#185).
     */
    val folderRowsBadgeUnread: StateFlow<Boolean> = _folderRowsBadgeUnread

    /**
     * Re-read everything the fold decision needs FOR THE CURRENT ACCOUNT: the explicit registry
     * and whether this account's rows can badge unread at all. One function, because both are
     * per-account entries into the same decision and both must move together on a switch — a
     * second refresher called from the same three places is a way for one of them to be forgotten.
     *
     * ⛔ The account id is read ONCE. Every `store.` call here decodes the whole account blob
     * afresh (`AccountBlobGate` memoises nothing) and this runs on the main thread at every tap of
     * a chevron, so asking twice for the same id is work paid on every account, IMAP or not.
     */
    private fun refreshCollapsedFolders() {
        val accountId = store.currentId()
        _collapsedFolders.value = accountId?.let { store.collapsedFolders(it) } ?: emptyMap()
        _folderRowsBadgeUnread.value = accountId?.let { repo.folderRowsBadgeUnread(it) } ?: false
    }

    /**
     * Store the drawer's fold/unfold choice for one folder, then re-read it.
     *
     * ⛔ Nothing else is armed: this is a display state and no push, notifier or sync depends on
     * it. [collapsed] is written both ways — writing false is a CHOICE, not the absence of one.
     */
    fun setFolderCollapsed(mailboxId: String, collapsed: Boolean) {
        val accountId = store.currentId() ?: return
        store.setFolderCollapsed(accountId, mailboxId, collapsed)
        refreshCollapsedFolders()
    }

    /** Toggle new-mail notifications for one folder, then re-arm push to pick it up. */
    fun setFolderWatched(mailboxId: String, watched: Boolean) {
        val accountId = store.currentId() ?: return
        store.setFolderWatched(accountId, mailboxId, watched)
        // Dropping the baseline means a later re-watch reseeds silently (no stale diff).
        if (!watched) NewMailNotifier.clear(getApplication(), accountId, mailboxId)
        refreshWatchedFolders()
        PushController.apply(getApplication(), userInitiated = true)
    }

    private fun folderOp(op: suspend (AccountCredentials) -> Unit) {
        viewModelScope.launch {
            val credentials = store.load() ?: return@launch
            runCatching { op(credentials) }
                .onFailure { _message.value = it.message ?: getApplication<Application>().getString(R.string.status_folder_op_failed) }
        }
    }

    /**
     * Toggle read/unread for the selection — marks read if any are unread, else marks unread —
     * and keeps the selection (only the read state changes, the list view stays put).
     *
     * Every selected key is resolved first ([resolveSelectionTargets]), rows drawn by a search
     * included: asking the cache and then walking the ANSWER dropped every hit with no row in the
     * local `emails` table, so from a search the toggle acted on some of the selection, said
     * nothing about the rest, and let the untouched rows vote on the direction (bench, 2026-08-08).
     * The direction is unchanged: one unread anywhere in the selection means mark read.
     *
     * Nothing here reads the row's own `mailboxId` (only its account and its id travel to the
     * server), so `folderTrusted` does not concern this path and a drawn row is as good as a
     * cached one.
     */
    fun toggleSelectedRead() {
        val keys = _selectedKeys.value
        if (keys.isEmpty()) return
        viewModelScope.launch {
            val resolved = resolveSelectionTargets(
                keys = keys,
                cached = repo.cachedEmailsByIds(keys),
                displayed = searchState.value.results,
            )
            val emails = resolved.targets.map { it.email }
            val targetSeen = !emails.all { it.isSeen }
            patchThreadMembersSeen(emails.mapTo(mutableSetOf()) { it.emailKey() }, targetSeen)
            // A key no row could be found for was never written: it counts as a failed attempt, or
            // a selection nothing at all resolved for would land on "nothing failed" and say
            // nothing. An account whose credentials are gone is the same kind of never-written.
            var failed = resolved.unresolved.size
            emails.forEach { email ->
                val credentials = credentialsFor(email)
                if (credentials == null) { failed++; return@forEach }
                runCatching { repo.setRead(credentials, email.id, targetSeen) }
                    .onFailure { failed++; android.util.Log.w("SternaInbox", "setRead (selection) failed for ${email.id}", it) }
            }
            if (targetSeen) dismissReadNotifications(emails.map { it.emailKey() })
            // Reflect the new state immediately so the toggle icon flips without re-selecting.
            _selectionAllRead.value = targetSeen
            // Partial and total are not the same news (the batched paths learned this the hard
            // way): "Couldn't complete the action" over a selection where nine of ten went through
            // is false. Counted over the WHOLE selection, unresolved keys included.
            when (bulkOutcome(attempted = keys.size, failed = failed)) {
                BulkOutcome.NONE -> Unit
                BulkOutcome.TOTAL -> _message.value = getApplication<Application>().getString(R.string.status_action_failed)
                BulkOutcome.PARTIAL -> _message.value = getApplication<Application>().getString(R.string.status_action_partly_failed)
            }
        }
    }

    /**
     * Which way the toggle's icon points, over the same resolution the action uses — otherwise the
     * icon speaks for the cached subset of a selection the action will act on whole.
     *
     * "All read" needs at least one target: `emptyList().all { }` is `true`, so a selection whose
     * rows are none of them cached used to draw "mark unread" over messages nobody had read.
     */
    private suspend fun refreshSelectionReadState(keys: Set<EmailKey>) {
        val targets = resolveSelectionTargets(
            keys = keys,
            cached = repo.cachedEmailsByIds(keys),
            displayed = searchState.value.results,
        ).targets
        _selectionAllRead.value = targets.isNotEmpty() && targets.all { it.email.isSeen }
    }

    // ---- inline search ----

    fun setSearchActive(active: Boolean) {
        searchJob?.cancel()
        searchRemoved.clear()
        searchState.value = if (active) SearchUi(active = true) else SearchUi()
        if (!active) {
            // Deliberately DON'T cancel the crawl: on a large mailbox it needs to run to completion,
            // and it is throttled + idempotent. Killing it on close (then hitting the throttle on
            // reopen) is exactly what froze coverage partway. Only the live query stops here.
            return
        }
        // Instant coverage floor from the cache (the first query awaits this), then crawl the whole
        // mailbox's headers into the index in the background. The crawl walks newest→oldest, so merge
        // the current query's local hits in after EACH page: older mail appears progressively and a
        // stalled/slow page can't hide it. Merges only ever ADD, so nothing flickers away.
        indexJob = viewModelScope.launch { runCatching { repo.seedIndexFromCache() } }
        // Guard against a second concurrent crawl if search is reopened while one is still running.
        if (crawlJob?.isActive == true) return
        crawlJob = viewModelScope.launch {
            val refresh: suspend () -> Unit = {
                searchState.value.query.takeIf { it.isNotBlank() }?.let { q ->
                    val local = runCatching { repo.searchIndex(q) }.getOrNull()
                    if (local != null && searchState.value.query == q) {
                        searchState.value = searchState.value.copy(
                            results = mergeHits(searchState.value.results.orEmpty(), local),
                        )
                    }
                }
            }
            searchAccounts().forEach { runCatching { repo.syncSearchIndex(it, onPage = refresh) } }
            refresh()
        }
    }

    fun setSearchQuery(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            searchState.value = searchState.value.copy(query = query, results = null, loading = false)
            return
        }
        searchState.value = searchState.value.copy(query = query, loading = true, complete = true)
        searchJob = viewModelScope.launch {
            // 1) Local FTS first: instant on every keystroke, offline, accent-folded, prefix-matched
            //    ("eco*" finds écologie/écologique/…), over the header index of the whole mailbox.
            indexJob?.join()
            // A failed local index gets the same treatment as a failed server leg below: it is a
            // leg of the answer that didn't run. A locked or damaged FTS table (the ground of #71)
            // otherwise dropped every hit only this index finds — accent-folded, prefix-matched —
            // in silence, under a count still calling itself a total.
            var localComplete = true
            val local = runCatching { repo.searchIndex(query) }.getOrElse { error ->
                if (error is CancellationException) throw error
                localComplete = false
                emptyList()
            }
            if (searchState.value.query != query) return@launch
            searchState.value = searchState.value.copy(
                results = local.filterNot { it.emailKey() in searchRemoved },
                loading = true,
                complete = localComplete,
            )
            // 2) Server full-text after a short typing pause: the server's own index sees everything
            //    (message bodies, the whole archive) in ~a second — no client-side re-indexing needed.
            //    UNION only: server hits can add to what's shown, never remove it; cancellation (new
            //    keystroke) plus the current-query check discard stale responses, so results can't
            //    flicker away or depend on typing speed.
            delay(SERVER_SEARCH_DEBOUNCE_MS)
            // A failed server leg (or one that dropped an unreachable account) must not pass for
            // a complete answer: the count says "at least N" instead of a total it can't back.
            val server = runCatching {
                repo.search(searchAccounts(), SearchQuery(text = query), SERVER_SEARCH_LIMIT)
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                MailSearchResult(emptyList(), complete = false)
            }
            if (searchState.value.query == query) {
                searchState.value = searchState.value.copy(
                    results = mergeHits(searchState.value.results.orEmpty(), server.emails),
                    loading = false,
                    // BOTH legs must have run to the end for this to be a total; a good server
                    // answer doesn't undo a local index that fell over.
                    complete = searchComplete(local = localComplete, server = server.complete),
                )
            }
        }
    }

    /** The accounts the current view searches over (unified inbox → all, single folder → current). */
    private fun searchAccounts(): List<AccountCredentials> = when (selection.value) {
        Sel.Unified -> store.allCredentials()
        // One account's folders, so one account's search — same answer as a single folder.
        Sel.Unread -> listOfNotNull(store.load())
        is Sel.Folder -> listOfNotNull(store.load())
    }

    /** Union of two hit lists (by account+id), newest first. Rows the user just removed stay
     *  out: the FTS index and the server both still know an evicted/held-back message, so an
     *  unfiltered merge would resurrect the swiped-away row (see [searchRemoved]). */
    private fun mergeHits(a: List<Email>, b: List<Email>): List<Email> =
        (a + b).distinctBy { it.accountId to it.id }
            .filterNot { it.emailKey() in searchRemoved }
            // receivedAt is an ISO-8601 UTC string, so lexicographic sort == chronological.
            .sortedByDescending { it.receivedAt ?: "" }

    private data class Status(val refreshing: Boolean, val error: String?)

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
        const val PURGE_HOLD_BACK_MS = 5_000L

        /** Most threads re-completed per action — bounds the Thread/get fan-out on a select-all. */
        const val MAX_THREAD_COMPLETIONS = 10
    }
}

/**
 * The banners a just-performed local removal must take down: one key per Undo entry.
 *
 * Two things it must not get wrong, both silent:
 *
 *  - the id is [UndoEntry.emailId], i.e. the id the message wore AT THE GESTURE. An IMAP id IS
 *    `imap:account:folder:uid`, so the move that deletes or archives the message rewrites it, and an
 *    Undo writes a third one; the banner on the shade only ever answers to the one that was live
 *    when it was posted.
 *  - the key carries the entry's OWN [UndoEntry.accountId], and a null one stays null. Two accounts
 *    on one server are routinely handed the same message id, so an unqualified id cancels the
 *    neighbour account's banner (#92); and null is resolved to the current account downstream, by
 *    the same `credentialsFor` every other key path goes through — inventing an account here would
 *    aim the cancel at a shade the gesture never touched.
 */
internal fun undoBannerKeys(entries: List<UndoEntry>): List<EmailKey> =
    entries.map { EmailKey(it.accountId, it.emailId) }

/**
 * The single account a multi-select belongs to, or null when it is empty or spans accounts. Pure,
 * so the move-to-folder picker's account resolution can be unit-tested (#73 multi-account): the
 * picker offers this account's folders and the move targets it, instead of the active account's.
 */
internal fun selectionAccount(keys: Set<EmailKey>): String? =
    keys.map { it.accountId }.distinct().singleOrNull()

/**
 * Whether the LIST's move picker may name an account other than the one the gesture will act on —
 * true only while the selection resolves to ONE account ([selectionAccount]).
 *
 * The guard exists because the two ends disagree on a selection spanning accounts. The move keeps
 * main's behaviour there — it takes the retained account's messages and counts the others as
 * skipped, under "Some messages couldn't be processed." (spec.md § 1, mixed selection out of
 * scope) — while [moveOwnerAccountId] has no selection account to work from and falls back to the
 * ACTIVE one (`InboxViewModel.kt:990-992`). Handed that fallback as its owner, the picker would
 * offer every account, and "B › Archive" would announce a move half the selection never makes.
 */
internal fun selectionAllowsAnotherAccount(keys: Set<EmailKey>): Boolean = selectionAccount(keys) != null

/**
 * The accounts the LIST's move picker may offer, given what is selected: all of them while the
 * selection allows another account, none at all otherwise.
 *
 * Returning an empty list is enough to draw nothing: [MoveAccountRow] returns straight away when
 * `pickerAccounts(...)` holds fewer than two entries (`MoveAccountRow.kt:48-49`), so a mixed
 * selection gets back exactly the picker it had before #189.
 */
internal fun selectionPickerAccounts(
    accounts: List<StoredAccount>,
    keys: Set<EmailKey>,
): List<StoredAccount> = if (selectionAllowsAnotherAccount(keys)) accounts else emptyList()

/**
 * The account choice the LIST's picker is allowed to ACT on: the chosen one while the selection
 * allows another account, null otherwise — back to the owner's own folders.
 *
 * Hiding the account row is not enough on its own. The choice is held by the ViewModel so it
 * survives a rotation, and the picker's three exits are the only things that put it back; make the
 * sheet disappear another way (rotation closes it, the ViewModel lives on) and the next opening,
 * on a DIFFERENT selection, still lists the chosen account's folders and still sends the gesture
 * there — with no row on screen to say so. Neutralising the choice at the reading end, once, keeps
 * what the picker lists and where the tap goes on the same answer as the row (#189).
 */
internal fun selectionMoveAccount(chosen: String?, keys: Set<EmailKey>): String? =
    chosen.takeIf { selectionAllowsAnotherAccount(keys) }

/**
 * What "Select all" is allowed to take: the list that is ON SCREEN.
 *
 * Two branches, because the two lists are not the same kind of thing. The search results are held
 * whole in the ViewModel and drawn straight from there, so "everything shown" is a value we have.
 * The navigation list is a `LazyPagingItems`, which does not expose the pages it has not loaded —
 * "what the list renders" cannot be asked of it — so outside a search the folder's cached ids stay
 * the answer, as they have always been.
 *
 * The predicate is the SCREEN's, not a second one written to look like it: results are on screen
 * when the search bar is up with something typed in it (`InboxScreen`'s `searchActive`) AND the
 * same [searchDisplay] call the screen branches on answers [SearchDisplay.RESULTS]. Two predicates
 * for one question drift apart, and the drift is silent — the user selects one list and deletes
 * another.
 */
/**
 * The roles map of [InboxViewModel.folderRoles], as a flow of its own so the RE-SCOPING can be run
 * by a test (#115). [accounts] is the account list as a live value; [roles] opens the (account,
 * folder) → role map for a set of account ids.
 *
 * What it has to get right is when it asks again. The map is keyed by account, so it has to be
 * reopened whenever the SET of accounts changes — an account added, an account signed out — or the
 * rows of the new account are judged by a map that does not contain them, and fall back to the
 * pre-#115 answer while the reader, which reads the database directly, says something else.
 *
 * That is why it hangs off the account list itself and not off `unifiedInboxScopes`, which is what
 * shipped first: those are (account, inbox) PAIRS, an account has no inbox id until its first
 * folder sync, and a StateFlow does not re-emit an equal value — so adding an account moved
 * nothing at all. The set of ids is what the map is keyed by, and [distinctUntilChanged] on it is
 * what keeps the store's other writes (an inbox id learned, an unread counter moved, identities
 * refreshed — all of which republish the whole list) from re-subscribing to Room for nothing.
 */
internal fun folderRolesFlow(
    accounts: Flow<List<StoredAccount>>,
    roles: (List<String>) -> Flow<Map<Pair<String, String>, String>>,
): Flow<Map<Pair<String, String>, String>> =
    accounts
        .map { list -> list.map { it.id } }
        .distinctUntilChanged()
        .flatMapLatest(roles)

internal fun selectAllKeys(
    searching: Boolean,
    query: String,
    results: List<EmailKey>?,
    loading: Boolean,
    complete: Boolean,
    folderKeys: List<EmailKey>,
): Set<EmailKey> {
    val shown = results.orEmpty()
    val onScreen = searching && query.isNotBlank() &&
        searchDisplay(shown.size, loading, complete) == SearchDisplay.RESULTS
    return if (onScreen) shown.toSet() else folderKeys.toSet()
}

/**
 * Which sentence a failed row action shows: the offline one, or the generic one.
 *
 * The exception's own text never reaches the screen. `it.message` on a transport failure is the
 * platform resolver's string — `Unable to resolve host "…": No address associated with hostname` —
 * in English whatever the app's language, and it named nothing the reader could act on. It is
 * logged instead; the sentence returned here is what is read.
 *
 * The same pair of helpers as the attachment failure ([app.sterna.net.isOfflineFailure]), and the
 * same rule for the offline half: "offline" is a claim about the DEVICE, so it is only made when
 * the transport died AND the device's own connectivity check confirms there is no usable network.
 * A server that is down, a rejected password, a VPN killswitch on a live Wi-Fi — all keep the
 * generic wording, because telling somebody plainly online that they are offline is defect #65 all
 * over again.
 *
 * It deliberately parts from the composer on the OTHER half: `ComposeViewModel.attach` keeps the
 * exception text in its non-offline sentence, this one does not. A list row's failure is read in
 * a snackbar that is gone in four seconds, and its own twin one call away — `threadSwipeRemove`,
 * for a folded conversation — has always shown the generic sentence. Closing that asymmetry is the
 * point; the technical text is in logcat for both.
 *
 * Returns a string resource rather than the text so the decision runs in a plain JVM test:
 * `InboxViewModel` is an `AndroidViewModel` and cannot be instantiated in one.
 */
@StringRes
internal fun actionFailureMessage(t: Throwable, online: Boolean): Int =
    if (isOfflineFailure(t, online)) R.string.status_action_offline else R.string.status_action_failed
