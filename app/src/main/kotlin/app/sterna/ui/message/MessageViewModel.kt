package app.sterna.ui.message

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import app.sterna.core.data.mail.MessageCrypto
import app.sterna.core.data.mail.MessageUnavailableException
import app.sterna.core.data.mail.requireAttachmentBytes
import app.sterna.core.data.pgp.PgpResult
import app.sterna.core.imap.CryptoKind
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.R
import app.sterna.push.Notifications
import app.sterna.snooze.Snoozes
import app.sterna.ui.compose.receivingAddress
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.accountAddresses
import app.sterna.core.data.calendar.ICalendar
import app.sterna.core.data.calendar.ParsedEvent
import app.sterna.core.data.getOrElseUnlessCancelled
import app.sterna.core.data.filter.BlockOutcome
import app.sterna.core.data.filter.addBlockRule
import app.sterna.core.data.filter.alreadyBlocked
import app.sterna.core.data.filter.blockableSender
import app.sterna.core.data.mail.FilterRulesState
import app.sterna.core.data.mail.UnsubscribeAction
import app.sterna.core.data.mail.UnsubscribeHeader
import app.sterna.core.data.mail.UnsubscribeMailPreview
import app.sterna.core.data.mail.UnsubscribeOptions
import app.sterna.core.data.mail.confirmationTarget
import app.sterna.core.data.mail.preferredAction
import app.sterna.core.data.mail.unsubscribePreview
import app.sterna.core.data.settings.REPLY_BAR_DEFAULT
import app.sterna.core.data.settings.MessageTextSize
import app.sterna.core.data.unsubscribe.UnsubscribeFailure
import app.sterna.core.data.unsubscribe.UnsubscribeResult
import app.sterna.core.jmap.ContentTooLargeException
import app.sterna.core.jmap.DownloadLimits
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailHeader
import app.sterna.core.jmap.model.Mailbox
import app.sterna.net.hasUsableNetwork
import app.sterna.ui.readerActionFailureText
import app.sterna.ui.inbox.isSelfAuthored
import app.sterna.ui.inbox.moveTargets
import app.sterna.ui.inbox.pickerAccount
import app.sterna.ui.inbox.pickerExcludedMailbox
import app.sterna.ui.inbox.showOnlySubscribedFor
import app.sterna.ui.sender.trashFilePath
import app.sterna.ui.showsRecipients
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface MessageState {
    data object Loading : MessageState
    data class Loaded(val email: Email) : MessageState
    data class Error(val message: String) : MessageState
}

/**
 * The opened message. [header] has the summary fields (from/date/subject/preview), shown at
 * once; [body] is the full email (with body parts) and its [inlineImages]. The reader shows a
 * single message — the conversation it belongs to lives in the list's inline unfold.
 */
data class ThreadMessage(
    val id: String,
    val header: Email,
    val body: Email? = null,
    val inlineImages: Map<String, String> = emptyMap(),
)

/**
 * Download/parse state for a calendar invite on the open message. [event] is the parsed
 * event when reading succeeded; [failed] is true if the .ics couldn't be fetched or parsed
 * (the card then offers to open the raw invitation). [part] / [ownerId] drive that fallback.
 */
data class CalendarInvite(
    val loading: Boolean = false,
    val event: ParsedEvent? = null,
    val failed: Boolean = false,
    val part: EmailBodyPart? = null,
    val ownerId: String? = null,
    /** State of the RSVP reply for this invite (idle until the user taps Accept/Decline/…). */
    val response: InviteResponse = InviteResponse.Idle,
)

/** RSVP reply lifecycle for a calendar invite, reflected on the event card. */
sealed interface InviteResponse {
    data object Idle : InviteResponse
    data object Sending : InviteResponse
    /** Reply sent; [partstat] is ACCEPTED / DECLINED / TENTATIVE. */
    data class Sent(val partstat: String) : InviteResponse
    data object Failed : InviteResponse
}

/** OpenPGP state of the opened message, driving the status card + header badges. */
sealed interface CryptoUiState {
    /** Ordinary mail — no crypto UI at all. */
    data object None : CryptoUiState

    /** Crypto detected; [decrypting] while a decrypt/verify call is in flight. */
    data class Locked(val kind: CryptoKind, val decrypting: Boolean = false) : CryptoUiState

    /** The provider needs the user (passphrase/key); launch [pendingIntent] to continue. */
    data class NeedsInteraction(val pendingIntent: PendingIntent) : CryptoUiState

    /** Decrypted and/or verified. */
    data class Decrypted(val result: MessageCrypto.Decrypted) : CryptoUiState

    /** Decrypt/verify failed; null message = no OpenPGP provider installed. */
    data class Failed(val message: String?) : CryptoUiState
}

/** What the sender's row in the participants panel offers about the per-sender filter rule. */
enum class SenderRuleEntry {
    /** No entry at all. */
    ABSENT,

    /** There, and tappable. */
    OFFERED,

    /** There, greyed: the account's script already sends this address away. */
    ALREADY_RULED,

    /** There, greyed: another Sieve script is active, so saving would switch it off. */
    FOREIGN_SCRIPT,
}

/**
 * What the reader knows about the account's Sieve script — and it is THREE states because two of
 * them used to be one, which is half of what R6 was.
 *
 * "Not read yet" and "read and got no answer" were both a null [FilterRulesState], and the entry
 * treated them alike: offered. They are not alike. Nothing has been asked of the server in the
 * first, so the entry names four answers it has no grounds for; a question WAS asked and failed
 * in the second, and that is a state the gesture survives (see [senderRuleEntry]).
 */
sealed interface SenderScript {
    /** Nothing has been asked. The panel arms the read; until it answers there is no opinion. */
    data object Unread : SenderScript

    /** A read was made and did not answer — offline, a dead connection, a server that threw. */
    data object Unreachable : SenderScript

    /** The server answered, with whatever it had to say. */
    data class Read(val state: FilterRulesState) : SenderScript
}

/**
 * Whether the reader may add "file future mail from this address into the Trash, marked read"
 * from the message it is reading, and in what state the entry appears.
 *
 * The same gesture the per-sender screen carries, and it is HERE for the reason the screen alone
 * could not cover: the screen's aggregate excludes the Trash, so deleting a sender's mail takes
 * its row away and the gesture with it, and the moment one actually wants the rule is the moment
 * one is reading the mail that prompted it.
 *
 * [isSender] — the row is in the From group. To and Cc are not who wrote, and a FROM rule on a
 * recipient is a rule about mail that person has not sent; the entry is not offered there.
 *
 * [address] and [ownAddresses] are the two noes that belong to the ADDRESS rather than to the
 * account's script — one's own address, and no address at all. They are
 * [app.sterna.core.data.filter.blockableSender]'s, written once and asked by both surfaces,
 * because the reader reached them from the Sent folder and the per-sender screen reaches them
 * from any ordinary folder: neither is a case the other can be trusted to have caught.
 *
 * The rest are the screen's noes, with ONE deliberate difference. [trashPath] null and
 * [FilterRulesState.Unsupported] take the entry away exactly as they do on the screen: nothing
 * honest to offer, and nothing invented to stand in. But `foreignActiveScript` GREYS the entry
 * here instead of hiding it — the difference is that a dialog has room for the reason and a list
 * row has not, which is the whole benefit of this location. It stays disabled, and it opens
 * nothing that would offer to take the running script over: that decision belongs to the Filters
 * screen, which warns in red above its Save button.
 *
 * The two halves of what used to be "null" part company here, and the line between them is the
 * one the per-sender screen already draws:
 *
 *  - [SenderScript.Unread] — **absent**. The panel has only just armed the read; an entry drawn
 *    before the answer is a promise made on no information, and the four states it can be in are
 *    exactly what has not been read yet. The screen holds it absent for the same span.
 *  - [SenderScript.Unreachable] — **offered**. Hiding it there would make an unreachable server
 *    look like an IMAP account, with no word and no retry, and the gesture is merely unavailable
 *    rather than wrong: tapping it runs [app.sterna.core.data.filter.addBlockRule], which reads
 *    again at the moment of writing and reports what happened. That read is the guard, and it is
 *    absolute: nothing here can talk it into writing over another script.
 */
fun senderRuleEntry(
    isSender: Boolean,
    trashPath: String?,
    script: SenderScript,
    address: String,
    ownAddresses: List<String>,
): SenderRuleEntry {
    val rules = (script as? SenderScript.Read)?.state
    return when {
        !isSender -> SenderRuleEntry.ABSENT
        // Before the account's script is consulted at all: no state of the server makes a rule
        // on one's own address, or on no address, into something worth offering.
        !blockableSender(address, ownAddresses) -> SenderRuleEntry.ABSENT
        trashPath == null -> SenderRuleEntry.ABSENT
        script is SenderScript.Unread -> SenderRuleEntry.ABSENT
        rules is FilterRulesState.Unsupported -> SenderRuleEntry.ABSENT
        // "Already there" answers before "cannot", for the same reason addBlockRule does: neither
        // case writes anything, they differ only in what the reader is told, and "another script is
        // active" on an address that is already handled reports an obstacle where there is none.
        rules is FilterRulesState.Loaded && alreadyBlocked(rules.rules, address) ->
            SenderRuleEntry.ALREADY_RULED
        rules is FilterRulesState.Loaded && rules.foreignActiveScript -> SenderRuleEntry.FOREIGN_SCRIPT
        else -> SenderRuleEntry.OFFERED
    }
}

/** The words each state of the entry wears — greyed entries say WHY, they do not just look dead. */
fun senderRuleLabel(entry: SenderRuleEntry): Int = when (entry) {
    SenderRuleEntry.ALREADY_RULED -> R.string.sender_volume_block_done
    SenderRuleEntry.FOREIGN_SCRIPT -> R.string.sender_volume_block_foreign
    else -> R.string.sender_volume_block
}

/**
 * Lifecycle of an unsubscribe on the open message, reflected on the banner above the body.
 * Session-only, like [InviteResponse]: remembering that a list was left would mean a column in
 * the message table, and that is a later piece of work with its own migration.
 */
sealed interface UnsubscribeState {
    data object Idle : UnsubscribeState

    /** The POST is in flight (the `mailto:` form never lingers here — the outbox takes it). */
    data object Sending : UnsubscribeState

    /** The sender's server accepted the one-click request. */
    data object Sent : UnsubscribeState

    /** The unsubscribe mail is in the outbox; delivery and retry are the outbox's business. */
    data object Queued : UnsubscribeState

    data class Failed(val reason: UnsubscribeFailure) : UnsubscribeState
}

/**
 * An unsubscribe waiting for the reader to confirm it, WITH the options it was offered for.
 *
 * The options travel with the pending gesture instead of being read again when the button is
 * pressed, so what the dialog names and what the request goes to cannot come apart. They can:
 * the reader is a pager over live state, and the message behind an open dialog can be reloaded
 * (a settle, a refresh, a swipe) — the second read then answers for another message, or for
 * none, and the button would have sent an unsubscribe to the previous list, or to nothing.
 */
data class PendingUnsubscribe(
    val action: UnsubscribeAction,
    val options: UnsubscribeOptions,
) {
    /** What the confirmation names — the same decision the action itself follows. */
    val target: String? get() = options.confirmationTarget(action)

    /**
     * The subject and body the mail would carry, or null when this gesture is not a mail.
     *
     * Read from [unsubscribePreview], which is also what `MailRepository.sendUnsubscribeMail`
     * builds the outbox row from: the dialog cannot show one text and send another. Both come
     * from the sender of the received message and go out under the account's identity, so they
     * are shown before the send, not described in the abstract.
     */
    val mailPreview: UnsubscribeMailPreview? get() = options.mailto?.let { unsubscribePreview(it) }
}

/**
 * The gesture still on offer for [options] in [state], or null when there is nothing left to
 * press. ONE decision, executed by the banner, the overflow entry and the action itself, because
 * the three disagreeing is precisely how a list gets left twice.
 *
 * [UnsubscribeState.Sent] and [UnsubscribeState.Queued] are done: the request went out (or is in
 * the outbox, which will send it), and offering the button again invites a second POST to a
 * server that already answered — for a large sender, from an address that just proved it is read.
 * [UnsubscribeState.Sending] is in flight. A [UnsubscribeState.Failed] IS offered again: a
 * refusal or a dead network is a retry, not a dead end.
 *
 * ⚠ Bounded by the PAGE's composition, and deliberately so — not by the process. This state lives
 * in the per-page [MessageViewModel], whose store is cleared as soon as the page leaves the pager
 * (see `rememberDisposableViewModelStoreOwner`), so a rotation, a back-and-return, or swiping two
 * messages away and back is enough for the button to come back. Remembering it any longer is a
 * column in the message table, which this lot does not open.
 */
fun offeredUnsubscribeAction(options: UnsubscribeOptions?, state: UnsubscribeState): UnsubscribeAction? =
    when (state) {
        UnsubscribeState.Sending, UnsubscribeState.Sent, UnsubscribeState.Queued -> null
        UnsubscribeState.Idle, is UnsubscribeState.Failed -> options?.preferredAction()
    }

/**
 * Which of its four shapes the unsubscribe strip draws — the decision taken out of the
 * `@Composable` so a JVM test can RUN it.
 *
 * [ACTION] and [SENDING] and [DONE] are the same height on purpose, and that is not a taste: the
 * measured height of the header is part of the key of the `remember` that builds the body's HTML
 * document, so a strip that changes size **cancels the body load in flight and starts another**.
 * The header is where an unsubscribe happens, so before this the reader reloaded the message
 * every time someone left a list. [FAILED] is the one shape allowed to grow — it carries a
 * sentence the reader has to read, and it only appears after a deliberate gesture.
 */
enum class UnsubscribeStripBody {
    /** Nothing done yet: one button, no sentence. */
    ACTION,

    /** In flight: the same button, its icon replaced by a spinner, and it cannot be pressed. */
    SENDING,

    /** Sent or queued: no button at all, one terminal line. */
    DONE,

    /** Refused, or the network died: the reason, and the button again. */
    FAILED,
    ;

    /**
     * Whether the strip's button may START an unsubscribe here.
     *
     * It must agree with [offeredUnsubscribeAction] in every state, and a test holds the two
     * together: the strip drawing a live button where the action refuses to run is a button that
     * does nothing, and the reverse is the second POST to a list that already answered.
     */
    val acts: Boolean get() = this == ACTION || this == FAILED
}

/** See [UnsubscribeStripBody]. */
fun unsubscribeStripBody(state: UnsubscribeState): UnsubscribeStripBody = when (state) {
    UnsubscribeState.Idle -> UnsubscribeStripBody.ACTION
    UnsubscribeState.Sending -> UnsubscribeStripBody.SENDING
    UnsubscribeState.Sent, UnsubscribeState.Queued -> UnsubscribeStripBody.DONE
    is UnsubscribeState.Failed -> UnsubscribeStripBody.FAILED
}

/**
 * The raw-headers viewer's state (issue #60). Null (see [MessageViewModel.headers]) means the
 * viewer is closed; the states below drive it while open.
 */
sealed interface HeadersState {
    data object Loading : HeadersState
    data class Loaded(val headers: List<EmailHeader>) : HeadersState
    data class Error(val message: String) : HeadersState
}

/**
 * The attached-message sheet's state: what a tap on a `message/rfc822` part opens. Null (see
 * [MessageViewModel.attachedMessage]) means the sheet is closed; the states below drive it while
 * open. Same shape as [HeadersState].
 */
sealed interface AttachedMessageState {
    data object Loading : AttachedMessageState
    data class Loaded(val message: AttachedMessage) : AttachedMessageState
    data class Error(val message: String) : AttachedMessageState
}

/** A copy with the $seen keyword set, so a just-opened/expanded message renders as read. */
private fun Email.markRead(): Email =
    if (isSeen) this else copy(keywords = keywords + ("\$seen" to true))

/** What [MessageViewModel.confirmLinks] carries before DataStore has answered: ask. The reader can
 *  dismiss a confirmation they did not want, but cannot un-open a page — seeded `false`, a link
 *  tapped in the reader's first frames was fetched straight away, silently, from a setting that had
 *  not been read yet. The safe side of this one is the one that costs a tap. */
internal const val CONFIRM_LINKS_UNLOADED = true

/** What the reader's REMOTE-IMAGE decision assumes about the reading-mode setting (#149) while
 *  DataStore has not answered: plain text, i.e. no image fetched. Same trade as
 *  [CONFIRM_LINKS_UNLOADED] and the same asymmetry — a message shown as HTML for one frame too many
 *  costs a frame, an image fetched in that frame has already told the sender the mail was opened and
 *  cannot be un-fetched. It is only the IMAGE half: what is RENDERED in that window is the stored
 *  default, or the reader would see a flash of text on every message swiped past. See
 *  [plainTextForImages] and [plainTextForBody], which are what execute both. */
internal const val PLAIN_TEXT_UNLOADED_FOR_IMAGES = true

/**
 * Which failure to open a message earns a sentence of OUR own, and which keeps the text it carries.
 *
 * Null means "no sentence": the exception's own message reaches the screen. A resource means the
 * technical text is REPLACED — not completed: the strings these exceptions carry name the account
 * UUID, the folder path and the uid, in English, and none of that belongs on a reader's screen
 * (#159). What a bug report needs is then in logcat and nowhere else, which is why the catch in
 * [MessageViewModel.load] logs the throwable before showing any of this.
 *
 * Pure, and at file level, because [MessageViewModel.readFailureText] needs an `Application` and no
 * JVM test can run it. This is the decision itself, so a test can execute it.
 */
internal fun readFailureStringRes(t: Throwable): Int? = when (t) {
    is ContentTooLargeException -> R.string.status_message_too_large
    // ⛔ ONE sentence, naming no cause. Offline, destroyed server-side and a renumbered folder are
    // the expected three, but the repository wraps EVERY failure of that fetch — an expired
    // password and a bad certificate land here too, and a wording that guessed "deleted, or your
    // connection is down" would be telling those readers something false.
    is MessageUnavailableException -> R.string.status_message_unavailable
    else -> null
}

@OptIn(ExperimentalCoroutinesApi::class)
class MessageViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val repo = application.container.mailRepository
    private val storage = application.container.storageRepository
    private val settings = application.container.settingsRepository

    /** Whether tapped links should have tracking params stripped before opening. */
    val stripTracking = settings.stripTrackingParams.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true,
    )

    /** Whether to show the destination and ask before opening a tapped link. */
    val confirmLinks = settings.confirmLinks.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CONFIRM_LINKS_UNLOADED,
    )

    /**
     * Whether an opened message that asked for a read receipt may raise the question — the setting
     * of [ReadReceiptSetting], ⛔ NOT a `Boolean`.
     *
     * The third value is the point: mapped to `false` while DataStore answers, "not read yet" and
     * "the reader said no" would be the same thing, and the guard over everyone who never turned
     * this on would hold by coincidence of a default. It is read at the moment the page settles —
     * the earliest a question could be raised — so losing the race means no question on that
     * message, which is the harmless side.
     *
     * ⭐ [SharingStarted.Eagerly], alone among the flows above, and it is required rather than
     * chosen: this one is not collected by a composable. It is read by [maybeMarkRead] through
     * `.value`, and under `WhileSubscribed` a flow nobody subscribes to never starts — it would sit
     * on [ReadReceiptSetting.NOT_LOADED] for the life of the page and the switch would appear to do
     * nothing at all.
     */
    val readReceiptSetting: StateFlow<ReadReceiptSetting> = settings.askReadReceipt
        .map { if (it) ReadReceiptSetting.ON else ReadReceiptSetting.OFF }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ReadReceiptSetting.NOT_LOADED,
        )

    /** Sender addresses whose remote images load automatically. */
    val imageAllowlist = settings.imageAllowlist.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptySet(),
    )

    /** Whether the reader shows the bottom Reply/Forward bar at all (#63). Global, so the bar does
     *  not come and go while paging through a unified inbox's messages. The first frame, before
     *  DataStore has answered, is REPLY_BAR_DEFAULT: the same definition the stored value is read
     *  against, rather than a second copy of it here. */
    val replyBar = settings.replyBar.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = REPLY_BAR_DEFAULT,
    )

    /** Reading text size for the message body. */
    val messageTextSize = settings.messageTextSize.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MessageTextSize.NORMAL,
    )

    /** Add/remove a sender from the always-show-images allowlist. */
    fun setImagesAlwaysAllowed(sender: String, allowed: Boolean) {
        viewModelScope.launch { settings.setImageAllowed(sender, allowed) }
    }

    private val _state = MutableStateFlow<MessageState>(MessageState.Loading)
    val state = _state.asStateFlow()

    /** The whole conversation as a stack of collapsible messages, oldest first. */
    private val _messages = MutableStateFlow<List<ThreadMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    /** Transient status while downloading/opening an attachment. */
    private val _attachmentStatus = MutableStateFlow<String?>(null)
    val attachmentStatus = _attachmentStatus.asStateFlow()

    /** The opened message's calendar invite (if any): download/parse state for the event card. */
    private val _calendar = MutableStateFlow<CalendarInvite?>(null)
    val calendar = _calendar.asStateFlow()

    /** Guards against re-downloading the invite on every recomposition of the same message. */
    private var calendarLoadedFor: String? = null

    /**
     * An attachment is already on its way to a viewer app. Tapping one starts a download, so the
     * chooser appears a beat later and a second tap in between used to start the whole thing again
     * — two downloads, then two choosers stacked on top of each other for one file.
     *
     * The screen's own [app.sterna.ui.rememberLeaveOnce] cannot cover this one: the hand-off happens
     * here, after a suspending download, not in the tap handler, and a ViewModel has no composition
     * to latch onto. Same shape as the [calendarLoadedFor] guard just above.
     */
    private var openingAttachment = false

    private fun updateMessage(id: String, transform: (ThreadMessage) -> ThreadMessage) {
        _messages.value = _messages.value.map { if (it.id == id) transform(it) else it }
    }

    /** Role of the folder the opened message is filed under, the single source of truth behind
     *  the folder-dependent reader actions (spam ↔ not-spam, destroy vs. move-to-Trash, and the
     *  actions Drafts/Sent must not offer at all — Codeberg #82). */
    private val _mailboxRole = MutableStateFlow<String?>(null)
    val mailboxRole = _mailboxRole.asStateFlow()

    /** Whether the opened message is in the Junk folder (drives Report spam ↔ Not spam). */
    val inJunk = _mailboxRole
        .map { it == "junk" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** True when the open message sits in Trash, so the reader's delete destroys (Codeberg #23). */
    val inTrash = _mailboxRole
        .map { it == "trash" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** True when the open message is the user's own outgoing mail, so the header names the
     *  recipients instead of the sender — yourself (Codeberg #59). */
    private val _ownMessage = MutableStateFlow(false)
    val ownMessage = _ownMessage.asStateFlow()

    /** Which of the account's own addresses the open message was addressed to, named in the
     *  participants panel so an account with several aliases can tell which one received it
     *  (Codeberg #81). Null when none is in To/Cc (mailing list, Bcc) — then nothing is shown
     *  rather than a guess. */
    private val _deliveredTo = MutableStateFlow<String?>(null)
    val deliveredTo = _deliveredTo.asStateFlow()

    /** Deadline of the snooze in force on the open message, or null when it is not snoozed.
     *  Lets the Snooze menu say WHEN the message comes back instead of listing mute delays
     *  (Codeberg #82). A snoozed message is still reachable — e.g. from search. */
    private val _snoozedUntil = MutableStateFlow<Long?>(null)
    val snoozedUntil = _snoozedUntil.asStateFlow()

    /** The folder the open message is filed under, resolved reliably (the body fetch can drop it).
     *  Handed to the inbox's delete so it can tell move-to-Trash from a permanent destroy (#23). */
    private val _mailboxId = MutableStateFlow<String?>(null)
    val mailboxId = _mailboxId.asStateFlow()

    /** Local account the open message belongs to, resolved once per [load] (the page's own
     *  account in the unified inbox, else the current one). Scopes [moveTargets]. */
    private val _ownerAccountId = MutableStateFlow<String?>(null)

    /** The owner, for the picker's account row (#189). */
    val moveOwnerAccountId: StateFlow<String?> = _ownerAccountId.asStateFlow()

    /**
     * The account chosen on the picker's account row (#189), null for the owner's. Survives a
     * rotation here; put back to null when the picker closes ([chooseMoveAccount]) and when
     * another message is loaded ([load]).
     */
    private val _moveAccountId = MutableStateFlow<String?>(null)
    val moveAccountId: StateFlow<String?> = _moveAccountId.asStateFlow()

    fun chooseMoveAccount(id: String?) {
        _moveAccountId.value = id
    }

    /** The configured accounts, for the picker's account row. */
    val accounts: StateFlow<List<StoredAccount>> get() = store.accountsFlow

    /** The account whose folders the picker LISTS ([pickerAccount]): the chosen one, else the owner. */
    private val movePickerAccountId: Flow<String?> =
        combine(_moveAccountId, _ownerAccountId) { chosen, owner -> pickerAccount(chosen, owner) }

    /**
     * Folders offered by the reader's move-to-folder picker (#73): the folders of THE OPEN
     * MESSAGE'S account, minus the one it is filed under, ordered like the list's own picker.
     *
     * Scoped by [_ownerAccountId], never by "the current account": in the unified inbox the
     * message on screen can belong to a sibling account, and mailbox ids collide between two
     * accounts of the same server (#92) — a picker fed the wrong account's folders would move
     * the message somewhere nobody chose. Empty until the message (and its account) is known.
     *
     * The same account decides whether unsubscribed folders are offered (#174): the flag is read
     * off THAT record, and handed to [moveTargets] as a parameter rather than applied here, so
     * only the targets are filtered while the parent path under each row is still resolved
     * against the whole list ([pickerMailboxes], #109).
     *
     * With another account chosen on the picker's row (#189), THAT account's folders are listed
     * instead, its own flag applies, and the message's folder is no longer left out
     * ([pickerExcludedMailbox]): its id names nothing there, or a homonym (#92).
     */
    val moveTargets: StateFlow<List<Mailbox>> = combine(
        movePickerAccountId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else repo.observeMailboxes(id)
        },
        combine(_moveAccountId, _ownerAccountId, _mailboxId) { chosen, owner, current -> pickerExcludedMailbox(chosen, owner, current) },
        combine(store.accountsFlow, movePickerAccountId) { accounts, id -> showOnlySubscribedFor(id, accounts) },
    ) { folders, current, onlySubscribed -> moveTargets(folders, current, onlySubscribed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The OPEN MESSAGE's account's folders, WHOLE — scoped by [_ownerAccountId] and by nothing
     * else. What the SENDER RULE is computed against: [blockSender] names the account's Trash in a
     * server-side `fileinto` ([app.sterna.ui.sender.trashFilePath]), and the participants panel
     * decides its entry on the same list (`senderRuleEntry`).
     *
     * ⛔ It must NEVER follow [movePickerAccountId] again (#189, and it did for one commit): the
     * rule [blockSender] writes belongs to A, and a Trash path computed on B's folder list would
     * file A's future mail into a path A does not have — or, worse, into a homonym (#92). Which is
     * exactly what [app.sterna.ui.inbox.mailboxFilePath] exists to prevent.
     *
     * ⛔ Not the picker's list either: that one is [pickerMailboxes] and it follows the account
     * row. Two consumers, two accounts, two flows.
     */
    val accountMailboxes: StateFlow<List<Mailbox>> = _ownerAccountId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repo.observeMailboxes(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The LISTED account's folders, WHOLE — the context the picker resolves a folder's parent
     * path against (#109): the open message's account, or the one chosen on the picker's account
     * row ([movePickerAccountId], #189). [moveTargets] cannot serve: it drops the folder the
     * message is filed under, which is often the very parent that names its siblings.
     *
     * ⛔ The picker's, and only the picker's — see [accountMailboxes] for why the sender rule
     * cannot read this one.
     */
    val pickerMailboxes: StateFlow<List<Mailbox>> = movePickerAccountId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repo.observeMailboxes(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The account's filter rules as last read — [SenderScript.Unread] until a read is made, and
     * [SenderScript.Unreachable] when one was made and did not answer. See [senderRuleEntry] for
     * what each of the three means to the entry; the first two are NOT the same answer.
     *
     * Read ONLY when the participants panel is opened ([loadSenderRules]), never on opening a
     * message: it is a network round-trip, and paying for one on every message read — including
     * every message merely swiped past — to decide the state of an entry nobody has looked for
     * yet is not a trade this app makes.
     */
    private val _senderRules = MutableStateFlow<SenderScript>(SenderScript.Unread)
    val senderRules = _senderRules.asStateFlow()

    /**
     * Every address that IS the user on the open message's account, so the rule gesture can
     * refuse its own sender — see [app.sterna.core.data.filter.blockableSender].
     *
     * Per message and not per app: the reader is a pager and the unified inbox makes it cross
     * ACCOUNTS, so the addresses that are "oneself" change under it exactly as the script does.
     * Read from the store, so it costs nothing and needs no network.
     */
    private val _accountAddresses = MutableStateFlow<List<String>>(emptyList())
    val accountAddresses = _accountAddresses.asStateFlow()

    /**
     * The addresses the user has DECLARED as her own on this account — her send-as identities, and
     * nothing else. Read by the header's arrival line ([app.sterna.ui.message.deliveredToInHeader]).
     *
     * ⛔ Deliberately NOT [accountAddresses], and the difference is the whole point: that list folds
     * in the LOGIN, which is an authentication identifier and not an address one is reachable at.
     * Shared hosting authenticates as `sc12345@mail.hebergeur.tld` and receives at
     * `moi@mondomaine.tld` — the `.k9s` import produces exactly that pair, `username` from
     * `<incoming-server>` and the address from `<identity><email>`, two independent fields — so a
     * count that included the login would read every such SINGLE-address account as multi-alias.
     *
     * ⛔ And [accountAddresses] KEEPS its login, it is not to be "fixed" to match: `senderRuleEntry`
     * asks "is this sender me?", where the login must count. Two lists because there are two
     * questions — "which addresses am I reachable at" is not "which strings are me".
     *
     * Refilled per message like the other, and for the same reason: the pager crosses accounts.
     */
    private val _identityAddresses = MutableStateFlow<List<String>>(emptyList())
    val identityAddresses = _identityAddresses.asStateFlow()

    /** One-shot word about the rule gesture (added / already there / failed), shown and cleared. */
    private val _senderRuleStatus = MutableStateFlow<String?>(null)
    val senderRuleStatus = _senderRuleStatus.asStateFlow()

    fun clearSenderRuleStatus() { _senderRuleStatus.value = null }

    /**
     * One-shot word about an action taken ON the open message (report spam / not spam), shown and
     * cleared — the channel a failure takes when the message itself is still there to read.
     *
     * ⭐ Its own flow, and not [_senderRuleStatus] which is toasted exactly the same way: that one
     * is the answer to a gesture made in the participants panel, both are one slot deep, and
     * sharing it means a refused "report spam" silently eating a "Rule added" the user is waiting
     * for (or the reverse). [_attachmentStatus] is no better: it is rendered INSIDE the attachment
     * section, so on a message with no attachment it is drawn nowhere at all.
     */
    private val _actionStatus = MutableStateFlow<String?>(null)
    val actionStatus = _actionStatus.asStateFlow()

    fun clearActionStatus() { _actionStatus.value = null }

    /**
     * Read the account's script, once, when the participants panel opens.
     *
     * Idempotent while it holds an ANSWER — [SenderScript.Read] — so reopening the panel does not
     * go back to the server. A read that failed leaves [SenderScript.Unreachable], which is not
     * an answer: the next opening tries again, and until then the entry is offered, which is what
     * a merely unreachable server deserves. [SenderScript.Unread], the state before any of this,
     * is the one that holds the entry back.
     */
    fun loadSenderRules() {
        if (_senderRules.value is SenderScript.Read) return
        val credentials = credentials() ?: return
        viewModelScope.launch {
            _senderRules.value = readSenderScript(credentials)
        }
    }

    /**
     * One read of the account's script, as the three-state answer the entry is decided on.
     *
     * `getOrElseUnlessCancelled` and not `getOrDefault` (issue #99): a cancelled read is not a
     * failed one. The reader is a pager, so this coroutine dies every time a page is swiped away
     * — and swallowing that as "unreachable" would answer for a message nobody is looking at,
     * including on the re-read that follows a rule just written.
     */
    private suspend fun readSenderScript(credentials: AccountCredentials): SenderScript =
        runCatching { SenderScript.Read(repo.loadFilterRules(credentials)) }
            .getOrElseUnlessCancelled { SenderScript.Unreachable }

    /**
     * Add the "future mail to Trash, marked read" rule for [address] to the account's script.
     *
     * The whole gesture is [addBlockRule] — read the rules, then save them WITH one more —
     * handed the read and the write, because `saveFilterRules` rewrites the ENTIRE script: a
     * save that did not start from a successful read would delete the account's filters instead
     * of adding one. It is also where the refusal on a foreign active script lives, and that
     * guard takes no argument that could switch it off.
     */
    fun blockSender(address: String) {
        val credentials = credentials() ?: return
        viewModelScope.launch {
            val trashPath = trashFilePath(accountMailboxes.value)
            if (trashPath == null) {
                _senderRuleStatus.value =
                    getApplication<Application>().getString(R.string.status_action_failed)
                return@launch
            }
            val outcome = addBlockRule(
                address = address,
                trashFolder = trashPath,
                load = { repo.loadFilterRules(credentials) },
                save = { rules -> repo.saveFilterRules(credentials, rules) },
            )
            _senderRuleStatus.value = getApplication<Application>().getString(
                when (outcome) {
                    BlockOutcome.ADDED -> R.string.sender_volume_block_added
                    BlockOutcome.ALREADY_PRESENT -> R.string.sender_volume_block_done
                    BlockOutcome.FAILED -> R.string.status_action_failed
                },
            )
            // Re-read, so the entry greys itself out for the address just handled instead of
            // going on offering a rule that is now there.
            _senderRules.value = readSenderScript(credentials)
        }
    }

    /** OpenPGP state of the opened message (status card + header badges). */
    private val _crypto = MutableStateFlow<CryptoUiState>(CryptoUiState.None)
    val crypto = _crypto.asStateFlow()

    /** Whether the bottom Reply/Forward bar should show for this page. Written by the page's
     *  body (the scroll-end reveal logic in ConversationBody), read by the pager-level chrome:
     *  the visible bar is FIXED outside the pager (#62) and follows the SETTLED page's value,
     *  so it never blinks during the swipe gesture itself. */
    private val _replyBarVisible = MutableStateFlow(false)
    val replyBarVisible = _replyBarVisible.asStateFlow()

    fun setReplyBarVisible(visible: Boolean) {
        _replyBarVisible.value = visible
    }

    /** One-time "show remote images" override for the open message. Lives here rather than in a
     *  composable because its writer and reader are now split across the pager boundary (#62):
     *  the fixed toolbar's menu sets it, the page's body reads it. Reset on load. */
    private val _manualShowImages = MutableStateFlow(false)
    val manualShowImages = _manualShowImages.asStateFlow()

    fun showImagesOnce() {
        _manualShowImages.value = true
    }

    /** One-shot "print this message" signal, same shape as the image override above and for the
     *  same reason: the overflow menu that raises it is fixed chrome at the pager level, the page
     *  that builds the document and hands it to the print service is one level down. The page
     *  consumes it on the spot ([printConsumed]); reset on load so a request never crosses over
     *  to the next message. */
    private val _printRequested = MutableStateFlow(false)
    val printRequested = _printRequested.asStateFlow()

    fun print() {
        _printRequested.value = true
    }

    fun printConsumed() {
        _printRequested.value = false
    }

    /** This message's DEVIATION from the reading-mode setting (#149), set from the overflow menu:
     *  null = nobody chose, follow the setting; true/false = read THIS message as text, or as its
     *  HTML, whatever the setting says. Not an `or` — with the setting on, the menu offers "Show
     *  HTML" and has to deliver it. Lives here for the same reason as the image override: the menu
     *  is at the pager level and the body it changes is at the page level. TRANSITORY on purpose —
     *  reset to null on load, nothing is stored, and leaving the message returns to the setting. It
     *  is pure UI over the body already in hand: it must never re-open or re-decrypt the message, or
     *  every toggle would replay the OpenKeychain round-trip. Resolved by [plainTextForBody]. */
    private val _plainText = MutableStateFlow<Boolean?>(null)
    val plainText = _plainText.asStateFlow()

    fun setPlainText(on: Boolean) {
        _plainText.value = on
    }

    /**
     * The stored reading-mode setting (#149) — and null while DataStore has not answered, which is a
     * state the reader has to be able to SEE. `MessagePage` builds one of these per page of the
     * pager, so that window reopens on every message swiped through, not once at launch.
     *
     * The initial value is therefore null and NOT PLAIN_TEXT_DEFAULT: seeding the default here would
     * answer the remote-image question as "HTML" before the answer is known, which is the one side
     * that cannot be taken back. The two readers of this null differ on purpose — [plainTextForBody]
     * renders the default, [plainTextForImages] cuts the images — and neither is reachable if the
     * null is resolved here.
     */
    val plainTextSetting: StateFlow<Boolean?> = settings.plainText.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    /** The raw-headers viewer (issue #60): null while closed, else its load/loaded/error state.
     *  Headers are pulled on demand ([viewHeaders]) so the normal reader path never fetches them. */
    private val _headers = MutableStateFlow<HeadersState?>(null)
    val headers = _headers.asStateFlow()

    /** Open the raw-headers viewer and fetch this message's headers (cheap over JMAP). */
    fun viewHeaders() {
        val id = loadedId ?: return
        _headers.value = HeadersState.Loading
        viewModelScope.launch {
            try {
                val credentials = credentials()
                    ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                _headers.value = HeadersState.Loaded(repo.rawHeaders(credentials, id))
            } catch (t: Throwable) {
                _headers.value = HeadersState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /** Close the raw-headers viewer. */
    fun dismissHeaders() {
        _headers.value = null
    }

    /**
     * "Save as .eml": write the message exactly as the server holds it — [MailRepository.rawSource]'s
     * bytes, one per octet — into the document the user picked at [uri]. ⛔ The server's octets,
     * never the clear text of an encrypted message: an OpenPGP message is saved as received, still
     * encrypted, because decrypted content is never written to disk, save for the exceptions the
     * README names — an attachment she opens, one she saves, and printing or saving a message as
     * PDF, which hands the decrypted text to the system print service. A .eml export is none of
     * them. The toast names the document's REAL name (she may have renamed it in the picker), else
     * [proposedName]. A failure lands on [actionStatus] like every other gesture on the open
     * message, never on [MessageState.Error]: the message stays readable.
     *
     * [ownerId] is the message the picker was launched FOR. The result comes back to whatever page
     * is settled by then — an activity recreated behind the picker can restore onto another one —
     * and message B's bytes under A's file name would be a lie in her Downloads, so a mismatch is
     * refused like [unsubscribe]'s and the snooze's owner guards.
     *
     * ⛔ `CreateDocument` has the provider CREATE the document before this runs: on any failure,
     * and on the refusal above, the empty (or half-written) file is deleted again, or she would
     * later find `Subject.eml` in Downloads and believe the message saved. The toast alone would not
     * undo that.
     */
    fun exportSource(uri: Uri, proposedName: String, ownerId: String) {
        viewModelScope.launch {
            try {
                val id = loadedId
                if (id == null || id != ownerId) {
                    discardDocument(uri)
                    _actionStatus.value = getApplication<Application>().getString(R.string.status_action_failed)
                    return@launch
                }
                val credentials = credentials()
                    ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                val bytes = repo.rawSource(credentials, id)
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        ?: error("no output stream")
                }
                val name = displayNameOf(uri, proposedName)
                _actionStatus.value = getApplication<Application>().getString(R.string.message_export_saved, name)
            } catch (t: Throwable) {
                discardDocument(uri)
                // The unavailable message gets the sentence the reader already shows for that case;
                // everything else keeps its own text, like "View headers".
                _actionStatus.value = readFailureStringRes(t)?.let { getApplication<Application>().getString(it) }
                    ?: readerActionFailureText(t) { res -> getApplication<Application>().getString(res) }
            }
        }
    }

    /**
     * "Save" on an attachment row: the part's bytes, straight from the server into the document she
     * picked at [uri]. Same shape as [exportSource], and the same toast — "Saved as …", naming the
     * document's REAL name (she may have renamed it in the picker), else [proposedName]. A failure
     * lands on [actionStatus] like every other gesture on the open message: the message stays
     * readable.
     *
     * ⛔ Never through `StorageRepository.cacheAttachment`, which is the OPEN path's business. The
     * bytes go from the server into the chosen document and nowhere else: a saved attachment must
     * not also exist as a second copy inside the app, sitting in the cache until something clears
     * it — and nothing about saving needs a file the app can open.
     *
     * [ownerId] is the message the picker was launched FOR, [partKey] the part it was launched for,
     * `partId ?: blobId` — the same key the attachment row is drawn from. BOTH are re-resolved from
     * [messages] here, and ⚠ NEITHER is [loadedId]: the reader is a CONVERSATION, and the row hands
     * over `msg.id`, which may be another message of the thread than the settled page. The picker's
     * result comes back to whatever page is settled by then — an activity recreated behind the
     * picker can restore onto ANOTHER page, or onto the same message with ANOTHER attachment — and
     * writing part B's bytes under part A's name in her Downloads would be a lie about what she has
     * on disk. So anything that does not resolve is refused. An empty key is refused for the same
     * reason: no row can be drawn from a part with neither id, so an empty one means the saved state
     * was lost, and refusing is the safe side of that.
     *
     * ⛔ [openingAttachment] is neither taken nor released: it holds back a second tap on OPEN, and
     * coupling the two would make a save wait behind a download it has nothing to do with.
     *
     * ⛔ `CreateDocument` has the provider CREATE the document before this runs, so every failure —
     * and the refusal above — deletes it again, or she later finds an empty `rapport.pdf` in
     * Downloads and believes the attachment saved.
     */
    fun saveAttachment(uri: Uri, proposedName: String, ownerId: String, partKey: String) {
        viewModelScope.launch {
            try {
                val part = _messages.value.firstOrNull { it.id == ownerId }
                    ?.body?.fileAttachmentParts()
                    ?.firstOrNull { (it.partId ?: it.blobId) == partKey }
                if (ownerId.isEmpty() || partKey.isEmpty() || part == null) {
                    discardDocument(uri)
                    _actionStatus.value = getApplication<Application>().getString(R.string.status_action_failed)
                    return@launch
                }
                val credentials = credentials()
                    ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                val bytes = repo.downloadAttachment(credentials, part, ownerId)
                requireAttachmentBytes(ownerId, partKey, bytes)
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        ?: error("no output stream")
                }
                val name = displayNameOf(uri, proposedName)
                _actionStatus.value = getApplication<Application>().getString(R.string.message_export_saved, name)
            } catch (cancelled: CancellationException) {
                // ⛔ Cancellation is NOT a failure to report, but the document still has to go: each
                // pager page owns its ViewModel, so swiping two messages away mid-download cancels
                // this scope, and caught as an ordinary throwable below it would leave the empty
                // `rapport.pdf` the picker created sitting in her folder — with no toast to even
                // hint at it, since this page is gone. [discardDocument] runs NonCancellable for
                // exactly this moment. Rethrown, never swallowed: the scope stays honest.
                discardDocument(uri)
                throw cancelled
            } catch (t: ContentTooLargeException) {
                // Our own refusal, not a failure — the same sentence the open path gives it, which
                // names no action precisely so both paths can wear it.
                discardDocument(uri)
                _actionStatus.value = getApplication<Application>().getString(R.string.status_attachment_too_large)
            } catch (t: Throwable) {
                discardDocument(uri)
                // ⛔ [readFailureStringRes] FIRST, exactly as exportSource's own catch does. The
                // sentence below interpolates `t.message` raw, and the message an unavailable part
                // carries names the part key and the message id in English (#159, the doctrine at
                // the top of this file): on a French screen that reads "Impossible d'enregistrer la
                // pièce jointe : Attachment 2.1 of M8fa4… came back empty". Mapped, she gets
                // status_message_unavailable, already translated into the nine languages.
                // Everything else keeps its own text.
                _actionStatus.value = readFailureStringRes(t)?.let { getApplication<Application>().getString(it) }
                    ?: getApplication<Application>()
                        .getString(R.string.status_save_attachment_failed, t.message ?: "error")
            }
        }
    }

    /** Delete the document the picker created at [uri] — nothing was, or could be, written into it.
     *  A provider that cannot delete costs nothing more: the failure toast is already on its way.
     *  ⛔ [NonCancellable], because the one moment this matters most is a cancelled scope: the page
     *  swiped away mid-write is precisely when nobody is left to see a toast, and a plain
     *  `withContext` would throw here instead of deleting, leaving the empty file behind for good. */
    private suspend fun discardDocument(uri: Uri) = withContext(NonCancellable + Dispatchers.IO) {
        runCatching { DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, uri) }
    }

    /** The document's name as its provider reports it (she may have renamed it in the picker),
     *  else [fallback]. A provider that answers nothing, or throws, costs the name and nothing more. */
    private suspend fun displayNameOf(uri: Uri, fallback: String): String = withContext(Dispatchers.IO) {
        runCatching {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback
    }

    /** The attached-message sheet: null while closed, else its load/loaded/error state. Opened by
     *  [openAttachment] when the part is a `message/rfc822`; nothing on that path touches the disk. */
    private val _attachedMessage = MutableStateFlow<AttachedMessageState?>(null)
    val attachedMessage = _attachedMessage.asStateFlow()

    /** The download behind the sheet, so that closing it can stop it: a `Loaded` landing after a
     *  dismiss — or after the page moved on to another message — would reopen the sheet on its own,
     *  with the previous message's attachment on top of the new one. */
    private var attachedMessageJob: Job? = null

    /** Close the attached-message sheet, and stop the download behind it. */
    fun dismissAttachedMessage() {
        attachedMessageJob?.cancel()
        attachedMessageJob = null
        _attachedMessage.value = null
    }

    // ---- unsubscribe (RFC 2369 / RFC 8058) ----

    /** What the open message offers as a way out of its mailing list; null = no banner, no menu
     *  entry, and no error either — a message that offers nothing is simply mail. */
    private val _unsubscribe = MutableStateFlow<UnsubscribeOptions?>(null)
    val unsubscribe = _unsubscribe.asStateFlow()

    private val _unsubscribeState = MutableStateFlow<UnsubscribeState>(UnsubscribeState.Idle)
    val unsubscribeState = _unsubscribeState.asStateFlow()

    /** The gesture awaiting confirmation, or null when no dialog is up. Confirmation is
     *  systematic and has no setting (decision D7): the dialog is where the app says what is
     *  about to leave and to whom, which is the whole of WYSIWYG applied to a network call. */
    private val _unsubscribeConfirm = MutableStateFlow<PendingUnsubscribe?>(null)
    val unsubscribeConfirm = _unsubscribeConfirm.asStateFlow()

    /** Ask before acting; a no-op when the message offers nothing, or when it is already done. */
    fun askUnsubscribe() {
        val options = _unsubscribe.value ?: return
        val action = offeredUnsubscribeAction(options, _unsubscribeState.value) ?: return
        // The options are CAPTURED here, not read again when the button is pressed: the dialog
        // then names, and the action then uses, one and the same target. A reload behind the open
        // dialog (the pager settling, a refresh) would otherwise leave the two disagreeing — or
        // leave the dialog naming nothing at all.
        _unsubscribeConfirm.value = PendingUnsubscribe(action, options)
    }

    fun dismissUnsubscribeConfirm() {
        _unsubscribeConfirm.value = null
    }

    /**
     * Carry out the confirmed unsubscribe: the one-click POST, or the mail through the outbox.
     * [UnsubscribeAction.OPEN_PAGE] is not handled here — handing a URL to a browser is the
     * screen's job, and it deliberately looks nothing like the other two.
     *
     * It acts on the target the confirmation NAMED (see [askUnsubscribe]), and only while
     * [offeredUnsubscribeAction] still offers it — so a dialog left open across a completed
     * unsubscribe cannot send a second request to a list that already answered.
     *
     * The result is applied only if this ViewModel still holds the SAME message of the SAME
     * account. The reader is a pager: a swipe while the POST is in flight would otherwise land
     * "Unsubscribed" on the neighbouring message (ids are per account — issue #31).
     */
    fun unsubscribe() {
        val pending = _unsubscribeConfirm.value ?: return
        if (offeredUnsubscribeAction(pending.options, _unsubscribeState.value) == null) return
        _unsubscribeConfirm.value = null
        val ownerId = loadedId ?: return
        val ownerAccount = accountId
        val app = getApplication<Application>()
        _unsubscribeState.value = UnsubscribeState.Sending
        viewModelScope.launch {
            val outcome: UnsubscribeState = try {
                val credentials = credentials() ?: error(app.getString(R.string.status_no_saved_account))
                when (pending.action) {
                    UnsubscribeAction.ONE_CLICK -> {
                        val url = pending.options.oneClickUrl ?: error("no one-click url")
                        // The connectivity read is handed over as a lambda, so it is answered when
                        // the POST fails and not now: this is the app's one connectivity check
                        // ([hasUsableNetwork], the same one that picks "Sending…" over "queued" at
                        // send time, #70), and `core:data` must not reach for Android to get it.
                        // Without it a dead unsubscribe endpoint — an expired domain, a shut-down
                        // list — is reported as "No network" to a phone that is plainly online.
                        when (val result = repo.unsubscribeOneClick(url) { hasUsableNetwork(app) }) {
                            is UnsubscribeResult.Sent -> UnsubscribeState.Sent
                            is UnsubscribeResult.Failed -> UnsubscribeState.Failed(result.reason)
                        }
                    }
                    UnsubscribeAction.MAIL -> {
                        val mailto = pending.options.mailto ?: error("no mailto")
                        repo.sendUnsubscribeMail(credentials, mailto)
                        UnsubscribeState.Queued
                    }
                    // Not ours to run; leaving the state untouched keeps the banner as it was.
                    UnsubscribeAction.OPEN_PAGE -> UnsubscribeState.Idle
                }
            } catch (cancelled: CancellationException) {
                // A cancellation is not a failure to report: the page it belonged to is gone.
                throw cancelled
            } catch (t: Throwable) {
                // Said out loud, because the screen cannot say it: "Unsubscribe failed" is one
                // sentence for a refused enqueueSend, a missing identity and a broken database
                // alike, and without this line the reason left no trace anywhere at all.
                android.util.Log.w("SternaUnsubscribe", "unsubscribe failed: ${pending.action}", t)
                UnsubscribeState.Failed(UnsubscribeFailure.REFUSED)
            }
            if (loadedId == ownerId && accountId == ownerAccount) {
                _unsubscribeState.value = outcome
            }
        }
    }

    // ---- read receipt (RFC 8098) ----

    /**
     * The question standing on the open message, or null when there is none to ask — which is the
     * answer for very nearly all mail.
     *
     * ⛔ RAISED in exactly one place — [maybeOfferReadReceipt], through [offeredReadReceipt], off a
     * marking only [maybeMarkRead] can write; see those for why the other eight ways a message
     * becomes read cannot reach it. Everything else only ever CLEARS it: a new message ([load]), a
     * refusal, an acceptance, and the switch being turned off. It carries what would be answered,
     * captured at the moment the question is put, so that what the reader is shown and what would
     * leave cannot come apart behind a reload.
     */
    private val _readReceiptOffer = MutableStateFlow<ReadReceiptRequest?>(null)
    val readReceiptOffer = _readReceiptOffer.asStateFlow()

    /** Where a receipt this reader accepted has got to; [ReadReceiptState.Idle] until they say so. */
    private val _readReceiptState = MutableStateFlow<ReadReceiptState>(ReadReceiptState.Idle)
    val readReceiptState = _readReceiptState.asStateFlow()

    /**
     * Whether the reader has had their say on THIS message's question — see [ReadReceiptAnswer].
     *
     * ⛔ Not derivable from [_readReceiptOffer] being null, and that is the whole reason it exists:
     * a refusal leaves exactly the same null as a message that never asked, and the decision is
     * replayed below on every change of the setting. Told apart by nothing, switching off and on
     * again would put back the question the reader had just declined.
     */
    private var readReceiptAnswer = ReadReceiptAnswer.PENDING

    init {
        // ⭐ BOTH directions, through ONE decision replayed. Turning the switch off takes a standing
        // question away; turning it ON with a message already open puts it. And it closes the
        // cold-start race in passing: when the settle AND the body both land before DataStore's
        // first emission, this is what asks the question that message would otherwise never raise.
        // ⛔ It is not an amnesty: `readReceiptAnswer` goes into the decision, so a question already
        // refused, or already answered, does not come back.
        viewModelScope.launch {
            readReceiptSetting.collect { maybeOfferReadReceipt() }
        }
    }

    /**
     * The reader said yes: queue the receipt through the outbox and report what was queued.
     *
     * The offer is taken and cleared FIRST, so a second tap has nothing to send; and the send uses
     * the request the banner was showing, never a fresh read of the message on screen — the pager
     * can settle on another one while this is in flight.
     *
     * ⭐ `deliveredTo` is handed over, not left out: it is the address the message ARRIVED at, and
     * `MailRepository.sendReadReceipt` takes it as a parameter so an account with several aliases
     * does not answer under a name the sender never wrote to.
     *
     * ⭐ What comes back is the preview of the row that was queued, and it is RELAYED into the
     * state, never rebuilt here: the banner must name the very text that left.
     *
     * ⭐ The DECISION is run again here, and not merely "an offer exists". What leaves this device
     * cannot be taken back, so the switch is read at the moment of the tap: a banner that survived
     * a frame after the reader turned the setting off must not be able to answer a stranger. Same
     * belt as the unsubscribe, which re-checks `offeredUnsubscribeAction` before acting.
     */
    fun sendReadReceipt() {
        val pending = offeredReadReceipt(
            setting = readReceiptSetting.value,
            marking = ReadMarking.READER_SETTLE_UNREAD,
            request = _readReceiptOffer.value,
            answered = readReceiptAnswer.answered,
        ) ?: return
        readReceiptAnswer = ReadReceiptAnswer.ACCEPTED
        _readReceiptOffer.value = null
        val ownerId = loadedId ?: return
        val ownerAccount = accountId
        val app = getApplication<Application>()
        _readReceiptState.value = ReadReceiptState.Sending
        viewModelScope.launch {
            val outcome: ReadReceiptState = try {
                val credentials = credentials() ?: error(app.getString(R.string.status_no_saved_account))
                readReceiptSendOutcome(
                    repo.sendReadReceipt(
                        credentials = credentials,
                        receiptTo = pending.receiptTo,
                        originalSubject = pending.originalSubject,
                        originalMessageId = pending.originalMessageId,
                        deliveredTo = pending.deliveredTo,
                    ),
                )
            } catch (cancelled: CancellationException) {
                // The page it belonged to is gone; not a failure to report.
                throw cancelled
            } catch (t: Throwable) {
                // Said out loud for the same reason the unsubscribe does it: the banner has one
                // sentence for a refused enqueue, a missing identity and a broken database alike.
                android.util.Log.w("SternaReadReceipt", "read receipt failed", t)
                ReadReceiptState.Failed
            }
            // The reader is a pager: a swipe while the enqueue is in flight would otherwise land
            // this answer on the neighbouring message (ids are per account — issue #31).
            if (loadedId == ownerId && accountId == ownerAccount) {
                _readReceiptState.value = outcome
                // ⭐ A FAILURE IS A RETRY, NOT A DEAD END — the posture the unsubscribe strip
                // already takes ("a refusal or a dead network is a retry"). Nothing was queued, so
                // the reader's yes bought her nothing; leaving her ACCEPTED would freeze a red
                // sentence on the message until she left it. ⛔ A REFUSAL is not a failure: this
                // runs only after she said yes, and `declineReadReceipt` is untouched by it.
                // Put back through the DECISION rather than by restoring the captured request:
                // the setting may have moved while the enqueue was in flight, and there must not
                // be a second way to raise a question.
                if (outcome == ReadReceiptState.Failed) {
                    readReceiptAnswer = ReadReceiptAnswer.PENDING
                    maybeOfferReadReceipt()
                }
            }
        }
    }

    /**
     * The reader said no: the question goes, and ⛔ nothing leaves.
     *
     * The refusal is remembered for THIS PAGE only, and only so that replaying the decision (the
     * setting changing, the body landing) cannot put the question back. ⛔ Nothing is written
     * anywhere: leave the message and come back and there is no memory of it, which is the trade
     * this work made to avoid a column, a migration and a resync.
     */
    fun declineReadReceipt() {
        readReceiptAnswer = ReadReceiptAnswer.DECLINED
        _readReceiptOffer.value = null
    }

    /** One automatic decrypt attempt per opened message (when the page settles). */
    private var autoDecryptTried = false

    /** The detected crypto kind, kept so a cancelled unlock returns to Locked. */
    private var lockedKind: CryptoKind? = null

    private var loadedId: String? = null
    /** Owning account when opened from the unified inbox; null = current account. */
    private var accountId: String? = null

    /**
     * The (account, id) whose cached body [warmFromCache] has already painted on this page —
     * ⛔ the PAIR, never the id alone: the unified inbox walks the pager across accounts, and two
     * accounts of one server hand out the same message id (#92).
     *
     * ⛔ Deliberately NOT [loadedId]. That one is what `MessagePaging.needsLoad` reads to answer
     * "already loaded?", and a warm-up writing it would mark a page loaded that was never
     * fetched: a neighbour absent from the cache would then return early from the settle and sit
     * blank forever, with no spinner and no error. These two fields are read by exactly one
     * thing, the `warm` flag below, and they say only "there is a body on screen already".
     *
     * Instance fields, so they die with the page's disposable ViewModelStore (MessageScreen).
     */
    private var warmedId: String? = null
    private var warmedAccountId: String? = null

    // Mark-as-read is deferred to "settle": the message pager composes neighbouring pages
    // while swiping, so reading on load() would mark messages read just by flicking past
    // them. [active] tracks whether this page is the one settled in front of the user;
    // the anchor is read once (guarded by [anchorMarked]) when it is both active and loaded.
    private var active = false
    private var anchorMarked = false

    /**
     * How this page's message came to be marked read, once the pager settled on it — null until
     * then, and null again for every new message ([load]).
     *
     * ⛔ Written in [maybeMarkRead] and nowhere else. It is the gate [maybeOfferReadReceipt] opens
     * on, so a `setRead` coming from anywhere but this page's settle has no way to reach the
     * read-receipt decision at all: it would have to write this field, and nothing outside this
     * class can see it.
     */
    private var settleMarking: ReadMarking? = null

    /**
     * The subject of the ENVELOPE — the cover — of the message this page holds, captured before
     * anything is decrypted and never rewritten afterwards.
     *
     * It exists because the read receipt is the one thing this reader can SEND, and it used to
     * take its subject off the screen. Since #128 the screen shows the PROTECTED subject once a
     * PGP/MIME message is decrypted (RFC 9788), so reading the screen would put the real subject
     * of an encrypted message, in clear, into a mail addressed to whatever the sender wrote in
     * `Disposition-Notification-To` — and into a copy kept in Sent.
     *
     * ⛔ Written only from the cached row and from the fetched original, i.e. only before any
     * decrypt, and never from `_state`: `maybeOfferReadReceipt` is deliberately a function of the
     * current state so it can be replayed, and the state is exactly what stops being the cover.
     * ⛔ Never erased by a null (same care as `_deliveredTo`), and null stays null: a receipt with
     * no subject is what `readReceiptPreview` already renders, and there is nothing else to use.
     */
    private var coverSubject: String? = null

    /** Credentials for the message's own account (unified inbox), else the current one. */
    private fun credentials(): AccountCredentials? =
        accountId?.let { store.credentials(it) } ?: store.load()

    /**
     * Paint this page's body from the LOCAL CACHE, before the finger reaches it — and from
     * nothing else.
     *
     * The defect it answers: a page whose state carries no body composes no `ConversationBody`,
     * hence no `WebView`, so creating the view, `loadDataWithBaseURL`, `onPageFinished` and the
     * height poll (30 × 32 ms) all start AFTER the swipe settles. That is the ~1 s hourglass at
     * the end of every swipe. The pager already composes one page each side of the one being
     * read, so the body can be on screen before the gesture ends — provided it costs nothing.
     *
     * ⛔ AND IT COSTS NOTHING: `repo.cachedMessage` and no other repository call. The warm-up
     * `798a5fd1` removed went through `openMessage`, which on IMAP fetches the whole source and
     * hands the sender's announced key to the OpenPGP provider from a message nobody opened,
     * irreversibly — ENCRYPTION.md:177-180 and SECURITY.md promise that never happens, and this
     * function keeps that promise by never reaching the network at all. A cache miss is simply
     * nothing done: no state, no trace, and the settle then loads as it does today.
     *
     * ⛔ It marks nothing read, forwards no settle, asks no read-receipt question. A warmed
     * neighbour is a message nobody has looked at, and it stays unread.
     *
     * ⛔ It never writes [loadedId] — see the fields above for what that would cost.
     */
    fun warmFromCache(emailId: String, accountId: String?) {
        if (loadedId != null) return
        // The page's OWN account, so `credentials()` answers for this message and not for
        // whatever account happens to be current (#92). `load` sets the same value.
        this.accountId = accountId
        viewModelScope.launch {
            val credentials = credentials() ?: return@launch
            val cached = runCatching { repo.cachedMessage(credentials.id, emailId) }.getOrNull() ?: return@launch
            val warmable = MessagePaging.warmable(
                cryptoKind = repo.cryptoKindOf(cached.email),
                inlineParts = cached.email.inlineImageParts().size,
                cachedInlineImages = cached.inlineImages.size,
                // ⛔ Read and compared exactly as MessageScreen does it (:1581-1582), lower-cased
                // against a list DataStore already stores lower-cased: a warmed body from an
                // allowed sender composes a WebView whose remote images are not blocked, and the
                // pixel leaves for a message nobody opened (PRIVACY.md:288).
                // ⛔ AND IT IS THE DATASTORE FLOW, AWAITED — never the `imageAllowlist` StateFlow
                // above. That one is `WhileSubscribed(5_000)` over `initialValue = emptySet()`, so
                // `.value` answers "nobody is allowed" both before DataStore has replied AND
                // whenever nothing is collecting it — and this warm-up runs at composition, which
                // is exactly that moment. The page would be warmed on an empty list, the real list
                // would arrive a frame later, `senderAllowed` would flip to true under a body
                // already on screen, and the pixel would leave after all. `first()` suspends until
                // the stored answer exists; if it never does, the warm-up simply does not happen
                // and the reader gets today's spinner, which is the safe way to be wrong.
                senderAllowedRemoteImages =
                    cached.email.from.firstOrNull()?.email?.lowercase()?.let { it in settings.imageAllowlist.first() } == true,
            )
            if (!warmable) return@launch
            // The settle may have overtaken the cache read: `load` owns the page from that moment
            // on, and a warm body painted over a fetch in flight would be a second render.
            if (loadedId != null) return@launch
            _state.value = MessageState.Loaded(cached.email)
            _messages.value = listOf(
                ThreadMessage(
                    id = cached.email.id,
                    header = cached.email,
                    body = cached.email,
                    inlineImages = cached.inlineImages,
                ),
            )
            warmedId = emailId
            warmedAccountId = accountId
        }
    }

    /** Loads the conversation once per (id, account) pair (idempotent across recompositions),
     *  and ONLY for the page the pager has settled on. Does NOT mark read — [onActiveChanged]
     *  does that when the pager settles on this page.
     *
     *  ⛔ [settled] carries no default, deliberately: the pager warms one page each side of the
     *  one being read, so a load that forgets to ask fetches two messages nobody touched — and on
     *  IMAP each fetched source imports its sender's announced key into OpenKeychain, which
     *  nothing can undo. Both call sites must therefore say out loud which they are: the pager's
     *  effect passes its page's `active`, the Retry button passes `true` because a tap is a
     *  gesture. The whole decision lives in [MessagePaging.needsLoad], error state included — as
     *  a separate `&& _state.value !is MessageState.Error` here it was an OR in disguise, and an
     *  unsettled page showing an error loaded anyway.
     *
     *  The account is part of the guard, not just remembered: JMAP ids are per account, so the
     *  same id under another account is another message. Comparing the id alone made reopening
     *  it return immediately and leave the previous account's message on screen (#92) — even
     *  though [MessageScreen]'s LaunchedEffect had correctly asked again. */
    fun load(emailId: String, accountId: String?, settled: Boolean) {
        val failed = _state.value is MessageState.Error
        if (!MessagePaging.needsLoad(settled, loadedId, this.accountId, emailId, accountId, failed)) {
            return
        }
        loadedId = emailId
        this.accountId = accountId
        // ⭐ Is there already a body on this page, put there by [warmFromCache] for THIS account
        // and THIS id? If so the two lines that would erase it are skipped — and they are the
        // whole volet: erased, the WebView leaves composition at the instant the finger lands and
        // the reader watches it be rebuilt, which is the ~1 s hourglass. ⛔ Every other reset in
        // this prologue stays unconditional: they guard against the PREVIOUS message's state.
        val warm = warmedId == emailId && warmedAccountId == accountId
        // FIRST, before anything else and before the coroutine below: the unsubscribe of the
        // message we are leaving. Everything under it is a label that would merely look wrong on
        // the new message; this one is a BUTTON, and until the fetch returns it would still be
        // wired to the previous message's list — a tap unsubscribing from something the reader is
        // no longer looking at, with a confirmation naming a sender they did not choose.
        _unsubscribe.value = null
        _unsubscribeState.value = UnsubscribeState.Idle
        _unsubscribeConfirm.value = null
        // And the read receipt, for the same reason and one degree worse: its button answers a
        // NAMED stranger, captured from the message we are leaving. Left standing, a tap would
        // tell the previous sender that a message the reader is no longer looking at was read.
        _readReceiptOffer.value = null
        _readReceiptState.value = ReadReceiptState.Idle
        readReceiptAnswer = ReadReceiptAnswer.PENDING
        // Re-point the move picker at the account this page's message belongs to, before any
        // of its state is read (the folders come from the cache, so no network is involved).
        _ownerAccountId.value = credentials()?.id
        _moveAccountId.value = null
        anchorMarked = false
        settleMarking = null
        if (!warm) {
            _state.value = MessageState.Loading
            _messages.value = emptyList()
        }
        _mailboxRole.value = null
        _ownMessage.value = false
        _deliveredTo.value = null
        coverSubject = null
        _snoozedUntil.value = null
        _mailboxId.value = null
        _calendar.value = null
        calendarLoadedFor = null
        _crypto.value = CryptoUiState.None
        autoDecryptTried = false
        _replyBarVisible.value = false
        _manualShowImages.value = false
        _printRequested.value = false
        _plainText.value = null
        _headers.value = null
        // The same three lines as [dismissAttachedMessage], written out: ReaderStateResetOnLoadTest
        // reads this prologue for `_attachedMessage.value`, and a helper would hide it.
        attachedMessageJob?.cancel()
        attachedMessageJob = null
        _attachedMessage.value = null
        _attachmentStatus.value = null
        // The per-sender rule, all three halves. The status is the answer to a gesture made on
        // the message we are leaving ("Rule added"), and it would be read as this message's. The
        // script is worse than stale: the pager crosses ACCOUNTS in the unified inbox, so the
        // previous account's rules would decide whether THIS account's sender is already ruled
        // — a grey "already there" on an address nothing files away, or the reverse. Unread costs
        // nothing: the script is read lazily, only when the participants panel is opened.
        // The account's own addresses cross accounts with it, and they are read from the store,
        // so they are taken here rather than left to a round-trip: the one thing that must never
        // arrive late is the reason the gesture is refused.
        _senderRules.value = SenderScript.Unread
        _senderRuleStatus.value = null
        // Same argument, one gesture over: "could not report as spam" belongs to the message we
        // are leaving, and toasted on the next page it would name the wrong mail.
        _actionStatus.value = null
        _accountAddresses.value = ownAddresses()
        // Identities ONLY, no login — see the flow's own comment for why this list is not the one
        // above. Same line, same moment, so neither can be the previous account's.
        _identityAddresses.value = store.identities(accountId).map { it.email }
        viewModelScope.launch {
            // Account-scoped: a snooze belongs to one account's message (issue #31).
            _snoozedUntil.value = runCatching {
                credentials()?.let { repo.snoozedUntil(it.id, emailId) }
            }.getOrNull()
            // Paint the cached header (sender/subject/preview) immediately so the screen shows
            // the tapped message at once; the body fills in when the fetch returns. Header-only
            // (body = null) — the body itself is rendered a single time, by the WebView.
            // The cached list row carries the folder it's filed under (its mailboxId), which is
            // reliable on both IMAP and JMAP. The fetched body's mailboxId can be absent over
            // JMAP (an email belongs to a *set* of mailboxes), so junk detection keys off this.
            val listEmail = runCatching { credentials()?.let { repo.cachedEmail(it.id, emailId) } }.getOrNull()
            listEmail?.let { cached ->
                // ⛔ HEADER-ONLY (body = null): this repaint drops the WebView just as surely as
                // the prologue does, so it is skipped when the warm-up already put the real body
                // there. Everything below it is metadata and stays unconditional.
                if (!warm) {
                    _messages.value = listOf(ThreadMessage(id = cached.id, header = cached))
                    _state.value = MessageState.Loaded(cached)
                }
                val role = repo.mailboxRole(cached.accountId ?: accountId, cached.mailboxId)
                _mailboxRole.value = role
                _ownMessage.value = isOwnMessage(cached, role)
                _deliveredTo.value = receivingAddress(cached, ownAddresses())
                coverSubject = cached.subject ?: coverSubject
                _mailboxId.value = cached.mailboxId
            }
            try {
                val credentials = credentials() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                // The tapped (newest) message: cache-first body + its inline images. Body and
                // images arrive together so the WebView renders once, complete. Not marked read
                // here — see [onActiveChanged].
                val opened = repo.openMessage(credentials, emailId, markRead = false)
                // Stamp the owning account: the fetched body never carries one, and downstream
                // actions (reader archive/delete in the unified inbox) route by it.
                val anchor = opened.email.copy(accountId = opened.email.accountId ?: accountId)
                _state.value = MessageState.Loaded(anchorDisplay(anchor))
                // Show the opened message (body + inline images) IMMEDIATELY. The thread fetch
                // below is a separate network round-trip; it must not gate the body the user
                // came to read — otherwise a cached/prefetched body still waits on the network.
                val anchorBody = anchorDisplay(anchor)
                _messages.value = listOf(
                    ThreadMessage(
                        id = anchor.id,
                        header = anchorBody,
                        body = anchorBody,
                        inlineImages = opened.inlineImages,
                    ),
                )
                val anchorRole = repo.mailboxRole(anchor.accountId, anchor.mailboxId ?: listEmail?.mailboxId)
                _mailboxRole.value = anchorRole
                _ownMessage.value = isOwnMessage(anchor, anchorRole)
                // The fetched original carries the full To AND Cc; the cached row above remembers
                // To only, so this is where a message addressed to an alias in Cc gets named. Never
                // erases what the cache already found (#81).
                _deliveredTo.value = receivingAddress(anchor, ownAddresses()) ?: _deliveredTo.value
                // The cover subject, with the same care — but ⛔ NOT unconditionally: `openMessage`
                // serves the decrypt cache FIRST (`MailRepository.kt:3489-3501`), so re-opening a
                // message decrypted earlier in this process hands back a body whose subject is the
                // PROTECTED one (#128). Which of the two this is, is what `opened.crypto` says.
                coverSubject = coverSubjectAfterOpen(coverSubject, anchor, opened.crypto)
                _mailboxId.value = anchor.mailboxId ?: listEmail?.mailboxId
                // Read off the OPENED message: the two headers only ever ride with the body
                // fetch, never with the cached list row painted a moment ago.
                _unsubscribe.value = UnsubscribeHeader.parse(anchor.listUnsubscribe, anchor.listUnsubscribePost)
                // OpenPGP: reflect the crypto state; a decrypt is attempted once the
                // page settles in front of the user (see onActiveChanged), not while
                // the pager pre-composes neighbours.
                when (val c = opened.crypto) {
                    is MessageCrypto.Locked -> {
                        lockedKind = c.kind
                        _crypto.value = CryptoUiState.Locked(c.kind)
                        maybeAutoDecrypt()
                    }
                    is MessageCrypto.Decrypted -> _crypto.value = CryptoUiState.Decrypted(c)
                    null -> {}
                }
                // An inline image we refused to download leaves a hole in the body. Say it —
                // showing less without a word is how a problem gets hidden.
                if (anchor.oversizedInlineImageCount() > 0) {
                    _attachmentStatus.value =
                        getApplication<Application>().getString(R.string.status_inline_images_too_large)
                }
                // A calendar invite (text/calendar part) is fetched + parsed off the body so the
                // reader can show an event card above it.
                loadCalendarFor(_messages.value.first())
                // If the page already settled before the body arrived, read it now.
                maybeMarkRead()
                // …and put the receipt question now that the body — the only fetch carrying
                // `Disposition-Notification-To` — is in hand. A page that settled while the cached
                // row was on screen took its decision on a row that could not name a single
                // address; this is the first moment both halves of it exist.
                maybeOfferReadReceipt()
                // The reader shows the single opened message; the conversation (the rest of the
                // thread) now lives only in the list's inline unfold, so no thread merge here.
            } catch (t: Throwable) {
                // The unsubscribe again, and for a different reason than in the prologue: the
                // read may have failed AFTER the options were set, or with a dialog open on them.
                // Nothing here can be acted on any more — there is no message on screen — and a
                // banner that outlives its message is a banner that never goes away.
                _unsubscribe.value = null
                _unsubscribeState.value = UnsubscribeState.Idle
                _unsubscribeConfirm.value = null
                // The receipt question likewise: there is no message under it any more, and a
                // banner that outlives its message is one that never goes away.
                _readReceiptOffer.value = null
                _readReceiptState.value = ReadReceiptState.Idle
                // The screen gets a sentence that names no cause, and for
                // MessageUnavailableException it REPLACES the technical text (#159) — so unless the
                // throwable is written here, the reason this message would not open exists nowhere.
                android.util.Log.w("SternaMessage", "message load failed: $emailId", t)
                _state.value = MessageState.Error(readFailureText(t))
            }
        }
    }

    /**
     * What to show when a message won't open: the sentence [readFailureStringRes] picks, and
     * failing that the exception's own technical text. Either way the throwable itself is logged by
     * the caller's catch — this is a screen, not a report.
     */
    private fun readFailureText(t: Throwable): String =
        readFailureStringRes(t)?.let { getApplication<Application>().getString(it) }
            ?: (t.message ?: t.javaClass.simpleName)

    /** The anchor as displayed: shown read once it has been settled on (else its true state). */
    private fun anchorDisplay(anchor: Email): Email = if (anchorMarked) anchor.markRead() else anchor

    /** Every address that IS the user on the message's account: its send-as identities plus the
     *  login it authenticates with. A delegated sub-account needs no special case — the store
     *  already resolves its own address, or its login's, for it (issue #31). */
    private fun ownAddresses(): List<String> =
        accountAddresses(store.identities(accountId), credentials()?.username)

    /**
     * Whether the reader shows this message by its recipients instead of its sender — the SAME
     * decision the list row makes ([showsRecipients]), so the header cannot contradict the line the
     * user just tapped (#115).
     *
     * `unified = false`: the reader resolves the real folder of the message it is showing, whatever
     * list it was opened from, so there is no unified case to declare here.
     */
    private fun isOwnMessage(email: Email, mailboxRole: String?): Boolean = showsRecipients(
        role = mailboxRole,
        unified = false,
        selfAuthored = isSelfAuthored(email.from, store.identities(accountId)),
    )

    /**
     * Called by the pager as this page becomes (or stops being) the settled, front-most one.
     * Reading is tied to settling, not to composition, so a message swiped past is not read.
     */
    fun onActiveChanged(isActive: Boolean) {
        active = isActive
        if (isActive) {
            maybeMarkRead()
            maybeAutoDecrypt()
        }
    }

    /**
     * Kick off one silent decrypt attempt when the page is settled and the message
     * is crypto-locked. With OpenKeychain's passphrase cached this resolves without
     * any UI; otherwise the state becomes [CryptoUiState.NeedsInteraction] and the
     * user unlocks explicitly from the status card.
     */
    private fun maybeAutoDecrypt() {
        if (!active || autoDecryptTried) return
        if (_crypto.value !is CryptoUiState.Locked) return
        autoDecryptTried = true
        decrypt()
    }

    /**
     * Decrypt/verify the opened message. [interactionResult] is the data Intent
     * from the provider's PendingIntent round-trip when retrying after
     * [CryptoUiState.NeedsInteraction].
     */
    fun decrypt(interactionResult: Intent? = null) {
        val emailId = loadedId ?: return
        (_crypto.value as? CryptoUiState.Locked)?.let { _crypto.value = it.copy(decrypting = true) }
        viewModelScope.launch {
            val credentials = credentials() ?: return@launch
            // Fail into the status card, never crash: decryptMessage returns PgpResult.Error for
            // the failures it anticipates, but an exception ESCAPING it (provider IPC death,
            // parcel mismatch from a foreign OpenKeychain build, OOM on a huge raw source) used
            // to propagate out of this coroutine and take the app down — Codeberg #14, a signed
            // Proton message. Mirror load()'s catch.
            val result = try {
                repo.decryptMessage(credentials, emailId, interactionResult)
            } catch (t: Throwable) {
                PgpResult.Error(t.message ?: t.javaClass.simpleName)
            }
            when (result) {
                is PgpResult.Success -> {
                    val opened = result.value
                    val display = anchorDisplay(opened.email)
                    _messages.value = listOf(
                        ThreadMessage(
                            id = opened.email.id,
                            header = display,
                            body = display,
                            inlineImages = opened.inlineImages,
                        ),
                    )
                    _state.value = MessageState.Loaded(display)
                    _crypto.value = when (val c = opened.crypto) {
                        is MessageCrypto.Decrypted -> CryptoUiState.Decrypted(c)
                        else -> CryptoUiState.None
                    }
                }
                is PgpResult.UserInteractionRequired ->
                    _crypto.value = CryptoUiState.NeedsInteraction(result.pendingIntent)
                is PgpResult.Error -> _crypto.value = CryptoUiState.Failed(result.message)
                PgpResult.NotAvailable -> _crypto.value = CryptoUiState.Failed(null)
            }
        }
    }

    /** The user dismissed the provider's dialog: back to an unlockable state. */
    fun cancelDecrypt() {
        _crypto.value = CryptoUiState.Locked(lockedKind ?: CryptoKind.PGP_ENCRYPTED)
    }

    /**
     * Mark the anchor read (once) when this page is both settled and loaded.
     *
     * ⭐ **The one place [settleMarking] is ever written**, and the reason is the `active` guard on
     * its first line: settled means the pager has stopped on this page, so the message is in front
     * of a person. `MailRepository.setRead`'s eight other callers — a swipe, a conversation unfold,
     * a multi-select, "mark all read", a notification action, the marking on archive/delete, a
     * Sieve rule, a `$seen` arriving from another device — mark mail read that nobody looked at
     * here. `SECURITY.md` promises no read signal leaves on open; a receipt proposed for a swipe
     * would contradict it word for word. See [offeredReadReceipt] for the decision itself and
     * [ReadMarking] for how a call site says which of the nine it is.
     */
    private fun maybeMarkRead() {
        if (!active || anchorMarked) return
        val current = (_state.value as? MessageState.Loaded)?.email ?: return
        anchorMarked = true
        // Recorded BEFORE the early return below, because "already read" is one of the answers the
        // decision owes: reopening a message that was read must NOT ask again, and that is the
        // whole reason this work stores no "already offered / already refused" anywhere.
        settleMarking = if (current.isSeen) {
            ReadMarking.READER_SETTLE_ALREADY_READ
        } else {
            ReadMarking.READER_SETTLE_UNREAD
        }
        maybeOfferReadReceipt()
        if (current.isSeen) return
        // Reflect the read state immediately so the unread dot clears on settle…
        updateMessage(current.id) { it.copy(header = it.header.markRead(), body = it.body?.markRead()) }
        _state.value = MessageState.Loaded(current.markRead())
        // …then persist it (server + cache).
        viewModelScope.launch {
            val credentials = credentials() ?: return@launch
            runCatching { repo.setRead(credentials, current.id, true) }
            // Clear the mail's notification now that it's been read (Codeberg #19).
            Notifications.dismiss(getApplication(), credentials.id, credentials.username, listOf(current.id))
        }
    }

    /**
     * Put — or take away — the read-receipt question, by RUNNING the decision against the state as
     * it now stands.
     *
     * It cannot decide anything on its own: [settleMarking] is null until [maybeMarkRead] has run,
     * and [maybeMarkRead] is the settle. That null is what makes the other eight ways a message
     * becomes read structurally unable to reach the decision — none of them touches this page's
     * marking, so none of them can put a value in it.
     *
     * Called from four places, because four things can complete the picture in any order:
     *  - the SETTLE, which is the only one that decides a message was read by a person;
     *  - the BODY landing, which is the only fetch carrying `Disposition-Notification-To` (the
     *    cached list row painted first cannot);
     *  - the SETTING changing, which is both the reader turning it on with a message already open
     *    and DataStore answering after the other two have already happened;
     *  - a SEND that FAILED, which puts back a question the reader answered but that bought her
     *    nothing — a retry, exactly as the unsubscribe strip offers one. ⛔ Not a refusal: that one
     *    is remembered.
     *
     * Being a function of the current state rather than of an event is what makes replaying it
     * safe. ⛔ Except for one thing, which is why [readReceiptAnswer] goes in: a reader who said no
     * has answered, and no replay may ask them again.
     */
    private fun maybeOfferReadReceipt() {
        val marking = settleMarking ?: return
        val current = (_state.value as? MessageState.Loaded)?.email ?: return
        _readReceiptOffer.value = offeredReadReceipt(
            setting = readReceiptSetting.value,
            marking = marking,
            request = readReceiptRequest(current, _deliveredTo.value, coverSubject),
            answered = readReceiptAnswer.answered,
        )
    }

    /** Download an attachment to the cache and hand it to a viewer app. */
    fun openAttachment(part: EmailBodyPart, ownerId: String) {
        if (openingAttachment) return
        openingAttachment = true
        // A message/rfc822 part has no viewer app to go to (the chooser came up empty, silently):
        // it is read here, in a sheet, and never written to the cache. [openingAttachment] is held
        // and released by that path, so a second tap waits for the first download all the same.
        if (isAttachedMessage(part.type)) { openAttachedMessage(part, ownerId); return }
        val emailId = ownerId
        val app = getApplication<Application>()
        _attachmentStatus.value = "Opening ${part.name ?: "attachment"}…"
        viewModelScope.launch {
            try {
                val credentials = credentials() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                val bytes = repo.downloadAttachment(credentials, part, emailId)
                val file = storage.cacheAttachment(part.name, bytes)
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
                val view = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, part.type ?: "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // unguarded: not a tap. The tap was handled above, where [openingAttachment] holds
                // the second one back until this hand-off is made; there is no composition here to
                // hang the shared leave guard on.
                app.startActivity(
                    Intent.createChooser(view, app.getString(R.string.status_open_attachment)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                _attachmentStatus.value = null
            } catch (t: ContentTooLargeException) {
                // Our own refusal, not a failure: say it plainly instead of showing byte counts.
                _attachmentStatus.value = app.getString(R.string.status_attachment_too_large)
            } catch (t: Throwable) {
                _attachmentStatus.value =
                    app.getString(R.string.status_open_attachment_failed, t.message ?: "error")
            } finally {
                // Released as soon as the chooser is up (or the attempt failed), not when the user
                // comes back: the file is theirs to open again as often as they like.
                openingAttachment = false
            }
        }
    }

    /**
     * Download a `message/rfc822` part and show it in the attached-message sheet: headers, text
     * body and the names of its own attachments, parsed in memory. Same download path as any
     * attachment (50 MiB ceiling, `pgp:` guard, BODY.PEEK), but nothing is cached, no URI is
     * exposed and no app is started. Releases [openingAttachment], which [openAttachment] holds.
     */
    private fun openAttachedMessage(part: EmailBodyPart, ownerId: String) {
        val app = getApplication<Application>()
        _attachedMessage.value = AttachedMessageState.Loading
        attachedMessageJob = viewModelScope.launch {
            try {
                val credentials = credentials() ?: error(app.getString(R.string.status_no_saved_account))
                val bytes = repo.downloadAttachment(credentials, part, ownerId)
                // Parsing a stranger's message is untrusted work on a large input: off the main
                // thread, like the invitation.
                val message = withContext(Dispatchers.Default) { attachedMessageOf(bytes) }
                _attachedMessage.value = AttachedMessageState.Loaded(message)
            } catch (cancelled: CancellationException) {
                // The sheet was closed under us: not an error to show, and nothing to reopen.
                throw cancelled
            } catch (t: ContentTooLargeException) {
                _attachedMessage.value = AttachedMessageState.Error(app.getString(R.string.status_attachment_too_large))
            } catch (t: Throwable) {
                _attachedMessage.value = AttachedMessageState.Error(t.message ?: t.javaClass.simpleName)
            } finally {
                openingAttachment = false
            }
        }
    }

    /**
     * Download and parse the message's calendar invite (text/calendar part), if any, into the
     * event model exposed via [calendar]. Reuses the existing attachment download path; decodes
     * with the part charset (UTF-8 default). A download/parse miss flips [CalendarInvite.failed]
     * so the card can still offer to open the raw invitation.
     */
    private fun loadCalendarFor(msg: ThreadMessage) {
        val part = msg.body?.calendarParts()?.firstOrNull() ?: return
        if (calendarLoadedFor == msg.id) return
        calendarLoadedFor = msg.id
        _calendar.value = CalendarInvite(loading = true, part = part, ownerId = msg.id)
        viewModelScope.launch {
            try {
                val credentials = credentials()
                    ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                // Fetched because the message is open, not because anyone asked: bounded like
                // the invitation the parser will accept, not like a tapped attachment.
                val bytes = repo.downloadAttachment(
                    credentials, part, msg.id, DownloadLimits.CALENDAR_MAX_BYTES,
                )
                val charset = runCatching {
                    java.nio.charset.Charset.forName(part.charset ?: "UTF-8")
                }.getOrDefault(Charsets.UTF_8)
                // Parsing a stranger's .ics is untrusted work on an unbounded input: keep it off
                // the main thread so a pathological invitation can never freeze the reader.
                val event = withContext(Dispatchers.Default) {
                    ICalendar.parse(String(bytes, charset))
                }
                _calendar.value = CalendarInvite(
                    loading = false,
                    event = event,
                    failed = event == null,
                    part = part,
                    ownerId = msg.id,
                )
            } catch (t: Throwable) {
                _calendar.value = CalendarInvite(loading = false, failed = true, part = part, ownerId = msg.id)
            }
        }
    }

    /**
     * RSVP to the open invite: build an iTIP REPLY .ics for [partstat] (ACCEPTED / DECLINED /
     * TENTATIVE) and e-mail it to the organiser via the normal send path. The chosen status is
     * reflected on the card for this session only (not persisted across restarts). The reply's
     * timestamp is taken here and passed into the pure builder so it stays testable.
     */
    fun respondToInvite(partstat: String) {
        val invite = _calendar.value ?: return
        val event = invite.event ?: return
        val organizer = event.organizerEmail?.takeIf { it.isNotBlank() } ?: return
        if (invite.response is InviteResponse.Sending) return
        val nowMillis = System.currentTimeMillis()
        _calendar.value = invite.copy(response = InviteResponse.Sending)
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val credentials = credentials() ?: error(app.getString(R.string.status_no_saved_account))
                // The replying attendee is this account; reuse its CN from the invite if it lists us.
                val accountEmail = credentials.username
                val cn = event.attendees.firstOrNull { it.email.equals(accountEmail, true) }?.cn
                val ics = ICalendar.buildReply(event, accountEmail, cn, partstat, nowMillis)
                val summary = event.title ?: app.getString(R.string.calendar_event_untitled)
                val subject = when (partstat) {
                    "ACCEPTED" -> app.getString(R.string.calendar_reply_subject_accepted, summary)
                    "DECLINED" -> app.getString(R.string.calendar_reply_subject_declined, summary)
                    else -> app.getString(R.string.calendar_reply_subject_tentative, summary)
                }
                val who = cn?.takeIf { it.isNotBlank() } ?: accountEmail
                val body = when (partstat) {
                    "ACCEPTED" -> app.getString(R.string.calendar_reply_body_accepted, who)
                    "DECLINED" -> app.getString(R.string.calendar_reply_body_declined, who)
                    else -> app.getString(R.string.calendar_reply_body_tentative, who)
                }
                repo.sendCalendarReply(credentials, organizer, subject, body, ics.toByteArray(Charsets.UTF_8))
                // The message may have changed underneath us while sending — guard by owner.
                if (_calendar.value?.ownerId == invite.ownerId) {
                    _calendar.value = _calendar.value?.copy(response = InviteResponse.Sent(partstat))
                }
            } catch (t: Throwable) {
                if (_calendar.value?.ownerId == invite.ownerId) {
                    _calendar.value = _calendar.value?.copy(response = InviteResponse.Failed)
                }
            }
        }
    }

    fun toggleFlag() {
        val current = (_state.value as? MessageState.Loaded)?.email ?: return
        val flagged = !current.isFlagged
        // Optimistic local update.
        _state.value = MessageState.Loaded(
            current.copy(
                keywords = current.keywords.toMutableMap().apply {
                    if (flagged) put("\$flagged", true) else remove("\$flagged")
                },
            ),
        )
        viewModelScope.launch {
            val credentials = credentials() ?: return@launch
            runCatching { repo.setFlagged(credentials, current.id, flagged) }
        }
    }

    fun markUnread(onDone: () -> Unit) = act(onDone) { c, id -> repo.setRead(c, id, false) }
    // Archive + delete route through the shared inbox VM (MessageScreen.onArchive/onDelete) so the
    // reader reuses the same count nudge + Undo as swipe/bulk (Codeberg #23, RC-6); no local copies.
    fun reportSpam(onDone: () -> Unit) = act(onDone) { c, id -> repo.reportSpam(c, id) }
    fun notSpam(onDone: () -> Unit) = act(onDone) { c, id -> repo.notSpam(c, id) }

    /** Snooze the open message until [until]: hide it now, re-surface (and notify) at that time. */
    fun snooze(until: Long, onDone: () -> Unit) {
        val email = (_state.value as? MessageState.Loaded)?.email ?: return
        viewModelScope.launch {
            val credentials = credentials() ?: return@launch
            runCatching {
                repo.snooze(email.id, credentials.id, until)
                Snoozes.enqueue(getApplication(), email.id, credentials.id, until)
            }.onSuccess { _snoozedUntil.value = until }
            onDone()
        }
    }

    private fun act(onDone: () -> Unit, op: suspend (AccountCredentials, String) -> Unit) {
        val id = loadedId ?: return
        viewModelScope.launch {
            try {
                val credentials = credentials() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                op(credentials, id)
                onDone()
            } catch (t: Throwable) {
                // ⛔ NEVER `MessageState.Error` HERE, whatever went wrong. What act() drives —
                // report as spam, not spam, mark unread — are gestures ON the open message, and
                // none of them is a reason to destroy what the user is reading; K-9 and Gmail do
                // not. This used to be `MessageState.Error(t.message ?: …)`, i.e. "Could not load
                // message: <the exception's own text>" over a message that had loaded perfectly
                // well — and since a failed IMAP move stopped being answered with a destructive
                // copy, the text it put there is the echoed command
                // (`UID MOVE 1234 "Junk" failed: NO [OVERQUOTA] …`, ImapClient.kt:299) or the
                // refusal's IMAP jargon about a "destroy" (#99). One surface, one assignment.
                _actionStatus.value = readerActionFailureText(t) { res -> getApplication<Application>().getString(res) }
            }
        }
    }
}
