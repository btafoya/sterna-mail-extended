package app.sterna.ui.compose

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.R
import app.sterna.contacts.AndroidContacts
import app.sterna.contacts.ContactSuggestion
import app.sterna.contacts.mergeSuggestions
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.data.account.pgpPublicKeyBackfill
import app.sterna.core.data.account.pgpPublicKeyCacheValue
import app.sterna.core.data.db.OutboxState
import app.sterna.core.data.db.ScheduledSendEntity
import app.sterna.core.data.mail.DraftOpenRoute
import app.sterna.core.data.mail.DraftSaveOutcome
import app.sterna.core.data.mail.LocalDraftEdit
import app.sterna.core.data.mail.SendDraftTarget
import app.sterna.core.data.mail.abandonPlan
import app.sterna.core.data.mail.carriedDraftAccountId
import app.sterna.core.data.mail.commitThenConsumeLocalDraft
import app.sterna.core.data.mail.draftOpenRoute
import app.sterna.core.data.mail.draftSaveConsumesTheQueuedRow
import app.sterna.core.data.mail.draftSaveNeedsNotice
import app.sterna.core.data.mail.draftSaveParksTheQueuedRowAs
import app.sterna.core.data.mail.localDraftLeaseAfterSave
import app.sterna.core.data.mail.localDraftPrefill
import app.sterna.core.data.mail.reopenedDraftIsLossy
import app.sterna.core.data.mail.scheduledSendDelayMillis
import app.sterna.core.data.mail.sendDraftTarget
import app.sterna.core.data.mail.takeLocalDraftEditOrGiveItBack
import app.sterna.core.data.mail.unlessBodyIsLossy
import app.sterna.core.data.mail.unlessDraftBelongsElsewhere
import app.sterna.core.data.pgp.PgpMode
import app.sterna.core.data.pgp.encrypts
import app.sterna.core.data.pgp.PgpResult
import app.sterna.core.data.text.Block
import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.Link
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.Span
import app.sterna.core.data.text.draftHtmlIsLossy
import app.sterna.core.data.text.draftHtmlToSave
import app.sterna.core.data.text.safeFileName
import app.sterna.core.data.text.toPlainText
import app.sterna.core.imap.OutgoingAttachment
import app.sterna.core.imap.OutgoingMessage
import app.sterna.core.imap.OutgoingMime
import app.sterna.core.imap.PgpMime
import app.sterna.mail.MessageDestroyWorker
import app.sterna.send.Outbox
import app.sterna.send.ScheduledSends
import app.sterna.send.SendOutbox
import app.sterna.send.composeDraftOf
import app.sterna.core.jmap.ContentTooLargeException
import app.sterna.core.jmap.DownloadLimits
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.net.hasUsableNetwork
import app.sterna.net.isOfflineFailure
import app.sterna.util.MailDates
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ComposeState {
    data object Idle : ComposeState
    data object Sending : ComposeState
    data object Done : ComposeState

    /** [whileSaving] distinguishes a failed draft save from a failed send, for the banner text. */
    data class Error(val message: String, val whileSaving: Boolean = false) : ComposeState

    /** The OpenPGP provider needs the user (passphrase/key pick) to finish the send:
     *  launch [pendingIntent] and hand the result to ComposeViewModel.retryPgpSend. */
    data class PgpInteraction(val pendingIntent: PendingIntent) : ComposeState
}

/** Initial field values, e.g. for a reply, forward, or a restored (undone-send) draft. */
data class DraftFields(
    val to: String,
    val cc: String = "",
    val bcc: String = "",
    val subject: String,
    val body: String,
    /** The inline styling [body] carries (#131), beside it and not inside it. A route that reopens a
     *  styled body and drops this is not showing "no styling yet": it is showing the styling REMOVED,
     *  and the next save writes that removal. */
    val bodyRanges: Map<Inline, List<Span>> = emptyMap(),
    /**
     * The list blocks [body] carries (#131), beside [bodyRanges] for the same reason. A route that
     * reopens a body holding a list and drops this is showing the list REMOVED.
     */
    val bodyBlocks: List<Block> = emptyList(),
    /** The links [body] carries (#131), the sharpest of the three: a draft holding a link is judged
     *  REPRODUCIBLE (`draftHtmlIsLossy`), so the next save DESTROYS the server original — a route that
     *  drops the links replaces the only copy that still had them. */
    val bodyLinks: List<Link> = emptyList(),
    /** Reveal the Cc/Bcc row (used when restoring a draft that had them). */
    val expand: Boolean = false,
    /**
     * Tick the "ask for a read receipt" box (RFC 8098) — set when reopening a message that already
     * asked. NOT filled when a draft is reopened from the server: the header is not read back (debt
     * D1), and claiming otherwise would tick a box for a request the message may not carry.
     */
    val requestReceipt: Boolean = false,
    /**
     * This body ALREADY carries the quoted original: true only for the reply prefill built on the path
     * where nothing was cached. The screen has to be told, because the screen is what survives: after a
     * process death [prepare] runs again on a new ViewModel while the composer comes back with its body
     * restored, and left unsaid the rebuilt ViewModel draws "Couldn't load the original message." over
     * the quote plainly on screen.
     */
    val quoted: Boolean = false,
)

/** A "From" choice: one identity belonging to a specific account. */
data class FromOption(val accountId: String, val identity: StoredIdentity)

/**
 * What a "From" switch asks of the body's signature (D5): the identity being left had one, so its
 * block is [Swap]ped where it is still intact (an edited or deleted one is left alone); or it had
 * none, so the new signature is [Insert]ed where the prefill would have put it.
 */
sealed interface SignatureChange {
    /** Whether the block is rewritten with the standard "-- " delimiter line (#90). Carried here
     *  because the rewrite happens in the screen, while the setting lives in DataStore. */
    val delimiter: Boolean

    data class Swap(val from: String, val to: String, override val delimiter: Boolean) : SignatureChange
    data class Insert(
        val signature: String,
        val belowQuote: Boolean,
        override val delimiter: Boolean,
    ) : SignatureChange
}

class ComposeViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val repo = application.container.mailRepository
    private val outbox = application.container.sendOutbox
    private val pgp = application.container.pgpEngine
    private val settings = application.container.settingsRepository

    /** Outlives this screen: putting a queued message back happens as the composer is popped (#70). */
    private val appScope = application.container.appScope

    private val _state = MutableStateFlow<ComposeState>(ComposeState.Idle)
    val state: StateFlow<ComposeState> = _state.asStateFlow()

    /**
     * The id of the queued row this composer was opened on (#70), if any. The row stays in the outbox
     * marked EDITING while it is held here; committing the message somewhere consumes it, and
     * committing it nowhere gives it back to the queue.
     */
    private var editingOutboxId: Long? = null

    /**
     * Whether a queued row is really parked behind this composer — the observable face of
     * [editingOutboxId], for the title ("Edit") and the leave dialog, which must say the message goes
     * BACK to the queue rather than claim it is destroyed (#96/#70).
     *
     * Seeded from the handed-over draft rather than from `false`, because the screen reads it on its
     * first frame while [prepare] only runs after it; [prepare] then settles it, and
     * [resumeOutboxEdit] settles it again after a process death.
     */
    private val _editingOutbox = MutableStateFlow(holdsQueuedOutboxRow(true, outbox.restored.value))
    val editingOutbox: StateFlow<Boolean> = _editingOutbox.asStateFlow()

    private val _onlyCopy = MutableStateFlow(false)
    /**
     * True when the message on screen exists nowhere else and nothing will put it back: a send the
     * user undid. Closing then destroys it, so the screen asks first even if nothing was typed.
     *
     * Deliberately NOT what titles the screen — that is [editingOutbox]; conflating the two is what
     * titled an empty composer "Edit" after a process death (#96).
     */
    val onlyCopy: StateFlow<Boolean> = _onlyCopy.asStateFlow()

    private val _prefill = MutableStateFlow<DraftFields?>(null)
    val prefill: StateFlow<DraftFields?> = _prefill.asStateFlow()

    /**
     * The quoted original for a reply, delivered separately from [prefill]: the headers come instantly
     * from the cached row while the quote needs the full original over the network. Consumed once
     * ([consumeReplyQuote]) — left in place it is replayed to every new collector, and this ViewModel
     * outlives its activity.
     */
    private val _replyQuote = MutableStateFlow<String?>(null)
    val replyQuote: StateFlow<String?> = _replyQuote.asStateFlow()

    private val _attachments = MutableStateFlow<List<EmailBodyPart>>(emptyList())
    val attachments: StateFlow<List<EmailBodyPart>> = _attachments.asStateFlow()

    private val _attachmentsTouched = MutableStateFlow(false)
    /**
     * Whether the user added or removed an attachment (#70/#94). The unsaved-changes guard needs "did
     * the user change anything", not "are there any": a reopened message opens WITH attachments, and
     * counting those fired the discard dialog on an untouched close. Read in exactly one place, and
     * it decides nothing at save time.
     */
    val attachmentsTouched: StateFlow<Boolean> = _attachmentsTouched.asStateFlow()

    private val _attachmentStatus = MutableStateFlow<String?>(null)
    val attachmentStatus: StateFlow<String?> = _attachmentStatus.asStateFlow()

    /**
     * One-shot string resources to surface after an action that closes the screen, where an inline
     * banner would never be read. The screen shows them as a toast, which outlives the navigation.
     */
    private val _notices = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val notices: SharedFlow<Int> = _notices.asSharedFlow()

    /**
     * The draft this phone was keeping has been deleted, and the screen may go (#95 × #127).
     *
     * A signal of its own, NOT [ComposeState.Done], which plays the tern's flight and pops to the
     * inbox; a deletion closes onto the Drafts list. Emitted only after the destroy is durable and
     * the row consumed: fired from the tap, it would close the screen over a deletion that failed.
     */
    private val _localDraftDeleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val localDraftDeleted: SharedFlow<Unit> = _localDraftDeleted.asSharedFlow()

    /**
     * Whether deleting the draft this phone is keeping would LEAVE the server copy behind it — the one
     * thing the confirmation owes the reader before her finger lands (#95). Where this phone never read
     * that copy whole it is deliberately kept, so the row goes, the mask lifts, and the pre-edit text
     * comes back at the top of Drafts; the dialog used to say the opposite.
     *
     * The decision is [emptiedLocalDraftKeepsServerCopy], armed where its terms are read.
     */
    private val _deleteKeepsServerCopy = MutableStateFlow(false)
    val deleteKeepsServerCopy: StateFlow<Boolean> = _deleteKeepsServerCopy.asStateFlow()

    /**
     * Whether the trash icon's confirmation is up (#127).
     *
     * HERE and not in a `rememberSaveable` on the screen: a bare `remember` fell back to false on
     * every rotation and the dialog vanished without a word, but `rememberSaveable` survives PROCESS
     * DEATH too, which the draft it names does not — the flag would redraw the dialog over a dead-end
     * screen whose Delete deletes nothing.
     * Scoped to the composer's NAVIGATION ENTRY, never "the process": it also outlives the composer
     * merely leaving the composition.
     */
    private val _pendingDraftDelete = MutableStateFlow(false)
    val pendingDraftDelete: StateFlow<Boolean> = _pendingDraftDelete.asStateFlow()

    /** Raise the delete confirmation. Only ever asks — the deletion itself is a separate tap. */
    fun askDraftDelete() {
        _pendingDraftDelete.value = true
    }

    /** Close it, whichever way out was taken (Cancel, a tap outside, or the confirmed delete). */
    fun clearDraftDelete() {
        _pendingDraftDelete.value = false
    }

    /**
     * Whether the leave dialog is up — "You haven't saved your changes" (#35, #127). HERE for
     * [pendingDraftDelete]'s reason: a bare `remember` fell back to false on every rotation and the
     * question left the screen as if it had been answered.
     *
     * It says only WHETHER the dialog is up; what it says and which buttons it offers is decided on
     * the screen. Lifetime: the NAVIGATION ENTRY, never "the process".
     */
    private val _pendingDiscard = MutableStateFlow(false)
    val pendingDiscard: StateFlow<Boolean> = _pendingDiscard.asStateFlow()

    /** Raise the leave dialog. Only ever asks — leaving is a separate tap, on one of its buttons. */
    fun askDiscard() {
        _pendingDiscard.value = true
    }

    /** Close it, whichever way out was taken (Cancel, a tap outside, Discard, or Save draft). */
    fun clearDiscard() {
        _pendingDiscard.value = false
    }

    /**
     * Whether the pre-send "did you forget an attachment?" question is up. HERE for
     * [pendingDraftDelete]'s reason: turning the phone took it away and left the message unsent, with
     * nothing said about why.
     *
     * The two pre-send guards are CHAINED, never up together. Do not merge them into one state.
     */
    private val _pendingForgotAttachment = MutableStateFlow(false)
    val pendingForgotAttachment: StateFlow<Boolean> = _pendingForgotAttachment.asStateFlow()

    /** Raise the forgotten-attachment question. Only ever asks — the send is the next tap. */
    fun askForgotAttachment() {
        _pendingForgotAttachment.value = true
    }

    /** Close it (Back to the message, a tap outside, or on the way to the next guard). */
    fun clearForgotAttachment() {
        _pendingForgotAttachment.value = false
    }

    /**
     * Whether the pre-send "this goes to many recipients" question is up — the second link of the
     * chain. HERE for [pendingDraftDelete]'s reason; see [pendingForgotAttachment] for the
     * chaining, which is an order, not a combined state. Lifetime: the navigation entry.
     */
    private val _pendingManyRecipients = MutableStateFlow(false)
    val pendingManyRecipients: StateFlow<Boolean> = _pendingManyRecipients.asStateFlow()

    /** Raise the many-recipients question. Only ever asks — the send is the next tap. */
    fun askManyRecipients() {
        _pendingManyRecipients.value = true
    }

    /** Close it (Back to the message, a tap outside, or just before the send goes out). */
    fun clearManyRecipients() {
        _pendingManyRecipients.value = false
    }

    /**
     * Whether the "pick date and time" CALENDAR is up (#161). HERE for [pendingDraftDelete]'s
     * reason. The two halves are CHAINED, never up together, and must not be merged.
     */
    private val _pendingScheduleDay = MutableStateFlow(false)
    val pendingScheduleDay: StateFlow<Boolean> = _pendingScheduleDay.asStateFlow()

    /** Raise the calendar. Only ever asks — nothing is scheduled until the clock's OK is tapped. */
    fun askScheduleDay() {
        _pendingScheduleDay.value = true
    }

    /** Close it (Cancel, a tap outside, or on the way to the clock). */
    fun clearScheduleDay() {
        _pendingScheduleDay.value = false
    }

    /**
     * Whether the "pick date and time" CLOCK is up — the second half, raised once a day is chosen
     * (#161). HERE for [pendingDraftDelete]'s reason; see [pendingScheduleDay] for the chaining.
     */
    private val _pendingScheduleTime = MutableStateFlow(false)
    val pendingScheduleTime: StateFlow<Boolean> = _pendingScheduleTime.asStateFlow()

    /** Raise the clock. The send is the next tap, and only if the instant is still ahead. */
    fun askScheduleTime() {
        _pendingScheduleTime.value = true
    }

    /** Close it (Cancel, a tap outside, or the confirmed schedule). */
    fun clearScheduleTime() {
        _pendingScheduleTime.value = false
    }

    /**
     * The day already chosen in the calendar, as the picker gives it: UTC midnight, never local —
     * [pickedScheduleMillis] is the only thing allowed to read it. HERE and not on the screen, with
     * one more reason than the flags above: a rotation BETWEEN the calendar and the clock would lose it.
     */
    private val _scheduleDay = MutableStateFlow<Long?>(null)
    val scheduleDay: StateFlow<Long?> = _scheduleDay.asStateFlow()

    /** Keep the day the calendar handed back, on its way to the clock. */
    fun chooseScheduleDay(dayUtcMidnightMillis: Long) {
        _scheduleDay.value = dayUtcMidnightMillis
    }

    /** Drop it once the clock is closed, so the next opening starts from nothing. */
    fun forgetScheduleDay() {
        _scheduleDay.value = null
    }

    /** Every identity across all accounts, and the chosen one (which sets the sending account). */
    private val _fromOptions = MutableStateFlow<List<FromOption>>(emptyList())
    val fromOptions: StateFlow<List<FromOption>> = _fromOptions.asStateFlow()
    private val _selectedFrom = MutableStateFlow<FromOption?>(null)
    val selectedFrom: StateFlow<FromOption?> = _selectedFrom.asStateFlow()

    /**
     * Switch the sending identity, and report what that asks of the body's signature (D5), or null when
     * it asks nothing: an identity being left WITH a signature gives a [SignatureChange.Swap], leaving
     * an edited or deleted block as the user made it; one WITHOUT gives an [SignatureChange.Insert],
     * still obeying the two reply settings.
     */
    suspend fun selectFrom(option: FromOption, isReplyOrForward: Boolean = false): SignatureChange? {
        val previous = selectedIdentity()
        _selectedFrom.value = option
        refreshPgp()
        val old = signatureTextOf(previous)
        val new = signatureTextOf(option.identity)
        if (old == new) return null
        val delimiter = settings.signatureDelimiter.first()
        if (old.isNotBlank()) return SignatureChange.Swap(old, new, delimiter)
        if (new.isBlank()) return null
        if (isReplyOrForward && !settings.signatureOnReplies.first()) return null
        return SignatureChange.Insert(
            signature = new,
            belowQuote = isReplyOrForward && settings.signatureBelowQuote.first(),
            delimiter = delimiter,
        )
    }

    private fun selectedIdentity(): StoredIdentity? = _selectedFrom.value?.identity

    /**
     * Say that the quoted original was dropped (B6). On a slow link the quote arrives after compose
     * has opened, and it is deliberately not dropped onto text already typed — which until now
     * happened in silence. A toast, nothing more: no "insert it anyway" action to reason about.
     */
    fun noticeQuoteNotAdded() {
        _notices.tryEmit(R.string.compose_quote_not_added)
    }

    /**
     * The screen has dealt with [replyQuote] — dropped it in, or said it could not: retire it so it is
     * handled exactly once. A rotation RECREATES the activity while this ViewModel survives, so left
     * non-null the quote is replayed to every new collector: the caret goes back to offset 0, and the
     * B6 toast is posted again.
     *
     * Called only from the two branches that HANDLED it: on the third way out nothing has posted it,
     * so consuming would throw it away in silence.
     */
    fun consumeReplyQuote() {
        _replyQuote.value = null
    }

    /** The identity's plain-text signature, with a legacy raw-HTML one flattened first. */
    private fun signatureTextOf(identity: StoredIdentity?): String =
        identity?.withSplitSignature()?.signature.orEmpty()

    /** The identity's HTML signature (imported, or served by JMAP), empty when it is plain text. */
    private fun signatureHtmlOf(identity: StoredIdentity?): String =
        identity?.withSplitSignature()?.signatureHtml.orEmpty()

    /**
     * The body a reply/forward opens with: the [quoted] original, plus the signature per the three
     * settings, all read from DataStore inside the prefill coroutine rather than from a cached
     * snapshot, so a composer opened moments after launch still honours them.
     */
    private suspend fun replyBody(quoted: String): String =
        if (!settings.signatureOnReplies.first()) {
            quoted
        } else {
            bodyWithSignature(
                quoted,
                signatureTextOf(selectedIdentity()),
                settings.signatureBelowQuote.first(),
                settings.signatureDelimiter.first(),
            )
        }

    // --- OpenPGP -----------------------------------------------------------------------------

    /** The per-message crypto mode (lock toggle in the top bar). */
    private val _pgpMode = MutableStateFlow(PgpMode.OFF)
    val pgpMode: StateFlow<PgpMode> = _pgpMode.asStateFlow()

    /** Whether the toggle is offered: sending account has PGP set up + provider bindable. */
    private val _pgpAvailable = MutableStateFlow(false)
    val pgpAvailable: StateFlow<Boolean> = _pgpAvailable.asStateFlow()

    /** Per-address key availability for ENCRYPT mode (address → has a usable key). */
    private val _recipientKeys = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val recipientKeys: StateFlow<Map<String, Boolean>> = _recipientKeys.asStateFlow()

    private var recipientKeysJob: Job? = null

    /** The recipient set [_recipientKeys] was last computed for; null when it never was, or when a
     *  change invalidated the answer. Gate of the anti-flicker rule, see [recipientKeysStale] (#35). */
    private var keyedRecipients: List<String>? = null

    /** True once the user sets the lock by hand: the mode then reflects their intent and is never
     *  auto-downgraded. While false the mode is derived from recipient-key availability (#35). */
    private var pgpModeUserSet = false

    /** Recipients with no usable key that kept an opportunistic default from encrypting, surfaced
     *  as an inline hint so it's clear why the message won't be encrypted (#35). */
    private val _pgpKeylessRecipients = MutableStateFlow<List<String>>(emptyList())
    val pgpKeylessRecipients: StateFlow<List<String>> = _pgpKeylessRecipients.asStateFlow()

    /** Emits the new mode only when the user cycles the lock by hand, so the confirmation snackbar
     *  fires on a deliberate toggle and never on an automatic opportunistic switch (#35). */
    private val _pgpToggleAnnounce = MutableSharedFlow<PgpMode>(extraBufferCapacity = 1)
    val pgpToggleAnnounce: SharedFlow<PgpMode> = _pgpToggleAnnounce.asSharedFlow()

    /** The sending account when its PGP is enabled with a key; else null. */
    private fun pgpAccount(): StoredAccount? =
        (_selectedFrom.value?.accountId ?: accountId ?: store.load()?.id)
            ?.let { store.account(it) }
            ?.takeIf { it.pgpEnabled && it.pgpSignKeyId != 0L }

    /** Re-evaluate availability (on open, on From changes, and once a restored message has put its own
     *  mode back); drops the mode if PGP is gone, and SAYS SO when that costs the user a lock they
     *  were shown (#35). The verdict itself is [pgpRefresh]. */
    private fun refreshPgp() {
        viewModelScope.launch {
            val account = pgpAccount()
            val available = account != null && pgp.isAvailable()
            _pgpAvailable.value = available
            // Another account means another keyring: whatever was looked up no longer answers for
            // these recipients (#35).
            keyedRecipients = null
            val outcome = pgpRefresh(
                current = _pgpMode.value,
                available = available,
                accountConfigured = account != null,
                encryptByDefault = account?.pgpEncryptByDefault == true,
                userSet = pgpModeUserSet,
            )
            _pgpMode.value = outcome.mode
            if (!available) _pgpKeylessRecipients.value = emptyList()
            // The padlock is the one indicator that has to be trusted, and it is about to disappear
            // from the top bar entirely. Losing it in silence is how a message the user encrypted
            // comes back looking ordinary, so name what went missing (#35).
            when (outcome.dropped) {
                PgpDropReason.NONE -> Unit
                PgpDropReason.NOT_CONFIGURED -> _notices.tryEmit(R.string.compose_pgp_not_configured)
                PgpDropReason.NO_PROVIDER -> _notices.tryEmit(R.string.message_pgp_no_provider)
            }
            // Last, and never in front of the mode: this writes nothing on screen and must not delay
            // what does.
            if (available) cachePgpPublicKey(account)
        }
    }

    /** Accounts whose public key this composer has already tried to read; see
     *  [pgpPublicKeyBackfill] for why one attempt per opening and not one per refresh. */
    private val pgpPublicKeyTried = mutableSetOf<String>()

    /**
     * Read this account's own public key from the provider and cache it, for accounts configured before
     * the cache existed. SILENT in every direction: the provider may answer UserInteractionRequired,
     * and nothing here may interrupt someone writing a message over a cache. And it is
     * [store.setPgpPublicKey], which refuses the write if the key id moved.
     */
    private suspend fun cachePgpPublicKey(account: StoredAccount?) {
        val id = pgpPublicKeyBackfill(account, pgpPublicKeyTried) ?: return
        val keyId = account?.pgpSignKeyId ?: return
        pgpPublicKeyTried += id
        val read = pgp.getPublicKey(keyId, account.username)
        if (read is PgpResult.Success) {
            store.setPgpPublicKey(id, keyId, pgpPublicKeyCacheValue(read.value))
        }
    }

    /** Lock toggle (short tap): OFF → SIGN → ENCRYPT → OFF. The cycle itself is [nextPgpMode]. */
    fun cyclePgpMode() = setPgpMode(nextPgpMode(_pgpMode.value))

    /**
     * The mode the user picked by hand — the lock's short tap, or the long-press menu, the only way to
     * [PgpMode.ENCRYPT_UNSIGNED]. ONE body for both: a hand-set mode is marked user-chosen so it is
     * never auto-downgraded (#35) and it announces itself, and a second entry point without both would
     * let encrypt-by-default silently rewrite a choice just made.
     */
    fun setPgpMode(mode: PgpMode) {
        pgpModeUserSet = true
        _pgpKeylessRecipients.value = emptyList()
        _pgpMode.value = mode
        _pgpToggleAnnounce.tryEmit(_pgpMode.value)
    }

    /**
     * Refresh per-recipient key availability, then re-derive the opportunistic default mode (#35).
     * Called on every keystroke, but it only acts when the set of real addresses changed: a half-typed
     * token has no key by construction, so recomputing on it would flip the lock to "not encrypted"
     * mid-word — and skipping keeps the provider out of it, each check being a round-trip per address.
     */
    fun updateRecipientKeys(to: String, cc: String, bcc: String) {
        val account = pgpAccount()
        val care = _pgpAvailable.value &&
            (account?.pgpEncryptByDefault == true || _pgpMode.value.encrypts)
        if (!care) {
            _recipientKeys.value = emptyMap()
            keyedRecipients = null
            return
        }
        val addresses = encryptionRecipients(to, cc, bcc)
        if (!recipientKeysStale(keyedRecipients, addresses)) return
        keyedRecipients = addresses
        recipientKeysJob?.cancel()
        recipientKeysJob = viewModelScope.launch {
            _recipientKeys.value =
                if (addresses.isEmpty()) emptyMap()
                else runCatching { pgp.findKeysEach(addresses) }.getOrDefault(emptyMap())
            deriveAutoMode(addresses)
        }
    }

    /**
     * Opportunistic default (#35): with encrypt-by-default on and the lock not set by hand, encrypt
     * only when EVERY recipient has a key, otherwise fall back visibly to plain. The keyless
     * recipients are recorded for the inline hint.
     */
    private fun deriveAutoMode(addresses: List<String>) {
        val account = pgpAccount() ?: return
        if (pgpModeUserSet || !account.pgpEncryptByDefault) return
        // Encrypt is the default intent; back off only when a recipient is CONFIRMED to have no key,
        // so a pending lookup leaves it optimistic and the lock does not flicker while typing.
        val keyless = addresses.filter { _recipientKeys.value[it] == false }
        if (keyless.isEmpty()) {
            _pgpMode.value = PgpMode.ENCRYPT
            _pgpKeylessRecipients.value = emptyList()
        } else {
            _pgpMode.value = PgpMode.OFF
            _pgpKeylessRecipients.value = keyless
        }
    }

    private val contactsEnabled =
        settings.contactSuggestions.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Whether device-contact recipient suggestions are enabled (drives the priming gate). */
    val contactSuggestionsEnabled = contactsEnabled

    /**
     * Whether the contacts-permission priming has already been offered. Initial value true so the
     * priming sheet never flashes before DataStore has loaded.
     */
    val contactsPrimed = settings.hasPrimedContacts.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Remember that the priming has been offered, so it is never shown again. */
    fun markContactsPrimed() {
        viewModelScope.launch { settings.setHasPrimedContacts(true) }
    }

    /** Enable (or disable) device-contact suggestions — the same setting as Settings > Privacy. */
    fun setContactSuggestions(enabled: Boolean) {
        viewModelScope.launch { settings.setContactSuggestions(enabled) }
    }

    /** Recipient autocomplete suggestions for the field currently being typed. */
    private val _suggestions = MutableStateFlow<List<ContactSuggestion>>(emptyList())
    val suggestions: StateFlow<List<ContactSuggestion>> = _suggestions.asStateFlow()

    /** Suggest recipients for the last token in [fieldValue] (after the final comma/semicolon). */
    fun suggest(fieldValue: String) {
        val token = fieldValue.substringAfterLast(',').substringAfterLast(';').trim()
        if (token.length < 2) {
            _suggestions.value = emptyList()
            return
        }
        viewModelScope.launch {
            val recent = repo.suggestContacts(token, 6)
            // The address book is a content-provider query: off the main thread, since this runs on
            // every keystroke while the keyboard needs it.
            val device = if (contactsEnabled.value) {
                withContext(Dispatchers.IO) { AndroidContacts.query(getApplication(), token, 6) }
            } else {
                emptyList()
            }
            _suggestions.value = mergeSuggestions(recent, device, 6)
        }
    }

    fun clearSuggestions() {
        _suggestions.value = emptyList()
    }

    private var prepared = false
    // Threading headers for a reply (empty for new/forward).
    private var inReplyTo: List<String> = emptyList()
    private var references: List<String> = emptyList()
    /**
     * For a forward: the original carried verbatim to send time, appended below the user's note in
     * both alternatives so its formatting survives. Null for new/reply/replyAll.
     */
    private var forwarded: ForwardedBlocks? = null
    /** Account to send from: the replied-to message's account (unified inbox), else current. */
    private var accountId: String? = null

    /**
     * The saved draft being edited (#63): compose was opened from a message in Drafts. Sending or
     * re-saving replaces it — the old copy is destroyed once the new one is safely through — so
     * editing never leaves a duplicate; a failure leaves it untouched on the server.
     */
    private var editingDraftId: String? = null

    /**
     * The IMAP numbering [editingDraftId] belongs to, FROZEN at the moment that id was read (#99).
     *
     * An `UID EXPUNGE` only means anything inside one UIDVALIDITY: a repository that looked the value up
     * at execution compared a number recorded after a renumbering with itself, said "same", and expunged
     * another message in Drafts.
     *
     * Read ONCE and never re-read on the way out — re-reading is the defect itself. On JMAP it is
     * ALWAYS null and the draft is destroyed all the same.
     */
    private var editingDraftUidValidity: Long? = null

    /**
     * The account [editingDraftId] was read from, FROZEN in the same breath as its numbering (#63 × #31).
     *
     * The "From" picker offers the identities of EVERY account and switching it resets nothing about the
     * draft. Resolved at save time instead, the expunge left for a server that never issued the id and
     * the screen closed as a success either way — on JMAP the draft comes back on the next sync, on IMAP
     * two accounts whose Drafts share a UIDVALIDITY let it through in the WRONG mailbox.
     *
     * Frozen once and never looked up again: re-reading answers the "From" showing NOW.
     */
    private var editingDraftAccountId: String? = null

    /**
     * What the draft held WHEN IT WAS OPENED and this composer cannot put back: a genuine HTML body,
     * inline images or a calendar part, or a file attachment that failed to re-stage (#63).
     *
     * Read only by [editingDraftLossy] — never by a destroy route, which must see the OTHER source
     * too, and never by the save's WRITE guard: this term is true for every server draft reopened
     * with an HTML body, so weighed on a write it refused an erasure made on purpose.
     */
    private var editingDraftLossyOnOpen = false

    /**
     * …and what THIS COMPOSER has lost since, on the way into an activity's saved-state parcel
     * ([saveComposerResume]). Pushed here once, and never taken back down.
     * The path it closes is a plain activity recreation: the parcel lost the body while `applied` and
     * `subject` came back intact, so the editor is redrawn empty, the leave guard sees no change, and
     * one tap on a still-lit Save destroys the server copy.
     *
     * Read in TWO places: half of the licence to destroy (#63), and on its own the ONLY term that may
     * refuse to write an empty body over a stored one.
     */
    private var composerBodyWasLost = false

    /**
     * THE LICENCE TO DESTROY, and the only name every destroy route reads — eight read sites over
     * `destroyReplacedServerDraft`, `sendInternal`, `scheduleSend` and `saveDraft`. The
     * `composerBodyWasLost` argument beside the last is NOT a ninth: the body a save may refuse to
     * overwrite is not a question of licence to destroy.
     *
     * It has TWO sources, and either alone is a data loss. A computed `val`, not a `var`: as one
     * field, `prepare()`'s last statement would overwrite a lost-body flag on every draft reopen.
     */
    private val editingDraftLossy: Boolean get() = editingDraftLossyOnOpen || composerBodyWasLost

    /**
     * The composer could not park its body, and the text is gone. Raised from the screen, once,
     * and idempotent: there is no gesture, anywhere, that makes a lost body come back.
     */
    fun onComposerBodyLost() {
        composerBodyWasLost = true
    }

    /**
     * The row of `local_drafts` this composer holds a lease on (#95), and the account it belongs to.
     * Both are needed to give it back: every write to that table is scoped by `(accountId, id)`
     * because ids collide between two accounts of one server (#31).
     *
     * The id is PUBLISHED, exactly as [editingDraft] publishes the server row: it is the only honest
     * answer to "is there a local draft to delete". `draftOpenRoute(draftId) == LOCAL` is not — on a row
     * the upload worker consumed, the route still says LOCAL and the button would do nothing.
     * One holder, not a var beside a flow: two of them drift.
     */
    private val _editingLocalDraftId = MutableStateFlow<String?>(null)
    val editingLocalDraftId: StateFlow<String?> = _editingLocalDraftId.asStateFlow()
    private var editingLocalDraftAccountId: String? = null

    /**
     * The SERVER draft the reopened local row stands in for, and the numbering that id was frozen under
     * — read off the row at the open.
     *
     * Two fields of their own, and NOT [editingDraftId] / [editingDraftUidValidity]: that pair is what
     * a re-save and a send act on, and for a local draft it names the ROW — a server id there mints a
     * second row per save, and a numbering there licenses an expunge beside an id no server issued.
     * These two are read by the gestures that CONSUME the row and so lift the mask over the copy.
     */
    private var replacedServerDraftId: String? = null
    private var replacedServerDraftUidValidity: Long? = null

    /**
     * Which account the draft id this composer would carry was read under — handed to
     * [unlessDraftBelongsElsewhere] at the three places that write it somewhere durable.
     *
     * The choice between the two fields is [carriedDraftAccountId], in `core:data` and executed by a
     * JVM test, because it is the same choice [sendDraftTarget] makes; this is only the wiring, in ONE
     * place, since written out at each site a later edit fixes one and leaves the others carrying an id
     * across accounts. Not a field: a fourth copy could go stale against the id it describes.
     */
    private fun carriedDraftAccount(): String? = carriedDraftAccountId(
        editingDraftId = editingDraftId,
        serverDraftAccountId = editingDraftAccountId,
        localRowAccountId = editingLocalDraftAccountId,
    )

    /**
     * The saved draft behind this composer AS A MESSAGE — the cached row, ready to be deleted — or null
     * when there is none (#127).
     *
     * Not derived from [editingDraftId], which has no flow and no longer answers the same question:
     * since the reopen branch arms the id before the coroutine it is set on EVERY reopen, including one
     * whose fetch failed. This flow is set only where the draft was read AND the cache holds the row,
     * which is why Delete stays absent after a failed reopen.
     */
    private val _editingDraft = MutableStateFlow<Email?>(null)
    val editingDraft: StateFlow<Email?> = _editingDraft.asStateFlow()

    /**
     * Whether a draft saved on the SERVER is behind this composer — what the leave dialog may not
     * announce as destroyed (#35). Broader than [editingDraft], which is null unless the cache holds the
     * row: a draft that could not be read still exists in Drafts. The rule is [savedDraftBehindScreen].
     */
    private val _savedDraftBehind = MutableStateFlow(false)
    val savedDraftBehind: StateFlow<Boolean> = _savedDraftBehind.asStateFlow()

    /**
     * Whether the saved draft this composer was opened on is still being fetched — the window in which
     * the screen has no fields to show and must offer no action (`draftReopenView`).
     *
     * NOT a [ComposeState]: that is a single slot, so a `Loading` there would be wiped by the first
     * error and would wipe the error banner on its way in.
     *
     * Raised on the `draftId` path ONLY: a reply overwrites nothing on the server, so sending one while
     * its quote is still coming loses nothing.
     */
    private val _draftLoading = MutableStateFlow(false)
    val draftLoading: StateFlow<Boolean> = _draftLoading.asStateFlow()

    /**
     * The string id of what to say when the draft could not be read at all ([draftLoadNoticeFor]).
     *
     * Its real job is not the sentence: it is that the editor is NOT DRAWN while it is non-null. A blank
     * composer titled "Draft" cannot be told from a new message, and a save from it put a SECOND draft
     * on the server while the first stayed there, invisible.
     */
    private val _draftLoadFailed = MutableStateFlow<Int?>(null)
    val draftLoadFailed: StateFlow<Int?> = _draftLoadFailed.asStateFlow()

    /**
     * The draft to delete, handed over exactly once. Refuses while a send or a save is in flight
     * (INV-6): a send carries `draftEmailId` and destroys the original when it lands, so a delete racing
     * it would be a second destruction. Clears as it hands over, so a double tap cannot delete twice.
     */
    fun takeEditingDraft(): Email? {
        if (_state.value is ComposeState.Sending) return null
        return _editingDraft.value?.also { _editingDraft.value = null }
    }

    private fun credentials(): AccountCredentials? =
        (_selectedFrom.value?.accountId ?: accountId)?.let { store.credentials(it) } ?: store.load()

    /**
     * Every address that IS the user on this account: the login plus each of its identities. A
     * reply-all must exclude them all — an account with a second alias used to reply to itself,
     * because only the login was filtered (B5).
     */
    private fun selves(credentials: AccountCredentials): Set<String> =
        (listOf(credentials.username) + store.identities(credentials.id).map { it.email })
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * Reply/forward from the address the original was addressed to (#81): pre-select [accountId]'s
     * identity that appears in [original]'s To, else its Cc. Deliberately NOT a change to the reply-all
     * recipients — [selves] still excludes every address of the account (B5) — nor to the undo path.
     */
    private fun preselectReceivingIdentity(accountId: String, original: Email) {
        receivingFromOption(_fromOptions.value, accountId, original)?.let { option ->
            if (option != _selectedFrom.value) {
                _selectedFrom.value = option
                refreshPgp()
            }
        }
    }

    /** Upload a picked document and add it to the outgoing attachments. */
    fun attach(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            _attachmentStatus.value = getApplication<Application>().getString(R.string.status_attaching)
            try {
                val credentials = credentials() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                val resolver = app.contentResolver
                val type = resolver.getType(uri)
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                val bytes = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: error(getApplication<Application>().getString(R.string.status_read_file_failed))
                val part = stageOutgoing(credentials, bytes, type, name, disposition = "attachment", cid = null)
                _attachments.value = _attachments.value + part
                _attachmentsTouched.value = true
                _attachmentStatus.value = null
            } catch (t: Throwable) {
                // The exception text is a resolver/HTTP string; keep it in logcat, where diagnosis
                // needs it.
                Log.w(TAG, "Attachment failed", t)
                // Offline, the upload cannot go anywhere and the raw "Unable to resolve host …" told
                // the reader nothing they could act on. Say what happened in a sentence — but only
                // when connectivity confirms it; every other failure keeps its technical message.
                _attachmentStatus.value = if (isOfflineFailure(t, online = hasUsableNetwork(app))) {
                    app.getString(R.string.status_attach_offline)
                } else {
                    app.getString(R.string.status_attach_failed, t.message ?: "error")
                }
            }
        }
    }

    fun removeAttachment(part: EmailBodyPart) {
        _attachments.value = _attachments.value.filterNot { it == part }
        _attachmentsTouched.value = true
    }

    /**
     * Take off the list every attachment whose staged bytes are no longer there, and say so. Called as
     * the composer comes back to the front, because that is when it can have happened: Clear cache, or
     * Android evicting `cacheDir`. The DECISION is [sweepStagedAttachments].
     *
     * IT DOES NOT RUN AT ALL WHILE THIS COMPOSER HOLDS THE ONLY COPY OF SOMETHING, i.e. on a server
     * draft or a queued outbox item: both destructions are held back by a COUNT of what came through
     * (#63, #70), and a sweep that removes the part from the INPUT side makes those counts agree.
     * The reported symptom survives on those two paths, and that is the accepted price.
     */
    fun dropVanishedAttachments() {
        if (editingDraftId != null || editingOutboxId != null) return
        val app = getApplication<Application>()
        val sweep = sweepStagedAttachments(_attachments.value, File(app.cacheDir, "outgoing"), File::exists)
        if (sweep.gone.isEmpty()) return
        _attachments.value = sweep.kept
        _attachmentsTouched.value = true
        _attachmentStatus.value = app.getString(R.string.status_attach_vanished)
    }

    /**
     * Bind everything about a queued or undone message that is NOT the text of its fields — the two
     * callers differ exactly there. [resumeOutboxEdit], after a process death, calls this ALONE, since
     * the fields come back from the screen's `rememberSaveable`s and the body from the resume slot.
     * So no `_prefill` here, ever (OutboxResumeWiringTest).
     *
     * [options] are the identities [prepare] just listed: the send identity is a flow of this
     * ViewModel, dead with the process, so without this the message would leave under the default one.
     */
    private fun bindQueuedRow(d: SendOutbox.ComposeDraft, options: List<FromOption>) {
        _attachments.value = d.attachments
        inReplyTo = d.inReplyTo
        references = d.references
        // Reopening an undone forward: restore the carried original so it is still sent.
        if (d.forwardedText != null && d.forwardedHtml != null) {
            forwarded = ForwardedBlocks(d.forwardedText, d.forwardedHtml)
        }
        val match = options.firstOrNull {
            it.accountId == d.fromAccountId && it.identity.email == d.fromIdentityEmail
        } ?: options.firstOrNull { it.accountId == d.fromAccountId }
        if (match != null) _selectedFrom.value = match
        editingDraftId = d.draftEmailId
        // …with the numbering that id was frozen under, carried by the outbox row through the queue
        // and back (#99). Restored rather than re-read: looking it up again would answer the number a
        // sync recorded after any renumbering, which is what lets the expunge through on another
        // message. Null on a row queued before v23.
        editingDraftUidValidity = d.draftUidValidity
        // …and the ACCOUNT that pair belongs to. The outbox row has no column of its own for it and
        // is not getting one: `fromAccountId` answers for the draft too, because a queued row may
        // only carry a draft id belonging to the SAME account as itself. Restored, never re-read: the
        // identity on show can have been switched since.
        editingDraftAccountId = d.fromAccountId
        // The fidelity verdict travels WITH the id: before it did, undoing a send restored the id
        // while this flag fell back to false, and re-sending destroyed an original the first send had
        // deliberately spared.
        editingDraftLossyOnOpen = d.draftBodyIsLossy
        // The saved draft behind this screen (#35) follows the binding: settled at the head of
        // prepare() from the hand-over, which a composer rebuilt after a process death did not have.
        _savedDraftBehind.value = savedDraftBehindScreen(null, d.draftEmailId)
        // Restore the PGP mode the message carried (#35/#70): a signed item reopens signed, an undone
        // encrypted send reopens with the padlock on — never silently downgraded.
        d.pgpMode?.let { mode ->
            runCatching { PgpMode.valueOf(mode) }.getOrNull()?.let {
                _pgpMode.value = it
                pgpModeUserSet = true
                // Re-check OpenPGP now that the mode is back, instead of relying on the check started
                // above landing after this line: whether it has run depends on how far it got before
                // waiting for the provider, so a mode restored into an app that can no longer sign
                // survived or not by accident of timing. The drop, if any, is announced exactly once.
                refreshPgp()
            }
        }
        // Reopened from the outbox: its row waits in the queue marked EDITING until this composer
        // commits the message somewhere or hands it back (#70). An undone send carries no id — the
        // one case where the message only lives on this screen.
        _editingOutbox.value = d.editingOutboxId != null
        editingOutboxId = d.editingOutboxId
        _onlyCopy.value = d.editingOutboxId == null
    }

    /**
     * Take back, by [id], the queued row a composer rebuilt after a process death was opened on (#70).
     * Before this, that composer was DETACHED — `editingOutboxId` is only set from the in-memory
     * hand-over — so Send enqueued a SECOND copy beside the original the startup sweep had parked.
     *
     * The order is the whole of it and it is [resumeOutboxRow] (`OutboxResumeTest`): AFTER
     * `outboxRecovery`, so the row is FAILED when `takeOutboxForEdit` reads it. Fields are NOT
     * touched (A8). Residue, accepted: a Send tapped before this returns goes out without an id.
     */
    private suspend fun resumeOutboxEdit(id: Long) {
        val app = getApplication<Application>()
        val draft = resumeOutboxRow(id, app.container.outboxRecovery) { rowId ->
            runCatching { repo.takeOutboxForEdit(rowId, File(app.cacheDir, "outgoing")) }
                .onFailure { android.util.Log.w("SternaCompose", "couldn't resume the outbox item $rowId", it) }
                .getOrNull()
        } ?: return
        bindQueuedRow(composeDraftOf(draft), _fromOptions.value)
    }

    /**
     * Build initial fields when opening as a reply/reply-all/forward of [replyToId], or, when [to] is
     * given, as a fresh mail pre-addressed to it.
     *
     * @param quoteAlreadyOnScreen the composer ALREADY carries the quoted original, so neither of the
     * two sentences below may be produced. Only the screen knows it: after a process death this object
     * is new and everything here runs again, so the second pass would toast "the quote was not added"
     * over the quote itself.
     * @param outboxId the queued row this composer was opened on by Outbox → Edit (#70).
     */
    fun prepare(
        replyToId: String?,
        mode: String?,
        accountId: String? = null,
        restore: Boolean = false,
        to: String? = null,
        cc: String? = null,
        bcc: String? = null,
        subject: String? = null,
        body: String? = null,
        draftId: String? = null,
        quoteAlreadyOnScreen: Boolean = false,
        outboxId: Long? = null,
    ) {
        if (prepared) return
        prepared = true
        // Settle, for every way of opening the composer, whether a queued row is really parked behind
        // it (#96). The navigation argument survives the app being killed; the handed-over draft does
        // not, so `restore=true` with nothing in hand is an EMPTY composer and must be described as
        // one. Set here rather than in the restore branch alone so no path inherits a stale answer.
        _editingOutbox.value = holdsQueuedOutboxRow(restore, outbox.restored.value)
        // The OTHER thing that can survive this screen (#35): a draft saved on the server. Settled
        // here from the same two facts. Not in the branches below: the restore branch consumes
        // `outbox.restored`, so after it there is nothing left to ask.
        _savedDraftBehind.value =
            savedDraftBehindScreen(draftId, if (restore) outbox.restored.value?.draftEmailId else null)
        this.accountId = accountId
        val options = store.accounts().flatMap { acc ->
            store.identities(acc.id).map { FromOption(acc.id, it) }
        }
        _fromOptions.value = options
        val preferred = accountId ?: store.load()?.id
        // Prefer the preferred account's chosen default identity, then any identity of that account,
        // then the very first option. A reply/forward/restore path below overrides this.
        val defaultIdentityId = preferred?.let { store.defaultIdentityId(it) }
        _selectedFrom.value =
            options.firstOrNull { it.accountId == preferred && it.identity.id == defaultIdentityId }
                ?: options.firstOrNull { it.accountId == preferred }
                ?: options.firstOrNull()
        refreshPgp()

        // Reopening an undone send: restore every field the user had, including the
        // "From" identity, Cc/Bcc, and attachments, so nothing is lost.
        if (restore) {
            // Nothing handed over means the app was killed while this composer was open. With no id
            // in the route either — the Undo route after such a death — the fields stay empty and
            // nothing is guarded, since an empty screen has nothing to lose (#96). With an id, the
            // resume below reattaches the row once the startup sweep is over.
            val handed = outbox.restored.value
            handed?.let { d ->
                _prefill.value = DraftFields(
                    to = d.to, cc = d.cc, bcc = d.bcc, subject = d.subject, body = d.body,
                    // The styling comes back with the text (#131): held in memory for an undone send,
                    // parsed out of the queued row's html for an Outbox → Edit. Dropped here, undoing
                    // a send hands back a message with its bold removed, and the re-send delivers the
                    // removal.
                    bodyRanges = d.bodyRanges,
                    bodyBlocks = d.bodyBlocks,
                    bodyLinks = d.bodyLinks,
                    expand = d.cc.isNotBlank() || d.bcc.isNotBlank(),
                    // The box comes back ticked for a message that asked for a receipt (#70): this
                    // composer will enqueue a NEW row, so a clear box here cancels the request.
                    requestReceipt = d.requestReceipt,
                )
                bindQueuedRow(d, options)
            }
            outbox.consumeRestored()
            // After the consume, on purpose (OnlyCopyWiringTest pins the lambda against it), and on
            // viewModelScope: a composer popped before the sweep is over must not take a row that
            // nobody will release.
            outboxRowToResume(handed, outboxId)?.let { id -> viewModelScope.launch { resumeOutboxEdit(id) } }
            return
        }

        // Editing a saved draft: reopen it with every field it carried, and remember its id so sending
        // or re-saving replaces it instead of duplicating (#63).
        // Which STORE the id belongs to is a decision, not a null check, answered in core:data where
        // a JVM test executes it. Branching on `draftId != null` alone sent a `local-draft:` id into
        // repo.fetchEmail, which offline blocks on the connect timeout and then opens the composer
        // EMPTY over the only copy of the text (#95).
        when (draftOpenRoute(draftId)) {
            DraftOpenRoute.LOCAL -> {
                // Raised HERE, before the coroutine, and NOT on its first line — the SERVER arm's reason,
                // and it holds on a Room read too: set inside, the editor is on screen, editable, with
                // Save and Send, for whatever the dispatcher does with that body, and a save landing
                // there writes a SECOND draft. `viewModelScope` is Main.immediate today, so this
                // guards against that changing rather than reading it.
                _draftLoading.value = true
                // The `finally` is at THIS level, not inside prepareLocalDraft: that function leaves
                // by three bare `return`s as well as by any throw from Room, and a flag only one path
                // lowers is a spinner for good.
                viewModelScope.launch {
                    try { prepareLocalDraft(draftId!!) } finally { _draftLoading.value = false }
                }
                return
            }
            DraftOpenRoute.SERVER -> {
                // The name stays `draftId` below, deliberately: the numbering freeze, the id and the
                // lossy verdict of this branch are pinned line by line by four wiring rules.
                val draftId: String = draftId!!
                // Raised HERE, before the coroutine, and NOT on its first line: the flag is up before
                // the first suspension point, so before any frame the fetch could delay. Set inside,
                // the editor is on screen — editable, with Save, Send and the padlock — which is
                // exactly the window a fast finger types into. Same reservation as the LOCAL arm.
                _draftLoading.value = true
                // THE ID AND ITS VERDICT, ARMED AS ONE GESTURE — before the first suspension point.
                // The invariant is no longer "the id is laid LAST" but "the LICENCE TO DESTROY is laid
                // last". The id used to be the try's last statement, because a send tapped while the
                // attachment carry was downloading went out amputated (#63) — but `applied` is a
                // rememberSaveable, so after a kill the editor comes back with the typed text while
                // prepare() re-runs, and offline that statement is never reached, so Save writes a
                // SECOND draft. An id on its own destroys nothing now, the verdict being pessimistic.
                // It must be armed to `true`, not left at `false`: that default IS the destroy, an
                // unread draft's files counting 0 against 0. A site that cannot prove fidelity says true.
                editingDraftId = draftId
                // …AND THE ACCOUNT IT WAS READ UNDER, IN THE SAME BREATH: `unlessDraftBelongsElsewhere`
                // lets a NULL draft account THROUGH on purpose, so an id armed beside a null account
                // disarms that guard. Frozen after the fetch instead, every way the read can fail left
                // that pair standing — and since draft ids COLLIDE between two accounts of one server
                // (#31), a Save made after switching "From" overwrote B's own pending draft with A's
                // text.
                // Read with `credentials()`, which answers here what it answers below, `_selectedFrom`
                // being empty at the entry of prepare(). And frozen HERE ONLY: two writes of one field
                // a few lines apart is a value free to go stale.
                editingDraftAccountId = credentials()?.id
                editingDraftLossyOnOpen = true
                viewModelScope.launch {
                    try {
                        // No account to ask means the draft cannot be read either, so this path says
                        // so like any other failure. It used to fall through to a blank editable
                        // composer. Not "offline": the link may be perfect.
                        val credentials = credentials() ?: run {
                            _draftLoadFailed.value = draftLoadNoticeFor(offline = false)
                            return@launch
                        }
                        val draft = repo.fetchEmail(credentials, draftId)
                        // The row the Delete button would act on (#127), taken now, from the cache the
                        // Drafts list itself was drawn from. Only here: a draft that could not be read
                        // must not be offered a delete. Read scoped to this account (#31).
                        _editingDraft.value = runCatching { repo.cachedEmail(credentials.id, draftId) }.getOrNull()
                        // That same row is also the ONLY place a recipient the server dropped can
                        // still be found (#96), so the prefill is handed it — it fills an addressing
                        // field the server returned empty, never one the server filled.
                        _prefill.value = draftFieldsOf(draft, _editingDraft.value)
                        inReplyTo = draft.inReplyTo
                        references = draft.references
                        // The numbering the draft's id belongs to, frozen HERE and never read again:
                        // at save time it would be the number a refresh recorded after the folder was
                        // renumbered, and the expunge would land on another draft (#99). Read AFTER
                        // the id is armed, which is safe: what destroys is the PAIR (id + a CLEAN
                        // verdict), and the verdict is only lowered on a draft actually read.
                        editingDraftUidValidity = repo.recordedUidValidityForDraft(credentials, draftId)
                        // THE ACCOUNT IS NOT FROZEN HERE: it is armed at the entry, beside the id it
                        // describes. On this line it would be frozen only on the paths that reach it,
                        // and every failed reopen would leave an armed id with a null account.
                        // What this editor cannot give back on a re-save: an HTML body it cannot READ
                        // BACK, inline images and calendar parts, and the read receipt the draft asked
                        // for (D1). Every leg is read off the DRAFT just fetched.
                        // INTO A LOCAL, NOT INTO THE FIELD: written to the field, this line hands the
                        // screen a clean licence while the carry has not started, and a Send in that
                        // window goes out with NO attachments against a faithful verdict. That is #63.
                        val lossy = reopenedDraftIsLossy(
                            hasHtmlBody = draftHtmlIsLossy(draft.htmlContent()),
                            inlineImageCount = draft.inlineImageParts().size,
                            calendarPartCount = draft.calendarParts().size,
                            receiptHeader = draft.dispositionNotificationTo,
                        )
                        // Its own `val`, and NEVER `lossy || !carryDraftAttachments(…)`: `||`
                        // short-circuits, so a draft already judged lossy would never have its
                        // attachments carried at all. The carry runs on every reopen; only the
                        // ASSIGNMENT waits.
                        val carried = carryDraftAttachments(credentials, draft)
                        // THE LICENCE TO DESTROY, LAID LAST — the final statement of this branch, and
                        // the only place the pessimistic verdict armed on entry is lowered. Until this
                        // line runs, every Send, Save or empty-and-save tapped during the fetch AND
                        // during the carry keeps the original.
                        // It reads BOTH answers: `= lossy` alone would call a draft faithful whose
                        // files failed to download — the destroy of the only copy of them.
                        editingDraftLossyOnOpen = lossy || !carried
                    } catch (t: Throwable) {
                        // The draft could not be read. NOTHING is prefilled and, above all, no editor is
                        // drawn: a blank editable composer titled "Draft" cannot be told from a new
                        // message, and saving from it added a SECOND draft. The sentence is its own, not
                        // compose_prefill_failed, which already means "the quoted original is missing".
                        // The verdict line is redundant since it is armed `true` on entry — kept
                        // because it documents the path. It must no longer claim "nothing on this
                        // screen can save any more": `applied` survives process death.
                        editingDraftLossyOnOpen = true
                        val app = getApplication<Application>()
                        _draftLoadFailed.value = draftLoadNoticeFor(isOfflineFailure(t, online = hasUsableNetwork(app)))
                    } finally {
                        // EVERY way out of the body lowers it, not just the two above: the silent
                        // `return@launch` with no credentials, and any throw from outside the try,
                        // would otherwise leave the composer waiting on a fetch that is over.
                        _draftLoading.value = false
                    }
                }
                return
            }
            DraftOpenRoute.NONE -> Unit
        }

        // A fresh mail pre-addressed to a participant, or prefilled from a mailto: link
        // (Codeberg #15) — no original to fetch.
        if (!to.isNullOrBlank() || !cc.isNullOrBlank() || !bcc.isNullOrBlank() ||
            !subject.isNullOrBlank() || !body.isNullOrBlank()
        ) {
            // Launched, not inline: the delimiter setting lives in DataStore and reading it suspends.
            // The screen already applies the prefill from a LaunchedEffect.
            viewModelScope.launch {
                _prefill.value = DraftFields(
                    to = to.orEmpty(),
                    cc = cc.orEmpty(),
                    bcc = bcc.orEmpty(),
                    subject = subject.orEmpty(),
                    // A fresh mail, so it opens with the signature below whatever the link carried.
                    body = body.orEmpty() + signatureBlock(
                        signatureTextOf(selectedIdentity()),
                        settings.signatureDelimiter.first(),
                    ),
                    expand = !cc.isNullOrBlank() || !bcc.isNullOrBlank(),
                )
            }
            return
        }

        // A blank new message: it still opens with the signature in the body, where it can be read and
        // edited before sending. No draft/undo path reaches here — those return above with their own
        // body, which already contains the signature it was saved with.
        if (replyToId == null) {
            viewModelScope.launch {
                val block = signatureBlock(
                    signatureTextOf(selectedIdentity()),
                    settings.signatureDelimiter.first(),
                )
                if (block.isNotEmpty()) _prefill.value = DraftFields(to = "", subject = "", body = block)
            }
            return
        }
        viewModelScope.launch {
            val credentials = credentials() ?: return@launch
            // Parsed ONCE: the three branches below read this and never the raw string, so a mode one
            // of them does not know cannot open, in silence, a threaded quoted reply.
            val opening = composeOpening(mode)
            fun prefillFailed() {
                _attachmentStatus.value =
                    getApplication<Application>().getString(R.string.compose_prefill_failed)
            }

            // Cache-FIRST, and never blocked on the network: To/Subject of a reply come from the
            // original's headers, which the cached list row already holds. Offline, fetchEmail below
            // blocks on the connection timeout, so waiting on it to build the headers is exactly the
            // bug. Account-scoped lookup: the cache is keyed (accountId, id) and a bare id would hand
            // back a sibling sub-account's message (#31).
            val cached = runCatching { repo.cachedEmail(credentials.id, replyToId) }.getOrNull()
            if (cached != null) {
                // BEFORE the prefill: the identity decides which signature the body opens with, so the
                // alias must be picked first (#81). Since schema v21 the cached row carries To, Cc AND
                // Bcc; the second attempt below on the fetched original stays for rows cached before
                // that migration, which are not back-filled.
                preselectReceivingIdentity(credentials.id, cached)
                _prefill.value = buildPrefill(cached, mode, selves(credentials), quoteBody = false)
                if (opening.threads) {
                    // Threading ids aren't cached on the list row — the fetch below supplies the real
                    // ones; keep whatever the cache has meanwhile.
                    inReplyTo = cached.messageId
                    references = cached.references + cached.messageId
                }
            }

            // THEN, in the background, fetch the full original — only to enrich the body. This is the
            // call that stalls offline, which is why the headers above did not wait on it.
            val original = runCatching { repo.fetchEmail(credentials, replyToId) }.getOrNull()

            if (original == null) {
                // No full body available. The headers are already prefilled from the cache; either way,
                // say the original couldn't load so the missing quote is not a silent surprise.
                // Unless the quote is already on screen: the banner would be drawn on top of the very
                // thing it says could not be loaded (G6). Only the sentence is dropped.
                if (!quoteAlreadyOnScreen) prefillFailed()
                return@launch
            }

            // The fetched original has the complete To AND Cc, so it can settle the sending identity
            // the cached row could not (#81). Still before any body is rebuilt below.
            preselectReceivingIdentity(credentials.id, original)

            if (opening.carriesOriginal) {
                // A forward's editable body stays empty; the original is carried at send time. Its
                // "Fwd: …" subject came from the cache above — set it now only if nothing was cached.
                if (cached == null) _prefill.value = buildPrefill(original, mode, selves(credentials))
                runCatching { forwarded = buildForwarded(credentials, original) }
                    .onFailure { prefillFailed() }
                return@launch
            }

            if (opening.attachesSource) {
                // Forward as attachment: the original's exact server bytes, staged as ONE
                // message/rfc822 part. Empty body, no threading ids, and no buildForwarded.
                if (cached == null) _prefill.value = buildPrefill(original, mode, selves(credentials))
                // Say so while the source is on its way, as attach() does: the composer is sendable
                // before the part is, and a "Fwd:" with an empty body must never look finished.
                _attachmentStatus.value = getApplication<Application>().getString(R.string.status_attaching)
                runCatching {
                    // The attachment ceiling, restated on what actually came back. Over JMAP the
                    // download already refuses a blob over it; over IMAP the parser drains a literal
                    // over its own 32 MiB into an EMPTY source, which rawSource refuses. So this line
                    // is reached only if one of those two stops holding.
                    val bytes = repo.rawSource(credentials, replyToId)
                    DownloadLimits.enforce(bytes.size.toLong(), DownloadLimits.ATTACHMENT_MAX_BYTES)
                    stageOutgoing(
                        credentials, bytes, type = "message/rfc822",
                        name = safeFileName(original.subject, "message") + ".eml",
                        disposition = "attachment", cid = null,
                    )
                }.onSuccess { part ->
                    // In a straight line, NOT through attach(): that one arms _attachmentsTouched, and
                    // the composer would open "dirty" — a discard box on the way back without a key
                    // typed (#70).
                    _attachments.value = _attachments.value + part
                    _attachmentStatus.value = null
                }.onFailure { t ->
                    Log.w(TAG, "Forward as attachment: source not staged", t)
                    if (t is ContentTooLargeException) {
                        _attachmentStatus.value =
                            getApplication<Application>().getString(R.string.status_attachment_too_large)
                    } else {
                        prefillFailed()
                    }
                }
                return@launch
            }

            // Reply / reply-all: take the real threading ids from the fetched original, then hand the
            // quoted body to the screen out-of-band via [replyQuote] so it lands WITHOUT touching the
            // To/Subject already on screen. When nothing was cached, emit the full prefill instead.
            inReplyTo = original.messageId
            references = original.references + original.messageId
            if (cached == null) {
                _prefill.value = buildPrefill(original, mode, selves(credentials))
            } else if (!quoteAlreadyOnScreen) {
                // The whole body, not just the quote: the screen swaps it in wholesale and must keep the
                // signature the cache-first prefill put there.
                // Not a second time. After a process death the user may have typed under the restored
                // quote, so handing it over again toasts "quoted message not added" over a body that
                // carries it (G5). An `else if` so the emission stays the one line the lint pins.
                _replyQuote.value = replyBody(quote(original, resolveOutgoingDateZone(settings.quotedDatesUtc)))
            }
        }
    }

    /**
     * Reopen one of THIS PHONE's own drafts — a row of `local_drafts` the server has not got (#95).
     * Nothing on this path may touch the network or read the cache: the id names a row no server has
     * heard of, and offline `fetchEmail` blocks on the connect timeout. The row IS the draft, and
     * [localDraftPrefill] projects it — a pure function a JVM test executes field by field.
     *
     * The row is taken out of the upload worker's way while this composer holds it, or the network
     * coming back mid-edit uploads the draft and deletes the row AND its attachments. A row that is
     * not there is SAID on [_draftLoadFailed], not swallowed by a bare `?: return`.
     */
    private suspend fun prepareLocalDraft(id: String) {
        val credentials = credentials()
        if (credentials == null) {
            noticeLocalDraftNoAccount()
            return
        }
        // The lease is taken, remembered and — if this screen is popped while the take is still out
        // — GIVEN BACK BY THIS COROUTINE, under the cancellation shelter the decision carries. Armed
        // after the call instead, the id was never remembered when viewModelScope died inside it: the
        // row stayed EDITING and the draft reached the server only after the next cold start (#95).
        val lease = takeLocalDraftEditOrGiveItBack(
            id = id,
            take = { repo.takeLocalDraftForEdit(credentials.id, it) },
            // Remembered synchronously, and BEFORE any field is written: the back gesture can arrive
            // at the very next frame. Both halves, because ids collide between two accounts of one
            // server (#31) and an unscoped release hands ANOTHER account's open row to the worker.
            arm = { taken ->
                editingLocalDraftAccountId = credentials.id
                _editingLocalDraftId.value = taken.id
            },
            giveBack = { runCatching { repo.releaseLocalDraftEdit(credentials.id, it) } },
        )
        val row = when (lease) {
            // The row is gone — the worker consumed it between the list being drawn and the tap, or
            // the user threw it away. Nothing will bring it back, so the sentence must not ask for
            // anything.
            LocalDraftEdit.Gone -> {
                noticeLocalDraftGone()
                return
            }
            // The row is there and something else really owns it right now: its files are being
            // staged, or the worker is uploading it. A different sentence, because it asks for
            // something that WORKS: close this screen and open it again.
            // An EDITING row is NOT here: it is this phone's own stale lease, taken back above.
            LocalDraftEdit.Busy -> {
                noticeLocalDraftBusy()
                return
            }
            is LocalDraftEdit.Taken -> lease.row
        }
        val fields = localDraftPrefill(row)
        _prefill.value = DraftFields(
            to = fields.to,
            cc = fields.cc,
            bcc = fields.bcc,
            subject = fields.subject,
            body = fields.body,
            // …and the styling the row stored, out of the SAME projection as the text.
            bodyRanges = fields.bodyRanges,
            bodyBlocks = fields.bodyBlocks,
            bodyLinks = fields.bodyLinks,
            expand = fields.expand,
        )
        _attachments.value = fields.attachments
        inReplyTo = fields.inReplyTo
        references = fields.references
        // The address the draft was written as, so a delegated sub-account's draft reopens under the
        // identity it will send as and not under the login's (#31).
        fields.fromEmail?.let { written ->
            _fromOptions.value.firstOrNull {
                it.accountId == credentials.id && it.identity.email.equals(written, ignoreCase = true)
            }
        }?.let {
            _selectedFrom.value = it
            refreshPgp()
        }
        // The fidelity verdict travels WITH the id, as for an undone send: `false` here would launder
        // a body the composer knows it cannot reproduce, and the next send would destroy the original
        // this row was written to spare (#63).
        editingDraftLossyOnOpen = fields.bodyIsLossy
        // The row's OWN id: `localDraftTarget` looks a `local-draft:` id up by id, so a re-save lands
        // on this very row. The server draft it stands in for is NOT armed here — a local id names
        // nothing a server can destroy — so [editingDraftUidValidity] stays null, and
        // [editingDraftAccountId] with it: that one says which server hears about an id read off a
        // server, and this id was read off this phone.
        editingDraftId = fields.editingDraftId
        // The server draft this row stands in for, carried to the gestures that consume the row,
        // since each of those lifts the mask it holds over that copy. Kept apart from the pair above:
        // this id is not what a re-save lands on, and its numbering must never end up beside an id the
        // server did not issue.
        replacedServerDraftId = fields.replacedServerDraftId
        replacedServerDraftUidValidity = fields.replacedServerDraftUidValidity
        // Armed from the two terms the row carries, at the one place they are read, so the delete
        // confirmation can say BEFORE the tap that the copy on the server stays (#95).
        // `addressingIsProvenEmpty = true` is NOT an assertion that the original was copied to nobody:
        // establishing that means a blocking server read in front of the gesture that exists because
        // there was no network. The gap is ONE-WAY — this dialog only ever ADDS a line, so `true` can
        // make it withhold that line for a copy the destruction keeps, never promise a copy stays when
        // the gesture is about to destroy it. Widened, not doubled, since the composer can lose its
        // body: deliberately not closed here, as recomputing would put a second writer on the flow.
        _deleteKeepsServerCopy.value = emptiedLocalDraftKeepsServerCopy(
            fields.replacedServerDraftId,
            fields.bodyIsLossy,
            addressingIsProvenEmpty = true,
        )
    }

    /**
     * The user emptied a draft this phone was keeping, and saved: destroy the SERVER draft it stood in
     * for, then take the row away (#95 × #69).
     * Consuming the row alone is the defect: while it exists the server draft is masked out of Drafts,
     * and once it goes the mask lifts and the pre-edit text comes back at the top, permanently. The
     * mask is not total for a server draft that represents a conversation.
     *
     * The destroy carries the folder and the FROZEN numbering, through `destroyDurably` and not
     * `schedule`, which takes the account's unique work slot with `REPLACE`.
     */
    private suspend fun destroyReplacedServerDraft(localDraftId: String) {
        // The account the ROW was leased under, and never the one being written as. Handed
        // `credentials()`, this destroyed under whichever account the "From" picker shows: the lookup,
        // the destroy and the eviction all left for a server that never issued that id and failed in
        // silence, while the row was taken away correctly — mask lifted, pre-edit draft back at the top
        // of Drafts. No fallback: throwing destroys nothing, and both callers catch it.
        val credentials = credentialsDestroyingReplacedServerDraft(
            leasedUnderAccountId = editingLocalDraftAccountId,
            composingAsAccountId = _selectedFrom.value?.accountId ?: accountId,
            lookup = { store.credentials(it) },
        ) ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
        val serverDraft = replacedServerDraftId
        val plan = planForEmptiedLocalDraft(
            replacedServerDraftId = serverDraft,
            // Frozen when the row was written, carried, never re-read (#99).
            replacedServerDraftUidValidity = replacedServerDraftUidValidity,
            mailboxId = serverDraft?.let { repo.draftMailboxOf(credentials, it) },
            bodyIsLossy = editingDraftLossy,
            // Read here, like `mailboxId` above, because establishing it means asking the server while
            // the decision stays pure. Under the account the ROW was leased under. The two guards in
            // front of the read are not a second decision: each skips a question whose answer cannot
            // reach the outcome and hands over `false`, "not proven". Unlike `mailboxId` this read
            // leaves the phone, and unguarded it would block the one gesture that exists because there
            // was no network (#95).
            addressingIsProvenEmpty = serverDraft != null && !editingDraftLossy &&
                repo.emptiedDraftAddressingIsProvenEmpty(credentials, serverDraft),
        )
        destroyThenConsumeEmptiedLocalDraft(
            localDraftId = localDraftId,
            plan = plan,
            destroy = { MessageDestroyWorker.destroyDurably(getApplication(), credentials.id, listOf(it)) },
            evict = { ids -> repo.evictAll(credentials.id, ids) },
            consume = { id -> consumeEditingLocalDraft(id) },
            tell = { _notices.tryEmit(R.string.compose_emptied_draft_server_copy_kept) },
        )
    }

    /**
     * Delete the draft this phone is KEEPING, from the composer that holds it (#95 × #127).
     * Not a second way to destroy anything: a deletion asked for IS the emptying —
     * [destroyReplacedServerDraft] reached by a button, under the same proofs.
     *
     * The acts: the lease is READ, not blanked, since blanking it leaves the row `EDITING` with nobody
     * to release it on a failure; the row goes back to the upload worker only when nothing was
     * destroyed; and the screen closes on [localDraftDeleted], never on `Done`, which lands on the
     * inbox. Stated debt: the failure banner is the SAVE one.
     */
    fun deleteEditingLocalDraft() {
        val id = heldEditingLocalDraft() ?: return
        _state.value = ComposeState.Sending
        viewModelScope.launch {
            var destroyed = false
            try {
                destroyReplacedServerDraft(id)
                destroyed = true
                _localDraftDeleted.tryEmit(Unit)
            } catch (t: Throwable) {
                _state.value = ComposeState.Error(t.message ?: t.javaClass.simpleName, whileSaving = true)
            } finally {
                localDraftLeaseAfterDelete(_editingLocalDraftId.value, destroyed)?.let { releaseLocalDraftEdit(it) }
            }
        }
    }

    /**
     * The lease on this phone's own draft row as this composer holds it — read, never cleared. Who
     * gets the row back is decided at the END of the gesture ([localDraftLeaseAfterDelete]) and cannot
     * be decided on a field this function has emptied.
     *
     * It refuses twice, both the useful half of [takeEditingDraft]'s one-shot (#127): during a send,
     * which consumes this very row (INV-6), and on a lease the close has already handed back.
     */
    private fun heldEditingLocalDraft(): String? {
        if (_state.value is ComposeState.Sending) return null
        return _editingLocalDraftId.value
    }

    /**
     * Say a draft of the PHONE's own store could not be opened because no account resolved (#95).
     * No new string and no new meaning: `compose_draft_load_failed` is what the SERVER reopen answers
     * for the same cause, and it is not "offline".
     *
     * The dominant case is transitory and reopening works, but [credentials] also answers null when the
     * store is empty — a deleted or signed-out account — where the sentence invites a gesture that
     * returns nothing. Stated debt: a tenth string across nine locales was too dear for a path this
     * narrow. On [_draftLoadFailed], the flow [draftReopenView] turns into `DeadEnd`.
     */
    private fun noticeLocalDraftNoAccount() {
        _draftLoadFailed.value = R.string.compose_draft_load_failed
    }

    /**
     * Say the draft the phone was keeping is not on the phone any more (#95) — the upload worker consumed
     * it between the list being drawn and the tap, or it was thrown away.
     *
     * The one new string of this branch, and it had to be new: `compose_draft_load_failed` asks for
     * something that CANNOT work, `compose_draft_offline` would accuse an unrelated network, and
     * `compose_prefill_failed` already means "the quoted original is missing".
     * On [_draftLoadFailed], for its neighbours' reason.
     */
    private fun noticeLocalDraftGone() {
        _draftLoadFailed.value = R.string.compose_local_draft_gone
    }

    /**
     * Say the draft is on the phone but not this composer's to open right now — its files are being
     * staged, or the worker is uploading it. An `EDITING` row is NOT one of these: it is this phone's
     * own stale lease, taken back rather than refused.
     *
     * A DIFFERENT sentence from [noticeLocalDraftGone]'s and no new string: `compose_draft_load_failed`
     * is exactly true here, both states being transitory. On `_draftLoadFailed`, so the editor is not
     * drawn: a blank composer with a live Save is the "second draft on the server" that flow ended.
     */
    private fun noticeLocalDraftBusy() {
        _draftLoadFailed.value = R.string.compose_draft_load_failed
    }

    /**
     * Take the reopened local draft away — its row AND its staged files — because the composer has just
     * committed the message somewhere else (#95).
     *
     * Never on its own: on the send routes it is [commitThenConsumeLocalDraft]'s `consume`, so it runs
     * strictly after the outbox row exists. Left undone, the row stays at the top of Drafts and the
     * worker files a copy of an already-sent message in the server's Drafts.
     */
    private suspend fun consumeEditingLocalDraft(id: String) {
        val account = editingLocalDraftAccountId ?: return
        _editingLocalDraftId.value = null
        repo.consumeLocalDraft(account, id)
    }

    /**
     * Leaving the composer without committing the message anywhere. A message reopened from the outbox
     * has two ways out, and [body] — the RAW editor text at the close, never through `bodiesForSend` —
     * decides which: the ordinary close gives it straight back to the queue (#70), while a close over a
     * body this composer LOST ([closingParksQueuedRow]) PARKS it instead, because giving it back re-arms
     * it at 0 and the message leaves in under a second, when the screen has just said to reopen it.
     *
     * App-scoped: the caller pops this screen right after, which kills [viewModelScope].
     */
    fun abandon(body: String, emptying: Boolean = false) {
        // All three answers are decided in one place, in values, and executed here (#95 × #70). A send
        // in flight gives back NOTHING — it owns the message and consumes both rows itself — and
        // outside one the local row is independent. `abandonPlan` is executed in SendDraftTargetTest.
        val plan = abandonPlan(
            sending = _state.value is ComposeState.Sending,
            emptying = emptying,
            parkOutbox = closingParksQueuedRow(composerBodyWasLost, body),
            localDraftId = _editingLocalDraftId.value,
            outboxId = editingOutboxId,
        )
        plan.localDraftToRelease?.let { releaseLocalDraftEdit(it) }
        // The row never left the queue — it only sat in EDITING — so giving it back is flipping its
        // state to QUEUED and re-arming delivery. Nothing is rebuilt, nothing can be lost (#70).
        plan.outboxToRelease?.let { id ->
            editingOutboxId = null
            _editingOutbox.value = false
            appScope.launch {
                runCatching { repo.releaseOutboxEdit(id) }
                    .onFailure { android.util.Log.w("SternaCompose", "couldn't release the edited outbox item", it) }
            }
        }
        // The other door, and NEVER releaseOutboxEdit: parked FAILED with its reason, off the
        // worker, reopenable.
        plan.outboxToPark?.let { id ->
            editingOutboxId = null
            _editingOutbox.value = false
            appScope.launch {
                runCatching { repo.parkInterruptedOutboxEdit(id) }
                    .onFailure { android.util.Log.w("SternaCompose", "couldn't park the interrupted outbox item", it) }
            }
        }
    }

    /**
     * Give back the lease taken by [prepareLocalDraft] (#95): the row goes from EDITING to PENDING and
     * its upload is re-armed. The repository refuses to touch a row in any other state.
     *
     * App-scoped, not as a precaution: this runs as the screen is popped and `viewModelScope` dies
     * with it. [id] is HANDED IN by [abandonPlan]: read off the field here, the plan's decision not to
     * release anything during a send could be undone by a call slipping past it.
     */
    private fun releaseLocalDraftEdit(id: String) {
        val account = editingLocalDraftAccountId ?: return
        _editingLocalDraftId.value = null
        appScope.launch {
            runCatching { repo.releaseLocalDraftEdit(account, id) }
                .onFailure { android.util.Log.w("SternaCompose", "couldn't release the edited local draft", it) }
        }
    }

    /**
     * Consume the queued row this composer was editing (#70): the message has just been committed
     * elsewhere (sent, scheduled, or saved as a draft), so its EDITING row is dropped rather than
     * given back to the queue — otherwise the same message would go out twice.
     */
    private suspend fun consumeEditingOutbox() {
        val id = editingOutboxId ?: return
        editingOutboxId = null
        _editingOutbox.value = false
        runCatching { repo.deleteOutbox(id) }
    }

    /**
     * Park the queued row this composer was editing in [state] (#95 × #70) — the other end of
     * [consumeEditingOutbox], for a save that put the message where the row's own copy is not.
     *
     * Leaving the row as the composer found it (EDITING) was the defect: no screen shows an EDITING
     * row, and the next launch hands it back to the queue and delivers the pre-edit text.
     *
     * App-scoped, for [abandon]'s reason. The id is read and cleared SYNCHRONOUSLY, before the launch,
     * so ordering against [consumeEditingOutbox] does not depend on coroutine scheduling.
     */
    private fun parkEditingOutbox(state: OutboxState) {
        val id = editingOutboxId ?: return
        editingOutboxId = null
        _editingOutbox.value = false
        appScope.launch {
            runCatching { repo.parkOutboxEdit(id, state) }
                .onFailure { android.util.Log.w("SternaCompose", "couldn't park the edited outbox item", it) }
        }
    }

    /**
     * Queue the message in the persistent outbox with a hold-back window (Undo-send): validate +
     * capture now, close the screen, and let the outbox worker deliver it a few seconds later
     * (with auto-retry) unless the user undoes it. The row survives the app being killed.
     */
    fun send(to: String, cc: String, bcc: String, subject: String, body: RichBody, requestReceipt: Boolean) =
        sendInternal(SendArgs(to, cc, bcc, subject, body, requestReceipt), interactionResult = null)

    /**
     * The composer's field values, kept so a PGP interaction round-trip can retry the send. The
     * read-receipt choice rides here too: that round-trip re-enters [sendInternal] with THIS record and
     * nothing else, so a choice left out of it is dropped from every signed or encrypted send.
     */
    private data class SendArgs(
        val to: String,
        val cc: String,
        val bcc: String,
        val subject: String,
        val body: RichBody,
        val requestReceipt: Boolean,
    )

    private var pendingSendArgs: SendArgs? = null

    /**
     * Continue a send paused on [ComposeState.PgpInteraction]. [interactionResult]
     * is the provider dialog's result Intent, or null when the user cancelled.
     */
    fun retryPgpSend(interactionResult: Intent?) {
        val args = pendingSendArgs
        pendingSendArgs = null
        if (args == null || interactionResult == null) {
            _state.value = ComposeState.Idle
            return
        }
        sendInternal(args, interactionResult)
    }

    /** Thrown inside a send when the OpenPGP provider needs the user first. */
    private class PgpInteractionNeeded(val pendingIntent: PendingIntent) : Exception()

    private fun sendInternal(args: SendArgs, interactionResult: Intent?) {
        // A body this composer LOST never leaves, and never takes the only copy of the text with it
        // (#95): the row in `local_drafts` under an offline draft is the ONLY place that text exists,
        // and the commit below destroys it. The decision is executed in `LostBodySendTest`.
        // `args.body.text`, NEVER the `textBody` of `bodiesForSend`, which carries the signature and
        // is never blank. ABOVE the re-entry guard, so nothing is flagged `Sending` for a refused
        // send. `holdsStoredDraft` takes ALL THREE stores: "type it again" is the one instruction
        // that DESTROYS the text on every route this protects.
        val lostBody = lostBodySendWording(
            bodyWasLost = composerBodyWasLost,
            body = args.body.text,
            holdsStoredDraft = _editingLocalDraftId.value != null || editingDraftId != null || editingOutboxId != null,
        )
        if (lostBody != null) {
            _notices.tryEmit(
                when (lostBody) {
                    LostBodyWording.REOPEN_DRAFT -> R.string.compose_body_lost_reopen_draft
                    LostBodyWording.RETYPE -> R.string.compose_body_lost_cannot_send
                },
            )
            return
        }
        if (_state.value is ComposeState.Sending) return
        _state.value = ComposeState.Sending
        val (to, cc, bcc, subject, body) = args
        viewModelScope.launch {
            try {
                val credentials = credentials() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                val recipients = parseAddrs(to)
                require(recipients.isNotEmpty()) { getApplication<Application>().getString(R.string.status_add_recipient) }
                val ccList = parseAddrs(cc)
                val bccList = parseAddrs(bcc)
                val identity = selectedIdentity()
                val (textBody, htmlBody) = bodiesForSend(body, identity)
                val attachments = _attachments.value
                val replyTo = inReplyTo
                val refs = references
                // Sign/encrypt NOW, while the provider can still show UI — the outbox worker runs
                // headless later. The outbox then only ever holds the signed/encrypted entity.
                val mode = _pgpMode.value
                val pgpEntity = if (mode != PgpMode.OFF) {
                    try {
                        buildPgpEntity(
                            credentials, recipients + ccList + bccList,
                            textBody, htmlBody, attachments, mode,
                            protectedSubject(mode, subject), interactionResult,
                        )
                    } catch (e: PgpInteractionNeeded) {
                        pendingSendArgs = args
                        _state.value = ComposeState.PgpInteraction(e.pendingIntent)
                        return@launch
                    }
                } else {
                    null
                }
                // Which server draft this send replaces, and under which numbering — ONE answer for
                // both, and the same one for the outbox row and for the record an undone send reopens
                // from. For a draft reopened off this phone's store the composer holds the ROW's id,
                // which names nothing a server can destroy, and without the copy named on the row the
                // send unmasks it (#95).
                // …and NOT AT ALL when that draft was read under another account (#31 × #63): no
                // transaction spans two servers, and carried, the id would expunge a bare number in B's
                // own Drafts.
                val sendTarget = sendDraftTarget(
                    editingDraftId = editingDraftId,
                    editingDraftUidValidity = editingDraftUidValidity,
                    replacedServerDraftId = replacedServerDraftId,
                    replacedServerDraftUidValidity = replacedServerDraftUidValidity,
                ).unlessDraftBelongsElsewhere(draftAccountId = carriedDraftAccount(), writingAccountId = credentials.id)
                // Persist the send held for the undo window; the worker delivers it after.
                // And ONLY THEN take the phone's own draft row away (#95). The order is grid rule 1:
                // that row is the only copy of the text and the line below is what makes a second one.
                // Left behind, it stays at the top of Drafts after the message has gone, and the next
                // launch re-arms it — the worker then files a copy of an already-sent message.
                val id = commitThenConsumeLocalDraft(
                    localDraftId = _editingLocalDraftId.value,
                    commit = {
                        repo.enqueueSend(
                            credentials, recipients, subject, textBody, replyTo, refs,
                            attachments, htmlBody, identity?.name, identity?.email, ccList, bccList,
                            holdMs = SendOutbox.HOLD_MS,
                            pgpMode = mode.takeIf { it != PgpMode.OFF },
                            prebuiltEntity = pgpEntity,
                            // Editing a saved draft (#63): once this send is delivered the worker
                            // destroys the draft it came from. Never one of this phone's own ids,
                            // which names nothing a server could be asked to destroy.
                            draftEmailId = sendTarget.emailId,
                            // …under the numbering frozen when that id was read (#99), off the SAME
                            // answer as the id: the two halves are meaningful only together. Persisted
                            // with the row because the destroy runs at DELIVERY, and the folder may
                            // have been renumbered by then.
                            draftUidValidity = sendTarget.uidValidity,
                            // — and ONLY against proof: the composer's verdict rides along, and
                            // enqueueSend leaves the id off the row when it cannot show the send
                            // reproduces the draft. The message still goes out; the original survives
                            // in Drafts instead of being destroyed behind an amputated send.
                            bodyIsLossy = editingDraftLossy,
                            // Asked for on this screen, written at DELIVERY time by a headless worker —
                            // so it has to be on the row, not in this process.
                            requestReceipt = args.requestReceipt,
                        )
                    },
                    consume = { local -> consumeEditingLocalDraft(local) },
                )
                // The edits are queued now under this fresh row, so the row taken out for editing is
                // consumed — dropped from the queue rather than given back (#70).
                consumeEditingOutbox()
                // Keep the raw draft so undoing the send can reopen compose with it intact.
                val draft = SendOutbox.ComposeDraft(
                    to = to, cc = cc, bcc = bcc, subject = subject, body = body.text,
                    // The styling as the composer held it — NOT re-parsed from the html that went out,
                    // which carries the signature substituted verbatim and would not parse back (#131).
                    bodyRanges = body.ranges,
                    bodyBlocks = body.blocks,
                    bodyLinks = body.links,
                    fromAccountId = _selectedFrom.value?.accountId,
                    fromIdentityEmail = identity?.email,
                    attachments = attachments, inReplyTo = replyTo, references = refs,
                    forwardedText = forwarded?.text, forwardedHtml = forwarded?.html,
                    pgpMode = mode.takeIf { it != PgpMode.OFF }?.name,
                    // The same answer as the row above, and deliberately the same VALUE: undoing this
                    // send reopens the composer from THIS record, whose local row has already been
                    // consumed, so the server draft it stood in for can only be named here now.
                    draftEmailId = sendTarget.emailId,
                    // Undoing this send reopens the composer from THIS record: without the frozen
                    // numbering the reopened message could no longer destroy the draft it replaces.
                    draftUidValidity = sendTarget.uidValidity,
                    draftBodyIsLossy = editingDraftLossy,
                    requestReceipt = args.requestReceipt,
                )
                val app = getApplication<Application>()
                // WYSIWYG (#70): every send is queued behind the undo window, and the message only
                // leaves once the worker submits it. So never claim "sent" here: say "Sending…" when
                // there is a connection to hand it to, and "queued" when offline. This stays honest
                // under a VPN killswitch too — the real outcome surfaces via the Outbox.
                val queuedOffline = !hasUsableNetwork(app)
                outbox.hold(
                    label = app.getString(
                        if (queuedOffline) R.string.status_message_queued
                        else R.string.status_message_sending,
                    ),
                    draft = draft,
                ) {
                    // Undo within the window: drop the queued row so nothing is sent and nothing stays
                    // parked. Delete the row first, then cancel its worker; should the cancel be a
                    // no-op, a later run finds no row and exits.
                    repo.deleteOutbox(id)
                    Outbox.cancel(app, id)
                }
                _state.value = ComposeState.Done
            } catch (t: Throwable) {
                _state.value = ComposeState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * Schedule the message to be sent at [sendAtMillis]. Persisted to Room and fired by WorkManager.
     * Attachments are not carried in v1.
     *
     * Answers whether the gesture was ACCEPTED and nothing more, which is what the screen needs:
     * announced unconditionally, its toast reads "Scheduled — Mon 1 Sep, 09:00" over a refused message.
     */
    fun scheduleSend(
        to: String,
        cc: String,
        bcc: String,
        subject: String,
        body: RichBody,
        sendAtMillis: Long,
        requestReceipt: Boolean,
    ): Boolean {
        // A scheduled send is handed to a headless worker that can neither sign nor encrypt (so any
        // PgpMode but OFF would go out unsigned, #35) and its table carries no attachments yet (so it
        // would send amputated, A2/A3). The toolbar disables the button for both; this is the choke
        // point. Only the pgp case has user-facing wording — attachments cannot reach here.
        if (!scheduleSendAllowed(_pgpMode.value, _attachments.value.isNotEmpty())) {
            if (_pgpMode.value != PgpMode.OFF) _notices.tryEmit(R.string.compose_pgp_no_draft)
            return false
        }
        // …and the same refusal as the immediate send, for the same reason: a scheduled send commits
        // just as truly and consumes the phone's own draft row on the way out. `body.text` is this
        // function's own raw parameter; the `textBody` computed below carries the signature.
        val lostBody = lostBodySendWording(
            bodyWasLost = composerBodyWasLost,
            body = body.text,
            holdsStoredDraft = _editingLocalDraftId.value != null || editingDraftId != null || editingOutboxId != null,
        )
        if (lostBody != null) {
            _notices.tryEmit(
                when (lostBody) {
                    LostBodyWording.REOPEN_DRAFT -> R.string.compose_body_lost_reopen_draft
                    LostBodyWording.RETYPE -> R.string.compose_body_lost_cannot_send
                },
            )
            return false
        }
        if (_state.value is ComposeState.Sending) return false
        _state.value = ComposeState.Sending
        viewModelScope.launch {
            try {
                val credentials = credentials() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                val recipients = parseAddrs(to)
                require(recipients.isNotEmpty()) { getApplication<Application>().getString(R.string.status_add_recipient) }
                val identity = selectedIdentity()
                val (textBody, htmlBody) = bodiesForSend(body, identity)
                // The same one answer as the send above: id and numbering together, the server draft
                // named off the row when this composer was reopened from one (#95), then withheld
                // WHOLE when the composer cannot show the message reproduces the draft (#63). The
                // scheduled row has nowhere to say so, so the id is the carrier.
                val sendTarget = sendDraftTarget(
                    editingDraftId = editingDraftId,
                    editingDraftUidValidity = editingDraftUidValidity,
                    replacedServerDraftId = replacedServerDraftId,
                    replacedServerDraftUidValidity = replacedServerDraftUidValidity,
                ).unlessBodyIsLossy(editingDraftLossy)
                    // …and withheld again, WHOLE, when the draft belongs to another account than the
                    // one this row is written under: a different question from the verdict above. The
                    // row carries `accountId = credentials.id`, so an id of A on it fires against B's
                    // server at the hour chosen.
                    .unlessDraftBelongsElsewhere(draftAccountId = carriedDraftAccount(), writingAccountId = credentials.id)
                // Same order as the send above (#95): the scheduled row is written and its worker
                // booked FIRST, and only then does the phone's own draft row go.
                commitThenConsumeLocalDraft(
                    localDraftId = _editingLocalDraftId.value,
                    commit = {
                        val id = repo.insertScheduledSend(
                            ScheduledSendEntity(
                                accountId = credentials.id,
                                recipients = recipients.joinToString(","),
                                cc = parseAddrs(cc).joinToString(",").ifBlank { null },
                                bcc = parseAddrs(bcc).joinToString(",").ifBlank { null },
                                subject = subject,
                                textBody = textBody,
                                htmlBody = htmlBody,
                                fromName = identity?.name,
                                fromEmail = identity?.email,
                                inReplyTo = inReplyTo.joinToString(" ").ifBlank { null },
                                references = references.joinToString(" ").ifBlank { null },
                                sendAtMillis = sendAtMillis,
                                // A scheduled send of an edited draft still replaces it (#63), but this
                                // row cannot carry the fidelity verdict, so the id itself is the
                                // carrier: written only when the verdict is clean, withheld otherwise.
                                // ScheduledSendWorker relies on exactly this. And never one of this
                                // phone's own ids, which names nothing to destroy.
                                draftEmailId = sendTarget.emailId,
                                // The row waits for hours, so the frozen numbering waits with it (#99):
                                // read at the hour the send fires it would be the number recorded after
                                // a renumbering, and the expunge would land on another draft.
                                draftUidValidity = sendTarget.uidValidity,
                                // The row waits for hours; the request has to wait with it, or the
                                // hand-over to the outbox drops it.
                                requestReceipt = requestReceipt,
                            ),
                        )
                        ScheduledSends.enqueue(getApplication(), id, scheduledSendDelayMillis(sendAtMillis, System.currentTimeMillis()))
                    },
                    consume = { local -> consumeEditingLocalDraft(local) },
                )
                // The message now waits in the scheduled table instead; the outbox row it came from is
                // consumed rather than given back, or it would send twice (#70).
                consumeEditingOutbox()
                _state.value = ComposeState.Done
            } catch (t: Throwable) {
                _state.value = ComposeState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
        // Accepted — the coroutine above owns what happens next. A failure inside it lands on
        // `ComposeState.Error`; this answer is about the GESTURE.
        return true
    }

    /**
     * The outgoing (text, html) bodies, carrying exactly what the composer shows — the signature is
     * already inside it, so nothing is appended (WYSIWYG). An HTML alternative is ALWAYS produced, since
     * a lone text/plain body is subject to format=flowed reflow by some servers; an imported HTML
     * signature is substituted while its plain block is untouched; and the INLINE styling (#131) goes
     * into the html alternative only, the text one being `toPlainText` — never `userBody.text`.
     *
     * Which part actually leaves the phone is not decided here: `buildBodyEntity` emits a SINGLE body
     * and knows no `multipart/alternative`, so only a plain JMAP send carries BOTH.
     */
    private suspend fun bodiesForSend(userBody: RichBody, identity: StoredIdentity?): Pair<String, String?> {
        val html = htmlBodyWithSignature(
            userBody, signatureTextOf(identity), signatureHtmlOf(identity),
            settings.signatureDelimiter.first(),
        )
        val fwd = forwarded ?: return toPlainText(userBody) to html
        return "${toPlainText(userBody)}\n\n${fwd.text}" to "$html<br><br>${fwd.html}"
    }

    /**
     * Carry a reopened draft's file attachments back into compose (#63), staged like a forward's; inline
     * images are not carried. Best-effort per part, and it returns whether EVERY part came across — a
     * partial carry means a re-save cannot reproduce the draft, so the original must survive it.
     */
    private suspend fun carryDraftAttachments(credentials: AccountCredentials, o: Email): Boolean {
        val parts = o.fileAttachmentParts()
        val staged = mutableListOf<EmailBodyPart>()
        for (part in parts) {
            runCatching {
                val bytes = repo.downloadAttachment(credentials, part, o.id)
                stageOutgoing(credentials, bytes, part.type, part.name, disposition = "attachment", cid = null)
            }.getOrNull()?.let { staged += it }
        }
        if (staged.isNotEmpty()) _attachments.value = _attachments.value + staged
        return staged.size == parts.size
    }

    /**
     * A draft is worth persisting only if it carries real content (#69). A wholly empty compose does
     * not count. Delegates to [draftHasContent] so this save gate and the toolbar's greyed-out Save
     * icon share one rule.
     */
    private fun hasDraftContent(
        to: String,
        cc: String,
        bcc: String,
        subject: String,
        body: String,
    ): Boolean = draftHasContent(to, cc, bcc, subject, body, _attachments.value.isNotEmpty())

    fun saveDraft(to: String, cc: String, bcc: String, subject: String, body: RichBody, requestReceipt: Boolean) {
        // A save is the CAUTIOUS gesture, and on one route it was the destructive one: a queued message
        // reopened by Outbox → Edit and redrawn empty is saved as an empty draft, and
        // consumeEditingOutbox() then deletes the queued row and its durable attachments — the only copy
        // of the text, while the send route on this same screen was telling her to reopen the message.
        // HERE and not lower: everything below writes. The RAW `body`, never one through
        // bodiesForSend. And NOT lostBodySendWording, which answers what a SEND owes.
        if (lostBodySaveDestroysQueuedRow(bodyWasLost = composerBodyWasLost, body = body.text, holdsQueuedRow = editingOutboxId != null)) {
            _notices.tryEmit(R.string.compose_body_lost_reopen_draft)
            return
        }
        // The plaintext guarantee, enforced where the upload happens (#35): an encrypted message never
        // leaves a readable copy on the server, draft included. The toolbar and the leave dialog
        // already refuse, so this is belt-and-braces — but it is the one choke point every save route
        // goes through. Refuse and say so: the composer stays open with the text intact.
        if (!draftSaveAllowed(_pgpMode.value)) {
            _notices.tryEmit(R.string.compose_pgp_no_draft)
            return
        }
        // Empty by the #69 rule: persist nothing. A brand-new compose leaves no trace at all; an opened
        // draft the user has emptied has its original deleted so no empty shell lingers in Drafts.
        if (!hasDraftContent(to, cc, bcc, subject, body.text)) {
            val original = editingDraftId
            // The numbering frozen when that id was read — carried, never re-read (#99).
            val originalUidValidity = editingDraftUidValidity
            // …and the ACCOUNT both were read from, captured with them and for the same reason: the
            // expunge below is addressed to it, never to the identity the "From" picker is showing —
            // that picker covers every account (#31 × #63).
            val originalAccountId = editingDraftAccountId
            // The phone's own row is handed back to NOBODY on this close, and the "nobody" is said to
            // abandon() rather than by blanking the field: abandon() would hand the row back PENDING and
            // RE-ARM its upload, racing the pre-edit text to the server, while blanking the field left a
            // failed submit() with the row EDITING and nobody to release it.
            // Nothing is being saved, so a message reopened from the outbox goes back to the queue (#70)
            // — lost and empty, parked rather than queued.
            // Its TEXT and not the RichBody: abandon() asks one question, read off the characters.
            abandon(emptying = true, body = body.text)
            if (original == null) {
                _state.value = ComposeState.Done
            } else if (draftOpenRoute(original) == DraftOpenRoute.LOCAL) {
                // NOT repo.discardDraft: for an id no server ever issued the IMAP route destroys
                // NOTHING, and the screen closed as a success while the emptied draft went up on the next
                // network. Its row and its staged files go together instead (#95 × #69) — and so does
                // the SERVER draft it stood in front of, which the row's removal would otherwise unmask.
                // And submit()'s credentials are NOT passed on: they belong to the "From" on show.
                submit(to) { _, _ -> destroyReplacedServerDraft(original) }
            } else {
                // …and the SERVER route asks the same question, because it is the same draft and the
                // same expunge: a draft never read WHOLE keeps its server copy (emptiedServerDraftIsKept).
                // "Empty" here is draftHasContent's answer, which counts neither an inline image nor a
                // calendar part, so a draft made of one inline image is DRAWN empty. The word is owed and
                // is emitted INSIDE the lambda: submit() reaches Done after this returns.
                // …and the same of the ADDRESSING, the term both routes were deciding without: the copy
                // about to be expunged is the only thing still holding a Cc never displayed.
                // And submit()'s credentials are NOT what it goes out under — hence `{ _, _ ->`.
                submit(to) { _, _ ->
                    // The account the draft was READ FROM, resolved here and nowhere else. `?:
                    // credentials` appended is the whole defect back: an id JMAP's destroyAll spares, or an
                    // IMAP number expunged in the OTHER account's Drafts when the two UIDVALIDITYs agree.
                    // Gone account: this throws, submit() catches it, and NOTHING is destroyed.
                    // A local fun and NOT a val computed up front: for a draft never read whole the copy
                    // is kept whatever the account says, and resolving a removed account would turn that
                    // true notice into a "could not save" banner.
                    fun destroyingCredentials(): AccountCredentials =
                        credentialsDestroyingEmptiedServerDraft(
                            openedUnderAccountId = originalAccountId,
                            composingAsAccountId = _selectedFrom.value?.accountId ?: accountId,
                            lookup = { store.credentials(it) },
                        ) ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                    // NOT a decision taken here — the decision is the function below, and this is the
                    // term it reads. `!editingDraftLossy &&` skips a server read whose answer cannot
                    // change the outcome, and what it hands over when it skips is `false`, "not
                    // proven". Under the account the draft was OPENED from: `credentials` here would
                    // read the addressing of another server.
                    val addressingIsProvenEmpty = !editingDraftLossy &&
                        repo.emptiedDraftAddressingIsProvenEmpty(destroyingCredentials(), original)
                    if (emptiedServerDraftIsKept(editingDraftLossy, addressingIsProvenEmpty = addressingIsProvenEmpty)) {
                        _notices.tryEmit(R.string.compose_emptied_draft_server_copy_kept)
                    } else {
                        repo.discardDraft(destroyingCredentials(), original, originalUidValidity)
                    }
                }
            }
            return
        }
        submit(to) { credentials, recipients ->
            // Keep a reply draft threaded, carry the attachments the chips are showing into the saved
            // copy, and replace the draft being edited (#63) rather than piling up a copy per save —
            // unless the copy cannot hold everything the original did, in which case the repository
            // keeps the original and says so here. Carry the composer's chosen From so a delegated
            // sub-account's draft is saved under the identity it will send as (#31).
            val identity = selectedIdentity()
            // What this save REPLACES: the id frozen at the open and its numbering, as ONE pair (#99) —
            // and NOTHING AT ALL when that pair was read under another account (#31 × #63). Unlike every
            // other account mismatch the destroy cannot be re-addressed: the new copy is created on the
            // account being written as while the original stays where it was, and no transaction spans
            // two servers. So the original is kept and the duplicate is the price. Both halves come out
            // of the SAME answer.
            val replaces = SendDraftTarget(emailId = editingDraftId, uidValidity = editingDraftUidValidity)
                .unlessDraftBelongsElsewhere(draftAccountId = carriedDraftAccount(), writingAccountId = credentials.id)
            val outcome = repo.saveDraft(
                // `toPlainText(body)` and never `body.text` (#131): the text part is the alternative
                // a client with no HTML shows, and it is the ONE plain-text answer the send already
                // uses. `body.text` here stores a draft whose text part has the list markers nowhere
                // and every URL missing, while the message built from the same body has both.
                credentials, recipients, subject, toPlainText(body),
                // The styling as a SECOND part, decided by the one pure function every route uses
                // (`draftHtmlToSave`): null for a body with no styling, so a plain draft is stored
                // exactly as before #131. NOT `htmlBodyWithSignature`, which is the SEND's html — it
                // substitutes an imported signature verbatim and would not parse back on reopen.
                html = draftHtmlToSave(body),
                cc = parseAddrs(cc), bcc = parseAddrs(bcc),
                inReplyTo = inReplyTo, references = references,
                replacesEmailId = replaces.emailId,
                replacesUidValidity = replaces.uidValidity,
                attachments = _attachments.value,
                bodyIsLossy = editingDraftLossy,
                composerBodyWasLost = composerBodyWasLost,
                // The emptiness travels APART from the text, because the text no longer answers it:
                // `toPlainText(body)` of an empty body carrying one bullet is `"- "`, which is not
                // blank. Judged there, the lost-body guard stands down and `"- "` is written over the
                // only copy of a local draft. `body.text` is what the user TYPED.
                typedBodyIsBlank = body.text.isBlank(),
                fromName = identity?.name, fromEmail = identity?.email,
                requestReceipt = requestReceipt,
            )
            // Which outcomes are worth a word is decided in the data layer and EXECUTED there
            // (draftSaveNeedsNotice): a draft kept on the phone because the network was down is a
            // successful save (#95).
            if (draftSaveNeedsNotice(outcome)) {
                _notices.tryEmit(R.string.compose_draft_original_kept)
            }
            // A queued row the save did NOT put on the server is PARKED, and parked FIRST: left as
            // the composer found it (EDITING) it is on no screen at all, and the next launch hands it
            // back to the queue and sends the pre-edit message with no undo window — after the user
            // tapped "save as draft". Before the consume, which clears editingOutboxId (#95 × #70).
            draftSaveParksTheQueuedRowAs(outcome)?.let { parkEditingOutbox(it) }
            // The queued row it came from is consumed — its row AND its staged files — only once the
            // draft has really reached Drafts (draftSaveConsumesTheQueuedRow). Unconditional, a save
            // made with no network destroyed the only copy the user could still see (#95 × #70).
            if (draftSaveConsumesTheQueuedRow(outcome)) consumeEditingOutbox()
        }
    }

    private inline fun submit(
        to: String,
        crossinline op: suspend (AccountCredentials, List<String>) -> Unit,
    ) {
        if (_state.value is ComposeState.Sending) return
        _state.value = ComposeState.Sending
        viewModelScope.launch {
            // The success as a VALUE, not as a position in the try: what the `finally` below does with
            // the row depends on whether `op` came back.
            var saved = false
            try {
                val credentials = credentials() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                op(credentials, parseAddrs(to))
                saved = true
                _state.value = ComposeState.Done
            } catch (t: Throwable) {
                // Saving a draft, not sending: the error banner must say so (#63 — a failed draft save
                // used to read "couldn't send", pointing users at the wrong step).
                _state.value = ComposeState.Error(t.message ?: t.javaClass.simpleName, whileSaving = true)
            } finally {
                // A save gives the phone's own row back too, and until this line nothing did
                // (#95 × #31). `abandon()` is the only other release and only the CANCEL calls it, this
                // route leaving on Done on the understanding that the save rewrote the row — which it did,
                // unless the "From" picker was moved to another account: a NEW row is then minted and the
                // reopened one stays EDITING for ever.
                // HERE and not in saveDraft's lambda: submit()'s three callers are all in saveDraft, so
                // abandonPlan(sending = true)'s risk cannot arise at this point.
                localDraftLeaseAfterSave(_editingLocalDraftId.value, saved)?.let { releaseLocalDraftEdit(it) }
            }
        }
    }

    /**
     * Initial fields for a reply/reply-all/forward of [original]. [quoteBody] is false when the full body
     * could not be loaded: the headers are still prefilled from the cached row, but the quoted body is
     * skipped rather than quoting the truncated preview. Ignored for a forward.
     */
    private suspend fun buildPrefill(
        original: Email,
        mode: String?,
        selves: Collection<String>,
        quoteBody: Boolean = true,
    ): DraftFields {
        // Resolved by SUSPENDING on the stored setting (#120), like the signature settings: a composer
        // opened moments after launch must not fall back to the default while DataStore is still
        // opening — with the switch on, that beat would write local time.
        val zone = resolveOutgoingDateZone(settings.quotedDatesUtc)
        val opening = composeOpening(mode)
        return when (opening) {
            // Same fields for both forwards: what differs (carried inline vs. attached as .eml)
            // happens in prepare(), after this prefill.
            ComposeOpening.FORWARD, ComposeOpening.FORWARD_ATTACHMENT -> DraftFields(
                to = "",
                subject = withPrefix(original.subject, opening.subjectPrefix),
                // The editable body starts empty (just the user's note, plus the signature when the
                // setting is on); the original is carried separately to send time so its formatting
                // survives. See [buildForwarded].
                body = replyBody(""),
            )
            ComposeOpening.REPLY_ALL -> DraftFields(
                to = replyAllRecipients(original, selves),
                subject = withPrefix(original.subject, opening.subjectPrefix),
                body = replyBody(if (quoteBody) quote(original, zone) else ""),
                // Travels with the body above and says exactly what that body holds: when the original
                // was not cached, THIS is how the quote reaches the screen, so it is the only thing
                // that can let the composer keep quiet about a quote it is showing after a process
                // death.
                quoted = quoteBody,
            )
            ComposeOpening.REPLY -> DraftFields(
                to = replyRecipient(original),
                subject = withPrefix(original.subject, opening.subjectPrefix),
                body = replyBody(if (quoteBody) quote(original, zone) else ""),
                // Same claim as the reply-all above, and `quoteBody` and not `true`: when the full body
                // could not be loaded the quote was skipped, and claiming otherwise would silence a
                // failure the reader must hear about.
                quoted = quoteBody,
            )
        }
    }

    /**
     * The quoted original for a reply: a localised attribution line with the date formatted for the
     * device, then the original prefixed with "> " — cut at the sender's signature delimiter, so a long
     * exchange does not accumulate one signature per round (D4).
     *
     * [zone] is the clock the attribution speaks ([resolveOutgoingDateZone], #120), a parameter WITHOUT a
     * default: a default here would let a forgotten caller leak the device's time zone silently.
     */
    private fun quote(o: Email, zone: ZoneId): String {
        val app = getApplication<Application>()
        val sender = o.from.firstOrNull()?.display() ?: app.getString(R.string.compose_quote_someone)
        val date = MailDates.formatFull(o.receivedAt, zone)
        val attribution = app.getString(R.string.compose_quote_attribution, date, sender)
        val quoted = quotedOriginalText(o).lineSequence().joinToString("\n") { deepenQuote(it) }
        return "\n\n$attribution\n$quoted"
    }

    /**
     * Prebuild the forwarded-original blocks (text + cleaned html) carried to send time, and re-stage the
     * original's inline images and attachments. Inline images keep their Content-ID so the forwarded
     * HTML's `<img cid:>` resolves; one whose image fails to download becomes "[image]" rather than broken.
     */
    private suspend fun buildForwarded(credentials: AccountCredentials, o: Email): ForwardedBlocks {
        val carriedCids = mutableSetOf<String>()
        val staged = mutableListOf<EmailBodyPart>()

        for (part in o.inlineImageParts()) {
            val cid = part.cid?.trim()?.trim('<', '>')?.takeIf { it.isNotBlank() } ?: continue
            val outPart = runCatching {
                val bytes = repo.downloadAttachment(credentials, part, o.id)
                stageOutgoing(credentials, bytes, part.type, part.name, disposition = "inline", cid = cid)
            }.getOrNull()
            // Carry the image only if it staged; otherwise its cid stays out of [carriedCids] so
            // cleanForwardedHtml neutralises that specific image.
            if (outPart != null) {
                staged += outPart
                carriedCids += cid
            }
        }
        val fileParts = o.fileAttachmentParts()
        var carried = 0
        for (part in fileParts) {
            runCatching {
                val bytes = repo.downloadAttachment(credentials, part, o.id)
                stageOutgoing(credentials, bytes, part.type, part.name, disposition = "attachment", cid = null)
            }.getOrNull()?.let { staged += it; carried++ }
        }
        if (staged.isNotEmpty()) _attachments.value = _attachments.value + staged
        // A12 (data loss): a file that would not download used to be dropped without a word, so the
        // forward opened looking complete while it was short of what the original carried. Say so
        // rather than sending an amputated forward silently.
        if (carried < fileParts.size) _notices.tryEmit(R.string.compose_forward_attachment_dropped)

        val app = getApplication<Application>()
        return buildForwardedBlocks(
            from = o.from.joinToString { it.display() },
            subject = o.subject.orEmpty(),
            date = MailDates.formatFull(o.receivedAt, resolveOutgoingDateZone(settings.quotedDatesUtc)),
            to = o.to.joinToString { it.display() },
            // The whole original is forwarded, signature included: it is the message being passed on,
            // not a quote of it.
            originalText = originalPlainText(o),
            originalHtml = o.htmlContent()?.takeIf { it.isNotBlank() },
            carriedCids = carriedCids,
            labels = ForwardLabels(
                from = app.getString(R.string.compose_from),
                subject = app.getString(R.string.compose_subject),
                date = app.getString(R.string.compose_forward_date),
                to = app.getString(R.string.compose_to),
            ),
        )
    }

    /**
     * Stage outgoing-attachment bytes the same way for a picked file or a carried forward part: IMAP
     * writes a cache temp file, JMAP uploads a blob. [disposition] is "inline" with a [cid] for a
     * carried inline image, else "attachment".
     */
    private suspend fun stageOutgoing(
        credentials: AccountCredentials,
        bytes: ByteArray,
        type: String?,
        name: String?,
        disposition: String,
        cid: String?,
    ): EmailBodyPart {
        val app = getApplication<Application>()
        // IMAP has no blob store; and with PGP on, JMAP also stages locally — the attachment travels
        // INSIDE the signed/encrypted entity, so uploading a plaintext blob would leak it for nothing.
        return if (credentials.protocol == MailProtocol.IMAP || _pgpMode.value != PgpMode.OFF) {
            val safe = (name ?: "attachment").replace(Regex("[^A-Za-z0-9._-]"), "_")
            val file = withContext(Dispatchers.IO) {
                File(app.cacheDir, "outgoing").apply { mkdirs() }
                    .let { File(it, "${System.nanoTime()}-$safe") }
                    .apply { writeBytes(bytes) }
            }
            EmailBodyPart(
                partId = file.absolutePath,
                type = type,
                size = bytes.size.toLong(),
                name = name,
                disposition = disposition,
                cid = cid,
            )
        } else {
            repo.uploadAttachment(credentials, bytes, type, name, disposition, cid)
        }
    }

    /**
     * Build the PGP/MIME entity: assemble the inner MIME entity with the same builder normal sends use,
     * then detached-sign or encrypt to all recipients + self — the split is [pgpEncryptArgs] and nothing
     * else. Throws [PgpInteractionNeeded] when the provider wants the user first.
     *
     * [mode] is a PARAMETER, and `_pgpMode.value` must never be read in here: the send captures the
     * mode ONCE, and read again it could answer something else, since `pgp.findKeys` suspends on a
     * round-trip and the mode moves under it — the message going out UNSIGNED while the composer said
     * "encrypted and signed", decided by which coroutine resumed first.
     */
    private suspend fun buildPgpEntity(
        credentials: AccountCredentials,
        allRecipients: List<String>,
        textBody: String,
        htmlBody: String?,
        attachments: List<EmailBodyPart>,
        mode: PgpMode,
        protectedSubject: String?,
        interactionResult: Intent?,
    ): String {
        val app = getApplication<Application>()
        val account = pgpAccount()
            ?: error(app.getString(R.string.compose_pgp_not_configured))
        // NAMED FOR THE ROLE THAT SURVIVES BOTH MODES. This key is the signer AND a recipient
        // (encrypt-to-self, so the Sent copy can be reopened). Calling it "signKeyId" is what makes
        // "drop the signer" read as "drop this key", which would make every unsigned encrypted message
        // unreadable for ever. See [pgpEncryptArgs].
        val selfKeyId = account.pgpSignKeyId

        // Attachment bytes must be local to ride inside the entity. Staged files read back directly; a
        // blob attached BEFORE the user enabled PGP is fetched back once.
        val outAttachments = attachments.map { part ->
            val bytes = when {
                part.partId != null -> withContext(Dispatchers.IO) { File(part.partId!!).readBytes() }
                part.blobId != null -> repo.downloadAttachment(credentials, part, emailId = "")
                else -> error(app.getString(R.string.status_read_file_failed))
            }
            OutgoingAttachment(
                name = part.name ?: "attachment",
                type = part.type ?: "application/octet-stream",
                bytes = bytes,
                cid = part.cid,
                inline = part.disposition.equals("inline", true) && !part.cid.isNullOrBlank(),
            )
        }
        require(outAttachments.sumOf { it.bytes.size.toLong() } <= PGP_MAX_ATTACHMENT_BYTES) {
            app.getString(R.string.compose_pgp_too_large)
        }
        val boundarySeed = java.util.UUID.randomUUID().toString().replace("-", "")
        // The subject goes in TWICE, and only one of them is this one. The message's real `Subject:`
        // is written by `OutgoingMime.build` on the envelope, in the clear — the `subject = ""` below
        // is this throwaway carrier's own. What [protectedSubject] adds is a copy INSIDE the bytes
        // about to be encrypted (RFC 9788 § 3), so a recipient can tell the subject they were shown is
        // the subject that was sent. Null on SIGN and on a blank subject.
        val inner = OutgoingMime.buildBodyEntity(
            OutgoingMessage(
                from = "-", to = listOf("-"), subject = "",
                body = textBody, html = htmlBody,
                messageId = "$boundarySeed@pgp", dateMillis = System.currentTimeMillis(),
                attachments = outAttachments,
            ),
            protectedSubject = protectedSubject,
        )
        val payload = PgpMime.signablePayload(inner)
        return when (mode) {
            PgpMode.SIGN -> {
                when (val r = pgp.detachedSign(payload.toByteArray(Charsets.UTF_8), selfKeyId, interactionResult)) {
                    is PgpResult.Success ->
                        PgpMime.wrapSigned(payload, r.value.armor, r.value.micalg, "----sterna_sig_$boundarySeed")
                    is PgpResult.UserInteractionRequired -> throw PgpInteractionNeeded(r.pendingIntent)
                    is PgpResult.Error -> error(r.message)
                    PgpResult.NotAvailable -> error(app.getString(R.string.message_pgp_no_provider))
                }
            }
            PgpMode.ENCRYPT, PgpMode.ENCRYPT_UNSIGNED -> {
                val recipientKeys = when (val r = pgp.findKeys(allRecipients, interactionResult)) {
                    is PgpResult.Success -> r.value
                    is PgpResult.UserInteractionRequired -> throw PgpInteractionNeeded(r.pendingIntent)
                    is PgpResult.Error -> error(app.getString(R.string.compose_pgp_missing_keys))
                    PgpResult.NotAvailable -> error(app.getString(R.string.message_pgp_no_provider))
                }
                // Who signs and who can read — [pgpEncryptArgs], the pure decision a JVM test runs.
                // selfKeyId is a recipient in BOTH modes; it is the signer only in ENCRYPT. Do not
                // rebuild either array here.
                val args = pgpEncryptArgs(mode, selfKeyId, recipientKeys.toList())
                when (val r = pgp.signAndEncrypt(payload.toByteArray(Charsets.UTF_8), args.signKeyId, args.recipientKeyIds, interactionResult)) {
                    is PgpResult.Success ->
                        PgpMime.wrapEncrypted(r.value, "----sterna_enc_$boundarySeed")
                    is PgpResult.UserInteractionRequired -> throw PgpInteractionNeeded(r.pendingIntent)
                    is PgpResult.Error -> error(r.message)
                    PgpResult.NotAvailable -> error(app.getString(R.string.message_pgp_no_provider))
                }
            }
            PgpMode.OFF -> error("unreachable")
        }
    }

    private companion object {
        const val TAG = "ComposeViewModel"

        /** v1 cap: the whole entity is signed/encrypted in memory. */
        const val PGP_MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024
    }
}
