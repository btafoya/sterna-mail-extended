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
import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.Span
import app.sterna.core.data.text.draftHtmlIsLossy
import app.sterna.core.data.text.draftHtmlToSave
import app.sterna.core.data.text.safeFileName
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
    /**
     * The inline styling [body] carries (#131) — empty for every opening that has none, which is
     * a reply, a forward, a mailto: link and any draft written before #131 or written plain.
     *
     * Beside [body] and not inside it, exactly as the composer holds it: the screen keeps the text
     * in a `TextFieldValue` and lays the spans over it, so the ~70 sites reading `body.text` see
     * what they always saw. ⛔ A route that reopens a styled body and drops this is not showing
     * "no styling yet": it is showing the styling REMOVED, and the next save writes that removal.
     */
    val bodyRanges: Map<Inline, List<Span>> = emptyMap(),
    /** Reveal the Cc/Bcc row (used when restoring a draft that had them). */
    val expand: Boolean = false,
    /**
     * Tick the "ask for a read receipt" box (RFC 8098) — set when reopening a message that already
     * asked for one: an undone send, or a queued item taken back out of the Outbox.
     *
     * It travels with the other initial field values because the box IS one of them: the composer
     * owns it in a `rememberSaveable`, exactly like the recipients and the body, so it survives a
     * process death that this ViewModel does not. ⛔ It is NOT filled when a draft is reopened from
     * the server: the header is not read back (assumed debt D1), and claiming otherwise here would
     * tick a box for a request the message may not carry.
     */
    val requestReceipt: Boolean = false,
    /**
     * This body ALREADY carries the quoted original: true only for the reply/reply-all prefill
     * built on the path where NOTHING was cached, the one that puts the quote straight inside
     * [body] instead of handing it over out of band through `replyQuote`.
     *
     * The screen has to be told, because the screen is what survives. After a process death this
     * ViewModel is brand new, `prepared` is an ordinary field and [prepare] runs again, while the
     * composer comes back with its body, its baseline and its `applied` restored from
     * `rememberSaveable`. On the uncached path no out-of-band hand-over ever runs, so nothing else
     * could raise the screen's flag: left unsaid, the rebuilt ViewModel draws "Couldn't load the
     * original message." over the quote that is plainly on screen.
     *
     * ⛔ The `false` default is what leaves every OTHER opening exactly as it was — a reopened
     * draft, a mailto: link, an undone send, a queued item taken back out of the Outbox, and a
     * forward, whose editable body carries no quote at all (its original travels separately).
     */
    val quoted: Boolean = false,
)

/** A "From" choice: one identity belonging to a specific account. */
data class FromOption(val accountId: String, val identity: StoredIdentity)

/**
 * What a "From" switch asks of the body's signature (D5). Two cases, deliberately distinct:
 * the identity being left had a signature, so its block is [Swap]ped where it is still intact (and
 * an edited or deleted one is left alone); or it had none, so nothing can have been deleted and the
 * new identity's signature is [Insert]ed where the prefill would have put it.
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
     * The id of the queued row this composer was opened on (#70), if any. The row stays in the
     * outbox marked EDITING while it is held here; committing the message somewhere — a fresh send,
     * a schedule, a saved draft — consumes it, and committing it nowhere gives it back to the queue.
     */
    private var editingOutboxId: Long? = null

    /**
     * Whether a queued row is actually parked behind this composer — the observable face of
     * [editingOutboxId], for the two things the screen has to get right: the title ("Edit"), and the
     * leave dialog, which must say the message goes BACK to the queue rather than claim it is being
     * destroyed (#96/#70).
     *
     * Seeded from the handed-over draft rather than from `false`, because the screen reads it on its
     * very first frame while [prepare] only runs after it: the reopen puts the draft in the handle
     * before navigating, so the title is right from the start instead of flipping a frame later.
     * Nothing handed over (the app was killed and the in-memory draft died with it) reads false,
     * which is the truth — there is no row in front of the user and nothing to edit. [prepare] then
     * settles it for every way of opening the composer, so the seed is never the last word — and
     * neither is [prepare]'s: a composer rebuilt after a process death with a row id in its route
     * takes the row back once the startup sweep is over ([resumeOutboxEdit]), and [bindQueuedRow]
     * flips this to true at that moment, title and leave dialog with it.
     *
     * The seed asks [holdsQueuedOutboxRow] with `restore = true` because that argument has not
     * reached the ViewModel yet: it answers "IF this is a restore, is a row behind it?". Safe on its
     * own — [composeTitle] re-tests `restore`, and [prepare] overwrites this a moment later with the
     * real one, before any tap can reach the leave dialog.
     */
    private val _editingOutbox = MutableStateFlow(holdsQueuedOutboxRow(true, outbox.restored.value))
    val editingOutbox: StateFlow<Boolean> = _editingOutbox.asStateFlow()

    private val _onlyCopy = MutableStateFlow(false)
    /**
     * True when the message on screen exists nowhere else and nothing will put it back: a send the
     * user undid. Closing then destroys it, so the screen asks first even if nothing was typed —
     * unlike a message reopened from the outbox, which the queue simply takes back.
     *
     * False also covers "there is nothing on screen at all" (the restore found no draft): an empty
     * composer has nothing to lose, so not guarding it is right. It is deliberately NOT what titles
     * the screen any more — that is [editingOutbox]; conflating the two is what titled an empty
     * composer "Edit" after a process death (#96).
     */
    val onlyCopy: StateFlow<Boolean> = _onlyCopy.asStateFlow()

    private val _prefill = MutableStateFlow<DraftFields?>(null)
    val prefill: StateFlow<DraftFields?> = _prefill.asStateFlow()

    /**
     * The quoted original for a reply/reply-all, delivered separately from [prefill]: the To/Subject
     * headers are built instantly from the cached list row, while the quote needs the full original
     * fetched over the network, which offline stalls on the timeout. Emitted once (null until the
     * fetch returns) so the screen can drop the quote into the body only while the body is still the
     * untouched initial prefill — never over text the user has begun typing.
     *
     * Consumed once ([consumeReplyQuote]), the moment the screen has either dropped it in or said
     * it could not: left in place it is replayed to every new collector, and this ViewModel
     * outlives its activity.
     */
    private val _replyQuote = MutableStateFlow<String?>(null)
    val replyQuote: StateFlow<String?> = _replyQuote.asStateFlow()

    private val _attachments = MutableStateFlow<List<EmailBodyPart>>(emptyList())
    val attachments: StateFlow<List<EmailBodyPart>> = _attachments.asStateFlow()

    private val _attachmentsTouched = MutableStateFlow(false)
    /**
     * Whether the user added or removed an attachment (#70/#94). The unsaved-changes guard needs
     * "did the user change anything", not "are there any attachments": a message reopened from the
     * outbox or a forward opens WITH attachments already, and counting those as an edit made the
     * discard dialog fire on a close the user never edited. [attach], [removeAttachment] and
     * [dropVanishedAttachments] flip this; the prefill paths populate [_attachments] without it.
     *
     * ⛔ Read in exactly one place — the discard-changes guard (`ComposeScreen` →
     * `ComposeDirty.isDirty`). It decides nothing at save time: the save reads [_attachments].
     */
    val attachmentsTouched: StateFlow<Boolean> = _attachmentsTouched.asStateFlow()

    private val _attachmentStatus = MutableStateFlow<String?>(null)
    val attachmentStatus: StateFlow<String?> = _attachmentStatus.asStateFlow()

    /**
     * One-shot string resources to surface after an action that closes the screen (the save
     * succeeds and compose navigates away, so an inline banner would never be read). The screen
     * shows them as a toast, which outlives the navigation.
     */
    private val _notices = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val notices: SharedFlow<Int> = _notices.asSharedFlow()

    /**
     * The draft this phone was keeping has been deleted, and the screen may go (#95 × #127).
     *
     * ⛔ **A signal of its own, and NOT [ComposeState.Done].** Done is what a message that WENT OUT
     * reaches: the screen plays the tern's flight for 300 ms and then pops to `"inbox"`. A deletion
     * is neither — it closes the way the server draft's deletion closes, with a bare `popBackStack()`
     * onto the Drafts list the composer was opened from, which is also where the deleted row was
     * showing. ⛔ It is emitted only after the destroy is durable, the server copy evicted and the
     * row consumed: fired from the tap, it would close the screen over a deletion that then failed.
     */
    private val _localDraftDeleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val localDraftDeleted: SharedFlow<Unit> = _localDraftDeleted.asSharedFlow()

    /**
     * Whether deleting the draft this phone is keeping would LEAVE the server copy behind it — the
     * one thing the confirmation owes the reader before her finger lands (#95).
     *
     * The row masks the server draft it replaced, and when this phone never read that draft whole
     * the copy is deliberately kept ([planForEmptiedLocalDraft], rule 1 of the grid): the row goes,
     * the mask lifts, and the draft from BEFORE the edit comes back at the top of Drafts. The dialog
     * used to say the opposite ("this draft hasn't reached the server … this can't be undone") and
     * the only correction was a toast, after the fact, naming a gesture she had not made.
     *
     * ⭐ The decision is [emptiedLocalDraftKeepsServerCopy], run on the two terms the row carries and
     * armed once, where they are read — never recomputed in the composable, which no JVM test here
     * can execute.
     */
    private val _deleteKeepsServerCopy = MutableStateFlow(false)
    val deleteKeepsServerCopy: StateFlow<Boolean> = _deleteKeepsServerCopy.asStateFlow()

    /**
     * Whether the trash icon's confirmation is up (#127) — the gate in front of the delete, not
     * something the user typed.
     *
     * ⛔ HERE and not in a `rememberSaveable` on the screen, and that is the whole point: the flag
     * belongs where what it acts on lives. A bare `remember` fell back to false on every rotation
     * (no `configChanges` in the manifest, so `MainActivity` is recreated) and the dialog vanished
     * without a word. But `rememberSaveable` survives PROCESS DEATH too, which [editingDraft] and
     * [editingLocalDraftId] do not: after a kill the flag would redraw the dialog over a loading or
     * dead-end screen, with the wrong body, and its Delete would reach the branch where
     * [takeEditingDraft] returns null — nothing deleted, nothing said.
     *
     * ⚠ Be exact about the lifetime, because the wrong claim is easy to make here: this is scoped
     * to the composer's NAVIGATION ENTRY, not to the process. It survives a configuration change
     * (which is the fix) and it dies when the entry is popped — but it also outlives the composer
     * merely leaving the composition, so a notification or a `mailto:` opened over the composer and
     * then dismissed comes back to the confirmation still up. That is deliberate as far as this
     * change goes: the question really is still unanswered. It is NOT the same interval as
     * [editingDraft]'s, and anything written about it should say "the navigation entry", never
     * "the process".
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
     * Whether the leave dialog is up — "You haven't saved your changes" (#35, #127), the question
     * the X and the system Back put in front of dropping what was typed.
     *
     * ⛔ HERE for the same reason as [pendingDraftDelete], and with the same refusal of
     * `rememberSaveable` — read that field's KDoc before touching either. A bare `remember` on the
     * screen fell back to false on every rotation and the question left the screen without a word,
     * as if it had been answered.
     *
     * ⚠ This flag says only WHETHER the dialog is up. What it then says, and which buttons it
     * offers, is decided on the screen by `discardWording` and `discardChoices` from live values —
     * including the refusal to offer "Save draft" while encrypting, which is the 1.4.3 plaintext
     * leak and does not live here.
     *
     * ⚠ Lifetime: the composer's NAVIGATION ENTRY. It survives a configuration change (which is the
     * fix) and dies when the entry is popped. ⛔ Never restate that as "the process" — a longer
     * interval, and the difference is observable.
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
     * Whether the pre-send "did you forget an attachment?" question is up — raised when the text
     * mentions one and nothing is attached.
     *
     * ⛔ HERE for the same reason as [pendingDraftDelete], same refusal of `rememberSaveable`.
     * Turning the phone with this question on screen took it away and left the message unsent, with
     * nothing said about why the tap on Send had come to nothing.
     *
     * ⚠ The two pre-send guards are CHAINED, never up together: this one's Send lowers this flag
     * and only then asks [pendingManyRecipients], which lowers itself before the send goes out. ⛔ Do
     * not merge them into one combined state, and do not leave either raised while a message is in
     * flight — the double-send guard in [send]'s path is a separate thing and stays where it is.
     *
     * ⚠ Lifetime: the composer's NAVIGATION ENTRY, as above — never "the process".
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
     * chain, raised once the attachment question has been answered.
     *
     * ⛔ HERE for the same reason as [pendingDraftDelete], same refusal of `rememberSaveable`; and
     * ⚠ see [pendingForgotAttachment] for the chaining, which is an order, not a combined state.
     *
     * ⚠ Lifetime: the composer's NAVIGATION ENTRY, as above — never "the process".
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
     * Whether the "pick date and time" CALENDAR is up — the first half of the menu's fifth entry
     * (#161), the one that lets an instant be chosen instead of taken from a preset.
     *
     * ⛔ HERE for the same reason as [pendingDraftDelete], same refusal of `rememberSaveable` —
     * read that field's KDoc before touching this one. A bare `remember` fell back to false on
     * every rotation and took the picker off screen without a word.
     *
     * ⚠ The two halves are CHAINED, never up together: this one's OK stores the day, lowers this
     * flag and raises [pendingScheduleTime]. ⛔ Do not merge them into one combined state.
     *
     * ⚠ Lifetime: the composer's NAVIGATION ENTRY, as above — never "the process".
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
     * (#161).
     *
     * ⛔ HERE for the same reason as [pendingDraftDelete], same refusal of `rememberSaveable`; and
     * ⚠ see [pendingScheduleDay] for the chaining, which is an order, not a combined state.
     *
     * ⚠ Lifetime: the composer's NAVIGATION ENTRY, as above — never "the process".
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
     * The day already chosen in the calendar, as the picker gives it: UTC midnight, never local
     * midnight — [pickedScheduleMillis] is what turns it into a real instant, and it is the only
     * thing allowed to read it that way.
     *
     * ⛔ HERE and not on the screen for the reason the four flags above are here, with one more:
     * this dialog carries more than a boolean. A rotation BETWEEN the calendar and the clock would
     * otherwise lose the day already picked, and the clock would come back over nothing.
     *
     * ⚠ Lifetime: the composer's NAVIGATION ENTRY, as above — never "the process". It is dropped
     * when the clock closes, whichever way, so reopening the entry starts from a clean calendar.
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
     * Switch the sending identity, and report what that asks of the body's signature (D5), or null
     * when it asks nothing. The signature lives in the body now, so the identity it belongs to must
     * follow the "From" picker:
     *  - the identity being left had a signature → [SignatureChange.Swap]: the caller replaces its
     *    block where it is still there verbatim ([replaceSignatureBlock]), and leaves an edited or
     *    deleted one exactly as the user made it — a deletion was deliberate;
     *  - it had none → nothing can have been deleted, so the new signature is
     *    [SignatureChange.Insert]ed where the prefill would have put it ([insertSignatureBlock]).
     *    On a reply or a forward that still obeys the two settings: nothing at all when "signature
     *    in replies" is off, and below the quote when asked.
     *
     * [isReplyOrForward] says which compose this is, since the settings only govern that case.
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
     * has opened, and it is deliberately not dropped onto text the user has already begun typing —
     * which until now happened in complete silence, so the reply simply had no quote and no reason
     * given. A toast, nothing more: no "insert it anyway" action to reason about.
     */
    fun noticeQuoteNotAdded() {
        _notices.tryEmit(R.string.compose_quote_not_added)
    }

    /**
     * The screen has dealt with [replyQuote] — dropped it into the body, or said it could not
     * ([noticeQuoteNotAdded]): retire it so it is handled exactly once.
     *
     * `MainActivity` declares no `android:configChanges`, so a rotation, a light/dark flip, a
     * language change or a multi-window resize RECREATES the activity while this ViewModel
     * survives. Left non-null, the quote is replayed to the effect's new collector on every one of
     * those: the body branch rewrites the body with the same string but the caret back at offset 0,
     * on top of the selection `TextFieldValue.Saver` had just restored; the notice branch posts the
     * B6 toast again, one per recreation, stacking in LENGTH_LONG.
     *
     * ⛔ Called only from the two branches that HANDLED it. On the third way out — the quote back
     * before the header prefill landed — nothing has posted it and nothing has announced it, so
     * consuming there would throw it away in silence.
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
     * The body a reply/forward opens with: the [quoted] original, plus the signature when
     * "Signature in replies" is on, above or below the quote per the second setting, with or
     * without the "-- " delimiter line per the third (Settings → Reading and writing). All three
     * are read from DataStore here, inside the prefill coroutine, rather than from a cached
     * snapshot — so a composer opened moments after launch still honours them.
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
     *  change invalidated the answer (PGP turned off, sending account swapped for one with another
     *  keyring). Gate of the anti-flicker rule, see [recipientKeysStale] (#35). */
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

    /** Re-evaluate availability (on open, on From changes, and once a restored message has put its
     *  own mode back); drops the mode if PGP is gone, and SAYS SO when that costs the user a lock
     *  they were shown (#35). The opportunistic default is derived from recipient keys in
     *  [updateRecipientKeys], not forced to ENCRYPT here. The verdict itself is [pgpRefresh]. */
    private fun refreshPgp() {
        viewModelScope.launch {
            val account = pgpAccount()
            val available = account != null && pgp.isAvailable()
            _pgpAvailable.value = available
            // Another account means another keyring: whatever was looked up no longer answers for
            // these recipients, so the next update must look them up again (#35).
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
            // from the top bar entirely (the toggle isn't drawn when PGP is unavailable). Losing it
            // in silence is how a message the user encrypted comes back looking ordinary, so name
            // what went missing (#35).
            when (outcome.dropped) {
                PgpDropReason.NONE -> Unit
                PgpDropReason.NOT_CONFIGURED -> _notices.tryEmit(R.string.compose_pgp_not_configured)
                PgpDropReason.NO_PROVIDER -> _notices.tryEmit(R.string.message_pgp_no_provider)
            }
        }
    }

    /** Lock toggle (short tap): OFF → SIGN → ENCRYPT → OFF. The cycle itself is [nextPgpMode]. */
    fun cyclePgpMode() = setPgpMode(nextPgpMode(_pgpMode.value))

    /**
     * The mode the user picked by hand — from the lock's short tap above, or from the long-press
     * menu, which is the only way to reach [PgpMode.ENCRYPT_UNSIGNED].
     *
     * ⛔ ONE body for both, deliberately: a mode set by hand is marked user-chosen so it is honoured
     * strictly and never auto-downgraded by the opportunistic default (#35), and it announces
     * itself. A second entry point that set `_pgpMode` without both would let the encrypt-by-default
     * account silently rewrite a choice the user had just made, with no snackbar to contradict it.
     */
    fun setPgpMode(mode: PgpMode) {
        pgpModeUserSet = true
        _pgpKeylessRecipients.value = emptyList()
        _pgpMode.value = mode
        _pgpToggleAnnounce.tryEmit(_pgpMode.value)
    }

    /**
     * Refresh per-recipient key availability, then re-derive the opportunistic default mode. Runs
     * whenever we care about keys: an encrypt-by-default account (to decide the mode) or an explicit
     * ENCRYPT (to flag missing recipients). (#35)
     *
     * Called on every keystroke in an addressing field, but only acts when the set of real addresses
     * changed ([encryptionRecipients] + [recipientKeysStale]). A half-typed token has no key by
     * construction, so recomputing on it would flip the lock to "not encrypted" mid-word and flip it
     * back once the address is complete — teaching the user to distrust the one indicator that must
     * be trusted. Skipping is also what keeps the provider out of it: each check is a round-trip to
     * OpenKeychain per address, and there is no reason to ask it about `bo` or `bob@exa`.
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
     * Opportunistic default (#35): with encrypt-by-default on and the user not having set the lock
     * by hand, encrypt only when EVERY recipient has a key, otherwise fall back visibly to plain.
     * The keyless recipients are recorded for the inline hint. A user-set lock is left untouched.
     */
    private fun deriveAutoMode(addresses: List<String>) {
        val account = pgpAccount() ?: return
        if (pgpModeUserSet || !account.pgpEncryptByDefault) return
        // Encrypt is the default intent; back off only when a recipient is CONFIRMED to have no key
        // (a pending lookup leaves it optimistic, so the lock doesn't flicker while typing). No
        // recipients yet → stay on the encrypt intent.
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
     * priming sheet never flashes before DataStore has loaded; it opens only once the real value
     * (false) has emitted.
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
     * both the text and html alternatives so its formatting survives. Null for new/reply/replyAll.
     */
    private var forwarded: ForwardedBlocks? = null
    /** Account to send from: the replied-to message's account (unified inbox), else current. */
    private var accountId: String? = null

    /**
     * The saved draft being edited (#63): compose was opened from a message in Drafts. Sending
     * or re-saving replaces it (the old copy is destroyed once the new one is safely through),
     * so editing never leaves a duplicate behind; a failure leaves it untouched on the server.
     */
    private var editingDraftId: String? = null

    /**
     * The IMAP numbering [editingDraftId] belongs to, FROZEN at the moment that id was read (#99).
     *
     * An `UID EXPUNGE` only means anything inside one UIDVALIDITY. Between this composer reading
     * the draft and a save or a discard going out, a background pass may have seen the Drafts
     * folder renumbered and written the new number to the store — so a repository that looked it up
     * at execution compared that new number with itself, said "same", and expunged a UID belonging
     * to the old numbering, i.e. another message in Drafts. Frozen here, the SELECT is handed the
     * number the id belongs to and the server refuses instead (silently: nothing was lost, the
     * draft simply stays).
     *
     * ⛔ It is read ONCE and never re-read on the way out — re-reading it is the defect itself, not
     * a safety net. ⚠ Not "beside [editingDraftId]" any more: the id is armed before the coroutine
     * and this is read further down, at the first line that CAN answer it — just after the fetch,
     * and above all BEFORE the attachment carry, whose per-part network waits are all the room a
     * background pass needs to notice Drafts renumbered. That gap is safe because an id on its own
     * no longer destroys anything: the verdict beside it stays `true` until the very last statement
     * of the branch. Same reason `InboxViewModel.flushPendingDestroy` is
     * deliberately not `suspend`.
     *
     * On IMAP, null — a folder never observed, a draft this screen was not opened on — destroys
     * nothing, which is the safe half of the trade. On JMAP it is ALWAYS null and the draft is
     * destroyed all the same: the repository's JMAP branch opposes nothing, because a JMAP id
     * survives anything a folder can go through. ⛔ The null is not "JMAP drafts are left behind".
     */
    private var editingDraftUidValidity: Long? = null

    /**
     * The account [editingDraftId] was read from, FROZEN in the same breath as its numbering
     * (#63 × #31).
     *
     * The "From" picker offers the identities of EVERY account and switching it resets nothing
     * about the draft. Resolved at save time instead — from the identity on show, which is what
     * `credentials()` answers — the expunge of an emptied draft left for a server that never issued
     * the id, and the screen closed as a success all the same: on JMAP `destroyAll` spares an id it
     * does not know, so the draft stays in the first account's Drafts, empty of meaning, and comes
     * back on the next sync; on IMAP an id is a NUMBER in a folder and the guard compares numbers,
     * so two accounts whose Drafts share a UIDVALIDITY let the expunge through in the WRONG
     * mailbox — and even where the numbering differs and the refusal falls, the look-up has already
     * invalidated the other account's cached Drafts bodies.
     *
     * ⛔ Frozen where [editingDraftUidValidity] is frozen, once, and never looked up again on the
     * way out: re-reading it answers the "From" that is showing NOW, which is the defect itself and
     * not a safety net.
     *
     * Null destroys nothing — no server draft was opened, or an undone send came back carrying no
     * account — and the screen says the save failed rather than guessing a server.
     */
    private var editingDraftAccountId: String? = null

    /**
     * What the draft held WHEN IT WAS OPENED and this plain-text composer cannot put back: a
     * genuine HTML body (flattened to text on open), inline images or a calendar part (never
     * carried), or a file attachment that failed to re-stage (#63).
     *
     * ⛔ Written in five places, and read only by [editingDraftLossy] — never by a destroy route,
     * which must see the OTHER source too, and ⛔ never by the save's WRITE guard, which weighs
     * [composerBodyWasLost] alone. This term is true for every server draft reopened with an HTML
     * body, i.e. exactly where the composer HAS its text; weighed on a write it refused an erasure
     * the user had made on purpose, in silence. `prepare()` arms this one pessimistically before
     * its coroutine and LOWERS it as the last statement of the reopen, so anything else stored here
     * would be wiped by the next reopen.
     */
    private var editingDraftLossyOnOpen = false

    /**
     * …and what THIS COMPOSER has lost since, on the way into an activity's saved-state parcel: a
     * body whose disk write failed on a text too big for the inline fallback
     * ([saveComposerResume]). The screen pushes it here once, and it is never taken back down.
     *
     * The path it closes is a plain activity recreation — a rotation, a theme flip, a language
     * change. The ViewModel survives it, so nothing here was ever `false` by accident; the parcel
     * is what lost the body, while `applied`, `to`, `cc` and `subject` came back intact from their
     * own saveables. The editor is then redrawn empty over a draft nobody re-reads, `initialBody`
     * is empty too so the leave guard sees no change, and one tap on a Save icon that is still lit
     * destroys the server copy and puts a bodiless one in its place.
     *
     * ⛔ Read in TWO places, and they ask different questions. Through [editingDraftLossy] it is
     * half of the licence to destroy a server original (#63). On its own it is handed to
     * `repo.saveDraft(composerBodyWasLost = …)`, where it is the ONLY term that may refuse to write
     * an empty body over a stored one: the disjunction cannot do that job, because its other half
     * is true for every reopened HTML draft.
     */
    private var composerBodyWasLost = false

    /**
     * THE LICENCE TO DESTROY, and the only name every destroy route reads. There are EIGHT read
     * sites, not the four this sentence used to claim, spread over four functions:
     * `destroyReplacedServerDraft` (the `bodyIsLossy` of `planForEmptiedLocalDraft`, and the
     * `addressingIsProvenEmpty` beside it), `sendInternal` (the `bodyIsLossy` of `enqueueSend`, and
     * the `draftBodyIsLossy` put back when the send is cancelled), `scheduleSend`
     * (`unlessBodyIsLossy`), and `saveDraft` (`addressingIsProvenEmpty`, `emptiedServerDraftIsKept`
     * and the `bodyIsLossy` of the save itself). ⛔ The `composerBodyWasLost` argument beside that
     * last one is NOT a ninth: it is the other field, passed raw, because the body a save may
     * refuse to overwrite is not a question of licence to destroy.
     * ⚠ `grep -n 'editingDraftLossy\b'` finds the eight, but
     * it does not COUNT them: it also matches this header, the declaration and five other comments.
     * It has TWO sources, and either one alone is a data loss:
     * what the reopened draft held that cannot be reproduced, OR a body this composer has lost.
     *
     * ⛔ A computed `val`, not a `var`. As one field, `prepare()`'s last statement
     * (`= lossy || !carried`) would overwrite a lost-body flag on every draft reopen, and there is
     * no ordering that fixes it: `prepare()` runs from a `LaunchedEffect(Unit)` whose coroutine
     * lands whenever the fetch does.
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
     * The row of `local_drafts` this composer holds a lease on (#95), and the account it belongs to
     * — null unless a draft the server has not got was reopened.
     *
     * ⛔ Both are needed to give it back: every write to that table is scoped by `(accountId, id)`
     * because ids collide between two accounts of one server (#31), and an unscoped release would
     * hand ANOTHER account's open row to the upload worker. They are set synchronously as the lease
     * is taken and read by [releaseLocalDraftEdit] as the screen goes.
     *
     * ⭐ **The id is PUBLISHED, exactly as [editingDraft] publishes the server row** (#95): it is
     * what the Delete button may be offered on, and it is the only honest answer to "is there a
     * local draft to delete". `draftOpenRoute(draftId) == LOCAL` is not: on `LocalDraftEdit.Gone`
     * — the upload worker consumed the row between the list being drawn and the finger landing —
     * the route still says LOCAL, the editor stays drawn, and the button would do nothing at all.
     * Only a lease actually HELD arms it, and it is dropped the moment the lease is released or
     * consumed — never merely because a gesture is acting on it ([heldEditingLocalDraft]).
     *
     * ⛔ One holder, not a var beside a flow: two of them drift, and the one the screen reads would
     * then offer a button over a row the ViewModel no longer holds.
     */
    private val _editingLocalDraftId = MutableStateFlow<String?>(null)
    val editingLocalDraftId: StateFlow<String?> = _editingLocalDraftId.asStateFlow()
    private var editingLocalDraftAccountId: String? = null

    /**
     * The SERVER draft the reopened local row stands in for, and the numbering that id was frozen
     * under — read off the row at the open (`LocalDraftPrefill`), null unless a local draft that
     * replaces something was reopened.
     *
     * ⛔ **Two fields of their own, and NOT [editingDraftId] / [editingDraftUidValidity].** That pair
     * is what a re-save and a send act on, and for a reopened local draft it names the ROW: a server
     * id there mints a second row per save, and a numbering there licenses an expunge beside an id
     * no server ever issued. These two are read by the gestures that CONSUME the row and therefore
     * lift the mask it holds over the server copy — emptying the draft (#95 × #69), sending it and
     * scheduling it ([sendDraftTarget]) — because consuming the row alone unmasks that copy and puts
     * the pre-edit text back in Drafts for good.
     */
    private var replacedServerDraftId: String? = null
    private var replacedServerDraftUidValidity: Long? = null

    /**
     * Which account the draft id this composer would carry was read under — handed to
     * [unlessDraftBelongsElsewhere] at the three places that write it somewhere durable (the outbox
     * row, the scheduled row, the saved draft), and opposed there to the account being written as.
     *
     * ⭐ **The choice between the two fields is not made here**: it is [carriedDraftAccountId], in
     * `core:data`, executed by a JVM test — because it is the same choice [sendDraftTarget] makes,
     * and made differently the guard would compare one account's name with another account's draft.
     * This function is only the wiring of the three fields onto it, in ONE place: written out at
     * each site instead, a later edit fixes the mapping at one of them and leaves the other two
     * carrying an id across accounts, which is the defect itself.
     *
     * ⛔ Not a field, and not frozen: the three fields it reads are ALREADY frozen at the open
     * ([editingDraftAccountId], [editingLocalDraftAccountId]), and a fourth copy of them would be a
     * value that can go stale against the id it is supposed to describe.
     */
    private fun carriedDraftAccount(): String? = carriedDraftAccountId(
        editingDraftId = editingDraftId,
        serverDraftAccountId = editingDraftAccountId,
        localRowAccountId = editingLocalDraftAccountId,
    )

    /**
     * The saved draft behind this composer AS A MESSAGE — the cached row, ready to be deleted —
     * or null when there is none to delete (#127).
     *
     * Not derived from [editingDraftId]: that one is a private var with no flow, so a screen
     * cannot follow it. ⚠ And it no longer answers the same question — since the reopen branch arms
     * the id before the coroutine, [editingDraftId] is set on EVERY reopen, including one whose
     * fetch then failed. (It used to be set after the fetch and not at all on a failure, which is
     * what let a save write a silent second draft.) This flow is set only where the draft was read
     * AND the cache also holds the row the delete needs — which is why Delete stays absent after a
     * failed reopen (#127) and must never start reading the id instead. Offline, a reopened draft leaves it null — the composer then knows it cannot
     * offer a delete, rather than offering one that would do nothing.
     */
    private val _editingDraft = MutableStateFlow<Email?>(null)
    val editingDraft: StateFlow<Email?> = _editingDraft.asStateFlow()

    /**
     * Whether a draft saved on the SERVER is behind this composer — what the leave dialog may not
     * announce as destroyed (#35). Deliberately broader than [editingDraft], which is the row the
     * Delete button acts on and is null unless the cache actually holds it: a draft that could not
     * be read still exists in Drafts, and an undone send carries the id of one that this screen was
     * never navigated to. The rule is [savedDraftBehindScreen]; this only publishes its answer.
     */
    private val _savedDraftBehind = MutableStateFlow(false)
    val savedDraftBehind: StateFlow<Boolean> = _savedDraftBehind.asStateFlow()

    /**
     * Whether the saved draft this composer was opened on is still being fetched — the window in
     * which the screen has no fields to show and must offer no action (`draftReopenView`).
     *
     * ⛔ NOT a [ComposeState]. That one is a single slot: `Sending`, `Error` and `Done` overwrite
     * whatever is in it, so a `Loading` put there would be wiped by the first error, and would
     * itself wipe the error banner on its way in. Two independent facts need two holders. The
     * pattern is the repo's own: `CalendarInvite.loading` sits beside the reader's state the same
     * way, for the same reason.
     *
     * Raised on the `draftId` path ONLY. A reply or a forward also fetches, and its buttons stay
     * live throughout on purpose: a reply overwrites nothing on the server, so sending one while
     * its quote is still coming loses nothing. Raising this flag there would disable Send on an
     * offline reply that works today.
     */
    private val _draftLoading = MutableStateFlow(false)
    val draftLoading: StateFlow<Boolean> = _draftLoading.asStateFlow()

    /**
     * The string id of what to say when the draft could not be read at all, or null while nothing
     * has failed. Which sentence it is, is [draftLoadNoticeFor]'s answer, taken from the app's one
     * offline verdict.
     *
     * Its real job is not the sentence: it is that the editor is NOT DRAWN while it is non-null.
     * A blank, editable composer titled "Draft" cannot be told from a new message, and a save from
     * it put a SECOND draft on the server while the first stayed there, untouched and invisible.
     */
    private val _draftLoadFailed = MutableStateFlow<Int?>(null)
    val draftLoadFailed: StateFlow<Int?> = _draftLoadFailed.asStateFlow()

    /**
     * The draft to delete, handed over exactly once, or null when there is nothing to hand over.
     *
     * Refuses while a send or a save is in flight (INV-6, like `abandon`/`sendInternal`/`submit`):
     * a send carries `draftEmailId = editingDraftId` and destroys the original when it lands, so a
     * delete racing it would be a second destruction of the same message. Clears as it hands over,
     * so a double tap cannot delete twice.
     */
    fun takeEditingDraft(): Email? {
        if (_state.value is ComposeState.Sending) return null
        return _editingDraft.value?.also { _editingDraft.value = null }
    }

    private fun credentials(): AccountCredentials? =
        (_selectedFrom.value?.accountId ?: accountId)?.let { store.credentials(it) } ?: store.load()

    /**
     * Every address that IS the user on this account: the login plus each of its identities (server
     * and manual). A reply-all must exclude them all — an account with a second alias used to reply
     * to itself, because only the login was filtered (B5).
     */
    private fun selves(credentials: AccountCredentials): Set<String> =
        (listOf(credentials.username) + store.identities(credentials.id).map { it.email })
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * Reply/forward from the address the original was addressed to (#81): pre-select [accountId]'s
     * identity that appears in [original]'s To, else its Cc. Leaves the selection alone when none
     * does (mailing list, Bcc), so the account's default identity stands, exactly as before.
     *
     * Deliberately NOT a change to the reply-all recipients: [selves] still excludes every address
     * of the account, the one now sending included (B5) — replying to an alias must not put another
     * of your own aliases in To. Nor does this touch the undo-send restore path, which re-selects
     * the identity the user actually sent under and returns before reaching here.
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
                // needs it, whichever of the two messages the user ends up reading.
                Log.w(TAG, "Attachment failed", t)
                // Offline, the upload the picker triggers cannot go anywhere, and the raw
                // "Unable to resolve host …" told the reader nothing they could act on. Say what
                // happened in a sentence instead — but only when connectivity confirms it (see
                // isOfflineFailure); every other failure keeps its technical message.
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
     * Take off the list every attachment whose staged bytes are no longer there, and say so.
     *
     * Called as the composer comes back to the front (`ComposeScreen`'s ON_START observer), because
     * that is when it can have happened: the user leaves for Settings → Apps → Sterna → Storage →
     * Clear cache, or Android evicts `cacheDir` under storage pressure, and neither closes this
     * screen. Until now the chip stayed drawn over nothing and the save was what found out — a red
     * "Could not save the draft: …" with a raw English detail behind it, and no draft written.
     *
     * The DECISION is [sweepStagedAttachments], out in ComposeText where a JVM test can execute it;
     * this method supplies the two things only it has: where staging lives, and the real
     * filesystem. ⛔ The staging root is what keeps a JMAP composer's attachments — they have no
     * file on this phone to lose (see that function).
     *
     * ⛔⛔ IT DOES NOT RUN AT ALL WHILE THIS COMPOSER HOLDS THE ONLY COPY OF SOMETHING, i.e. when it
     * was opened on a server draft ([editingDraftId]) or on a queued outbox item
     * ([editingOutboxId]). Both of those destroy their original once the composer has committed the
     * message, and both destructions are held back by a COUNT of what came through: a server draft
     * by `draftReplacementIsFaithful(attachments.size, parts.size, …)`, whose mismatch is what keeps
     * the original (#63), and a queued item by `persistAttachments` refusing to shorten, which is
     * what keeps `consumeEditingOutbox` from deleting the still-whole durable copy (#70). A sweep
     * that removes the part from the INPUT side of those counts makes them agree, and the guard
     * that existed to save the original waves the destruction through. Standing down leaves both
     * paths exactly as they were before this method existed: the chip stays, the save or send fails
     * with its red banner, and nothing on the server or in the queue is touched. ⚠ The reported
     * symptom therefore survives on those two paths, and that is the accepted price — a duplicate
     * is recoverable, a destroyed original is not.
     *
     * [_attachmentsTouched] is armed exactly as [removeAttachment] arms it. That flag has one
     * reader — the unsaved-changes guard behind the "discard changes?" dialog (`ComposeScreen` →
     * `ComposeDirty.isDirty`) — and a sweep IS a change to the attachment list, made without the user
     * touching it, so leaving the screen afterwards must ask. ⛔ It governs nothing at save time:
     * the save reads [_attachments] directly.
     *
     * ⛔ The sentence does not go through `isOfflineFailure`: nothing here was on the network, and
     * `status_attach_offline` would tell the reader to come back when connected, which brings
     * nothing back.
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
     * Bind everything about a queued or undone message that is NOT the text of its fields — the
     * two callers differ exactly there. The restored lambda of [prepare] fills the fields from the
     * hand-over and then calls this; [resumeOutboxEdit], after a process death, calls this ALONE:
     * To/Cc/Bcc/subject come back from the screen's `rememberSaveable`s and the body from the
     * resume slot, or it is lost — and then "Close this screen and reopen the message" is true,
     * because closing parks the row. ⛔ So no `_prefill` here, ever (OutboxResumeWiringTest).
     *
     * [options] are the identities [prepare] just listed. ⭐ The send identity is a flow of this
     * ViewModel, dead with the process: without this the resumed message would leave under the
     * default identity, not the one it was queued under.
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
        // …with the numbering that id was frozen under, carried by the outbox row through
        // the queue and back (#99). Restored rather than re-read: the read that matters
        // happened when the composer first opened the draft, and looking it up again here
        // would answer the number a sync recorded after any renumbering — the value that
        // lets the expunge through on another message. Null on a row queued before v23 — on
        // IMAP that destroys nothing; on JMAP it is always null and destroys all the same.
        editingDraftUidValidity = d.draftUidValidity
        // …and the ACCOUNT that pair belongs to. The outbox row has no column of its own
        // for it and is not getting one: `fromAccountId` is the account the message goes
        // out under, and it answers for the draft too because a queued row may only carry
        // a draft id belonging to the SAME account as itself — the guarantee lives where
        // the row is written, one gesture away, and this line rests on it. Restored, never
        // re-read: the identity on show can have been switched since, and it covers every
        // account.
        editingDraftAccountId = d.fromAccountId
        // The fidelity verdict travels WITH the id: before it did, undoing a send
        // restored the id while this flag fell back to false, and re-sending destroyed
        // an original the first send had deliberately spared.
        editingDraftLossyOnOpen = d.draftBodyIsLossy
        // The saved draft behind this screen (#35) follows the binding: settled at the head of
        // prepare() from the hand-over, which a composer rebuilt after a process death did not
        // have — so it is settled again here, from the record the resume brought back.
        _savedDraftBehind.value = savedDraftBehindScreen(null, d.draftEmailId)
        // Restore the PGP mode the message carried (#35/#70): a signed item reopens signed,
        // an undone encrypted send reopens with the padlock on — never silently downgraded.
        d.pgpMode?.let { mode ->
            runCatching { PgpMode.valueOf(mode) }.getOrNull()?.let {
                _pgpMode.value = it
                pgpModeUserSet = true
                // Re-check OpenPGP now that the mode is back, instead of relying on the
                // check started above landing after this line. It may not: whether that
                // one has already run depends on how far it got before it had to wait for
                // the provider, so a mode restored into an app that can no longer sign or
                // encrypt survived or not by accident of timing. Asking again once the
                // mode is in place makes the answer the same either way — and the drop, if
                // there is one, is announced exactly once (the second check finds the mode
                // already OFF and says nothing more).
                refreshPgp()
            }
        }
        // Reopened from the outbox: its row waits in the queue marked EDITING until this
        // composer either commits the message somewhere or hands it back (#70). An undone
        // send carries no id — it is the one case where the message only lives on this screen.
        _editingOutbox.value = d.editingOutboxId != null
        editingOutboxId = d.editingOutboxId
        _onlyCopy.value = d.editingOutboxId == null
    }

    /**
     * Take back, by [id], the queued row a composer rebuilt after a process death was opened on
     * (#70). Before this, that composer was DETACHED: `editingOutboxId` is only ever set from the
     * in-memory hand-over, which died with the process, so Send enqueued a SECOND copy beside the
     * original the startup sweep had parked FAILED, and the title, the leave dialog, the send
     * identity, the attachments, the PGP mode and the draft being replaced were all those of a new
     * mail.
     *
     * The order is the whole of it and it is [resumeOutboxRow], executed in `OutboxResumeTest`:
     * AFTER `AppContainer.outboxRecovery`, so the row is FAILED when [MailRepository.takeOutboxForEdit]
     * reads it (which `canEdit` accepts) and comes back EDITING with nobody left to park it. A row
     * that is gone or not reopenable (SENDING, encrypted, an unreadable attachment) leaves the
     * composer detached as it was — not worse, and nothing leaves on its own: the sweep parked it.
     *
     * ⛔ Fields are NOT touched (A8): see [bindQueuedRow].
     *
     * ⚠ Residue, accepted: a Send tapped before this has returned goes out without an id — a new
     * row, the original still parked FAILED beside it, to Retry or delete by hand (two explicit
     * gestures, like a Retry after the fact). And a row taken back under a composer that is then
     * popped stays EDITING until the next launch, whose sweep parks it again.
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
     * Build initial fields when opening as a reply/reply-all/forward of [replyToId], or, when [to]
     * is given (from the participant panel's "write to"), as a fresh mail pre-addressed to it.
     *
     * @param quoteAlreadyOnScreen the composer on screen ALREADY carries the quoted original, so
     * neither of the two sentences below may be produced. It is the screen that knows it and not
     * this ViewModel: after a real process death this object is brand new, [prepared] is an
     * ordinary field and so is false again, and everything here runs a second time — while the
     * screen restored its `body`, its `initialBody` and its `applied` from `rememberSaveable`.
     * Left ungated, the second pass re-emits [replyQuote] onto a body that no longer matches its
     * baseline (the B6 toast then says the quote was not added, over the quote) and re-announces
     * `compose_prefill_failed` offline (the banner then covers the restored quote). Both would be
     * messages contradicting what the reader is looking at.
     * @param outboxId the queued row this composer was opened on by Outbox → Edit (#70), from the
     * route. Read only when [restore] is true and nothing was handed over in memory — i.e. after a
     * process death — to take the row back ([outboxRowToResume], [resumeOutboxEdit]).
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
        // Settle, for every way of opening the composer, whether a queued row is really parked
        // behind it (#96). The navigation argument survives the app being killed; the handed-over
        // draft does not, so `restore=true` with nothing in hand is an EMPTY composer and must be
        // described as one — not as the edit of a message that is no longer there. Set here rather
        // than in the restore branch alone so no other path can inherit a stale answer.
        _editingOutbox.value = holdsQueuedOutboxRow(restore, outbox.restored.value)
        // The OTHER thing that can survive this screen (#35): a draft saved on the server. Settled
        // here for the same reason and from the same two facts — the navigation argument, and the
        // draft id an undone send carries back. Not in the branches below: the restore branch
        // consumes `outbox.restored`, so after it there is nothing left to ask.
        _savedDraftBehind.value =
            savedDraftBehindScreen(draftId, if (restore) outbox.restored.value?.draftEmailId else null)
        this.accountId = accountId
        val options = store.accounts().flatMap { acc ->
            store.identities(acc.id).map { FromOption(acc.id, it) }
        }
        _fromOptions.value = options
        val preferred = accountId ?: store.load()?.id
        // Prefer the preferred account's chosen default identity (keyed by stable id), then any
        // identity of that account, then the very first option. A reply/forward/restore path below
        // overrides this initial pick when it sets _selectedFrom explicitly.
        val defaultIdentityId = preferred?.let { store.defaultIdentityId(it) }
        _selectedFrom.value =
            options.firstOrNull { it.accountId == preferred && it.identity.id == defaultIdentityId }
                ?: options.firstOrNull { it.accountId == preferred }
                ?: options.firstOrNull()
        refreshPgp()

        // Reopening an undone send: restore every field the user had, including the
        // "From" identity, Cc/Bcc, and attachments, so nothing is lost.
        if (restore) {
            // Nothing handed over means the app was killed while this composer was open. With no
            // id in the route either — the Undo route after such a death — the fields stay empty,
            // [editingOutbox] was already settled to false above, and [_onlyCopy] stays false too:
            // an empty screen has nothing to lose, so nothing to guard (#96). With an id — Outbox →
            // Edit — the resume below reattaches the row once the startup sweep is over; it does
            // not touch the fields, which come back from the screen's own saveables.
            val handed = outbox.restored.value
            handed?.let { d ->
                _prefill.value = DraftFields(
                    to = d.to, cc = d.cc, bcc = d.bcc, subject = d.subject, body = d.body,
                    // The styling comes back with the text (#131): held in memory for an undone
                    // send, parsed out of the queued row's html for an Outbox → Edit
                    // (`composeDraftOf`). Dropped here, undoing a send hands back a message with
                    // its bold removed, and the re-send delivers the removal.
                    bodyRanges = d.bodyRanges,
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

        // Editing a saved draft: reopen it in compose with every field it carried, and remember its
        // id so sending or re-saving replaces it instead of duplicating (#63).
        //
        // ⛔ Which STORE the id belongs to is a decision, not a null check, and it is answered in
        // core:data where a JVM test executes it (`draftOpenRoute`). Branching on `draftId != null`
        // alone sent a `local-draft:` id down the branch below, whose first statement is
        // repo.fetchEmail: for an id no server has ever heard of that is "Message is not in the
        // cache." on IMAP and an unknown id on JMAP — and with no network it does not even fail
        // fast, it blocks on the connect timeout. The catch then posts compose_prefill_failed and
        // the composer opens EMPTY over the only copy of the text (#95). A `when` over the three
        // answers also means the local arm cannot be moved below the server one.
        when (draftOpenRoute(draftId)) {
            DraftOpenRoute.LOCAL -> {
                // Raised HERE, before the coroutine, and NOT on its first line — the same reason
                // the SERVER arm below states, and it holds on a Room read too: this way the flag
                // is up before the first suspension point, so before any frame the read could
                // delay. Set inside, the editor is on screen — editable, with Save, Send and the
                // padlock — for whatever the dispatcher does with that body, which is exactly the
                // window a fast finger types into, and a save landing in it writes a SECOND draft
                // while the first stays intact and invisible. That is the defect #63 closed on the
                // server route and this one still held open.
                // ⚠ Today `viewModelScope` is Main.immediate and `prepare` is called from a
                // LaunchedEffect, so the body would in fact run inline and no frame is drawn in
                // between: this placement is a guard against those two facts changing, not a
                // reading of them. It costs one statement, and it is what makes the two arms read
                // alike.
                _draftLoading.value = true
                // ⛔ The `finally` is at THIS level, not inside prepareLocalDraft: that function
                // leaves by three bare `return`s (no credentials, Gone, Busy) as well as by any
                // throw from Room, and a flag only one path lowers is a spinner for good — with
                // the sentence the failure just recorded never drawn under it.
                viewModelScope.launch {
                    try { prepareLocalDraft(draftId!!) } finally { _draftLoading.value = false }
                }
                return
            }
            DraftOpenRoute.SERVER -> {
                // ⛔ The name stays `draftId` below, deliberately: the numbering freeze, the id and
                // the lossy verdict of this branch are pinned line by line by four wiring rules.
                val draftId: String = draftId!!
                // Raised HERE, before the coroutine, and NOT on its first line: this way the flag
                // is up before the first suspension point, so before any frame the fetch could
                // delay. Set inside, the editor is on screen — editable, with Save, Send and the
                // padlock — for whatever the dispatcher does with that body, which is exactly the
                // window a fast finger types into. ⚠ Same reservation as the LOCAL arm above:
                // Main.immediate would run the body inline as things stand, so this is a guard
                // against that changing, not a reading of it. The waiting the reader SEES on this
                // arm is the network's; the local arm raises the same flag for a Room read.
                _draftLoading.value = true
                // ⭐ THE ID AND ITS VERDICT, ARMED AS ONE GESTURE — here, beside the flag above and
                // for the very same reason: before the first suspension point, so before any frame
                // the fetch could delay.
                //
                // ⛔ The invariant of this branch is no longer "the id is laid LAST". It is "the
                // LICENCE TO DESTROY is laid last". The id used to be the try's last statement,
                // because the id is what arms the destroy and a send tapped while the attachment
                // carry was still downloading went out amputated and destroyed the only copy (#63).
                // That paid for the race with a hole: `applied` is a rememberSaveable in
                // ComposeScreen, so after the process is killed the editor comes back WITH the
                // typed text and a live bar while prepare() re-runs. Offline, repo.fetchEmail
                // throws, we leave by the catch, and that last statement is never reached — Save
                // then sees `replacesEmailId = null`, `finishDraftSave` answers SAVED and
                // `draftSaveNeedsNotice` answers false: a SECOND draft on the server, and not one
                // word on screen. Two more legs never even reached the catch: the seconds while the
                // fetch is in flight (the bar is alive from the first frame, the fetch blocks to the
                // connect timeout), and the `credentials() ?: return@launch` below, which leaves
                // before it.
                //
                // ⭐ An id on its own destroys nothing now, because the verdict armed with it is
                // pessimistic: `draftReplacementIsFaithful` is `!bodyIsLossy && …`
                // (core/data/…/mail/DraftSave.kt). ⛔ It must be armed to `true` and not left at the
                // field's `false` default — that default IS the destroy: the same function compares
                // the composer's attachments with THEMSELVES, so an unread draft's files count 0
                // against 0 and pass, and on JMAP `addressingIsCarried` is a hard-coded `true`
                // (MailRepository, the JMAP branch of uploadDraft). The identical trap was met on
                // the LOCAL route and answered the same way — `bodyIsLossy = 1` forced on every
                // rescued row rather than on a condition that would have to stay right for ever
                // (core/data/…/db/LocalDraftDao.kt, revertStagedToPendingLossy, whose comment
                // spells out the same "0 compared to 0"). Same rule as
                // SendOutbox.ComposeDraft.draftBodyIsLossy: a site that cannot prove fidelity
                // answers `true`.
                //
                // ⚠ The duplicate that survives a failed reopen is DELIBERATE and stays: the
                // original was never read, so replacing it would take it and its attachments with
                // it. What is fixed here is the SILENCE — the save now answers ORIGINAL_KEPT, so
                // the notice (compose_draft_original_kept) is finally shown.
                editingDraftId = draftId
                // ⭐ …AND THE ACCOUNT IT WAS READ UNDER, IN THE SAME BREATH — because a server
                // draft id must never travel without it. `unlessDraftBelongsElsewhere`
                // (core/data/…/mail/LocalDraftReopen.kt) lets a NULL draft account THROUGH on
                // purpose: null is supposed to mean "no server draft is open behind this composer".
                // An id armed beside a null account therefore disarms that guard completely.
                // ⛔ Frozen after the fetch, as it was while the id was still laid last, every way
                // the read can fail left exactly that pair standing for good — offline, the process
                // killed and prepare() re-run under a bar `applied` has already redrawn, or the
                // `credentials() ?: return@launch` below, which leaves before the fetch. Switch
                // "From" to another account (the picker covers them all and resets nothing), tap
                // Save, and the guard waves A's id into a save made under B: `repo.saveDraft`
                // updates the row `forServerDraft(B, D)` finds, and server draft ids COLLIDE
                // between two accounts of one server (#31, core/data/…/mail/LocalDraftSave.kt),
                // so B's own pending draft is overwritten by A's text — the only copy of it.
                // ⛔ Read with `credentials()`, which is NOT `suspend` and answers here exactly what
                // it answers below: `_selectedFrom` is not populated yet at the entry of prepare()
                // (`prepared` guards the entry, and the ViewModel is new after a process death), so
                // both calls resolve the same account.
                // ⛔ And it is frozen HERE ONLY — not here and again after the fetch. Two writes of
                // one field a few lines apart is a value free to go stale against the id it
                // describes, and DraftNumberingFreezeWiringTest counts exactly two in this whole
                // class: the restore of an undone send, and this one.
                editingDraftAccountId = credentials()?.id
                editingDraftLossyOnOpen = true
                viewModelScope.launch {
                    try {
                        // No account to ask means the draft cannot be read either, so this path
                        // says so like any other failure. It used to fall through to a blank
                        // editable composer, which is the lie being removed. Not "offline": the
                        // link may be perfect.
                        val credentials = credentials() ?: run {
                            _draftLoadFailed.value = draftLoadNoticeFor(offline = false)
                            return@launch
                        }
                        val draft = repo.fetchEmail(credentials, draftId)
                        // The row the Delete button would act on (#127), taken now, from the cache
                        // the Drafts list itself was drawn from. Only here: a draft that could not
                        // be read must not be offered a delete (see [editingDraft]). Read scoped to
                        // this account (#31): two sub-accounts of one login mint the same draft id.
                        _editingDraft.value = runCatching { repo.cachedEmail(credentials.id, draftId) }.getOrNull()
                        // That same row is also the ONLY place a recipient the server dropped can
                        // still be found (#96), so the prefill is handed it — it fills an addressing
                        // field the server returned empty, never one the server filled.
                        _prefill.value = draftFieldsOf(draft, _editingDraft.value)
                        inReplyTo = draft.inReplyTo
                        references = draft.references
                        // The numbering the draft's id belongs to, frozen HERE and never read again:
                        // at save time it would be the number a refresh recorded after the folder was
                        // renumbered, and the expunge would land on another draft (#99). ⚠ It is now
                        // read AFTER the id is armed, and that is safe: a numbering on its own
                        // destroys nothing, and neither does the id on its own any more — what
                        // destroys is the PAIR (id + a CLEAN verdict), and the verdict is only
                        // lowered a few lines below, on a draft that was actually read. Still taken
                        // before the attachment carry: the earliest read is the safest one.
                        editingDraftUidValidity = repo.recordedUidValidityForDraft(credentials, draftId)
                        // ⛔ THE ACCOUNT IS NOT FROZEN HERE. It is armed at the entry, beside the id
                        // it describes and before this coroutine starts — see the block above
                        // `viewModelScope.launch`. Moved back down to this line it would be frozen
                        // only on the paths that reach it, and every failed reopen would leave an
                        // armed id with a null account, which is the one shape
                        // `unlessDraftBelongsElsewhere` waves through. `credentials()` there and
                        // `credentials` here are the same account: `_selectedFrom` is still empty
                        // at the entry of prepare().
                        // What this editor cannot give back on a re-save: an HTML body it cannot
                        // READ BACK (since #131 an html of our own reopens with its spans and costs
                        // nothing — `draftHtmlIsLossy` decides, and an html that is present but
                        // says nothing counts as unreadable), inline images and calendar parts (not
                        // carried), and the read receipt the draft asked for — its header is on the
                        // server copy and the checkbox below comes back CLEAR (D1), so the message
                        // would go out asking for nothing and the destroy would take the only copy
                        // that asked.
                        // Every leg is read off the DRAFT that was just fetched, never off this
                        // screen's state, and the decision itself lives in core:data so a JVM test
                        // can execute it — nothing here can (see DraftVerdictWiringTest).
                        // ⚠ INTO A LOCAL, NOT INTO THE FIELD. Written straight to
                        // [editingDraftLossyOnOpen] here, this line would hand the screen a clean licence
                        // to destroy while the carry below has not even started — and the carry
                        // suspends once per attachment on a network download and publishes nothing
                        // into [_attachments] until its very last statement. The editor and the
                        // whole bar are already drawn by then (`_prefill` was emitted above, so
                        // `applied` is true and draftReopenView answers Editor), so a Send tapped
                        // in that window goes out with NO attachments against a faithful verdict —
                        // 0 parts compared with 0 — and the original holding the only copy of the
                        // files is expunged on delivery. That is #63, word for word.
                        val lossy = reopenedDraftIsLossy(
                            hasHtmlBody = draftHtmlIsLossy(draft.htmlContent()),
                            inlineImageCount = draft.inlineImageParts().size,
                            calendarPartCount = draft.calendarParts().size,
                            receiptHeader = draft.dispositionNotificationTo,
                        )
                        // ⛔ Its own `val`, and NEVER `lossy || !carryDraftAttachments(…)`: `||`
                        // short-circuits, so a draft already judged lossy would never have its
                        // attachments carried at all and they would never come back on screen. The
                        // carry runs on every reopen; only the ASSIGNMENT waits.
                        val carried = carryDraftAttachments(credentials, draft)
                        // ⭐ THE LICENCE TO DESTROY, LAID LAST — one assignment, the final statement
                        // of this branch, and the only place the pessimistic verdict armed on entry
                        // is ever lowered. Until this line runs, `editingDraftLossy` is `true` from
                        // the first frame onwards, so every Send, Save or empty-and-save tapped
                        // during the fetch AND during the carry keeps the original: an id with a
                        // lossy verdict destroys nothing (`draftReplacementIsFaithful` is
                        // `!bodyIsLossy && …`, core/data/…/mail/DraftSave.kt).
                        // ⛔ It reads BOTH answers. `= lossy` alone would call a draft faithful
                        // whose files failed to download — the destroy of the only copy of them —
                        // which is why `carryDraftAttachments` returning the strict count
                        // (`staged.size == parts.size`) is now what the whole of #63 rests on.
                        editingDraftLossyOnOpen = lossy || !carried
                    } catch (t: Throwable) {
                        // The draft could not be read. NOTHING is prefilled and, above all, no
                        // editor is drawn (draftReopenView): a blank editable composer titled
                        // "Draft" cannot be told from a new message, and saving from it added a
                        // SECOND draft while the first stayed on the server, intact and invisible.
                        //
                        // The sentence is its own (compose_draft_offline /
                        // compose_draft_load_failed), not compose_prefill_failed: that one already
                        // means "the quoted original of a reply is missing", and a third meaning
                        // would make it say nothing at all. It is unchanged on the reply path.
                        //
                        // Whatever the draft held is unknown to us now, so a later save must not
                        // destroy it. ⚠ Redundant since the verdict is armed `true` on entry — a
                        // belt over braces, kept because it costs nothing and it documents the
                        // path. ⛔ What it must no longer claim is the sentence that used to close
                        // it, "nothing on this screen can save any more": that was the false
                        // invariant behind the whole defect. `applied` survives process death, so
                        // on a reopen the editor and the bar are back over the typed text while
                        // this catch runs, and Save is one tap away.
                        editingDraftLossyOnOpen = true
                        val app = getApplication<Application>()
                        _draftLoadFailed.value = draftLoadNoticeFor(isOfflineFailure(t, online = hasUsableNetwork(app)))
                    } finally {
                        // EVERY way out of the body lowers it, not just the two above: the silent
                        // `return@launch` when there are no credentials, and any throw from outside
                        // the try, would otherwise leave the composer waiting on a fetch that is
                        // over — frozen, with no fields, no actions and no sentence, for good.
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
            // Launched, not inline: the delimiter setting lives in DataStore and reading it
            // suspends. The screen already applies the prefill from a LaunchedEffect, so it lands
            // the same way a reply's does.
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

        // A blank new message: it still opens with the signature in the body, where it can be read
        // and edited before sending (nothing is appended behind the user's back at send time).
        // Note that no draft/undo path reaches here — those return above with their own body, which
        // already contains the signature it was saved with, so it is never inserted twice.
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
            // Parsed ONCE: the three branches below read this and never the raw string, so a mode
            // one of them does not know cannot open, in silence, a threaded quoted reply.
            val opening = composeOpening(mode)
            fun prefillFailed() {
                _attachmentStatus.value =
                    getApplication<Application>().getString(R.string.compose_prefill_failed)
            }

            // Cache-FIRST, and never blocked on the network: To/Subject (and the reply-all
            // recipients) of a reply come from the original's headers, which the cached list row
            // already holds — so prefill them IMMEDIATELY. Offline, fetchEmail below does not fail
            // fast: it blocks on the connection timeout (~10s), so waiting on it to build the
            // headers is exactly the bug — the recipient/subject must appear at once (Codeberg:
            // offline reply to a never-opened mail). No quote yet; the body is enriched below.
            // Account-scoped lookup: the cache is keyed (accountId, id) and a bare id would hand
            // back a sibling sub-account's message under the same id (issue #31).
            val cached = runCatching { repo.cachedEmail(credentials.id, replyToId) }.getOrNull()
            if (cached != null) {
                // BEFORE the prefill: the identity decides which signature the body opens with, so
                // the alias must be picked first or the body would carry the default one (#81).
                // Since schema v21 the cached row carries To, Cc AND Bcc; the second attempt below
                // on the fetched original stays for rows cached BEFORE that migration, which are
                // not back-filled and still come back with their copy columns empty.
                preselectReceivingIdentity(credentials.id, cached)
                _prefill.value = buildPrefill(cached, mode, selves(credentials), quoteBody = false)
                if (opening.threads) {
                    // Threading ids aren't cached on the list row (empty here) — the fetch below
                    // supplies the real ones; keep whatever the cache has meanwhile.
                    inReplyTo = cached.messageId
                    references = cached.references + cached.messageId
                }
            }

            // THEN, in the background, fetch the full original — only to enrich the body: the quoted
            // original for a reply, or the carried original for a forward. This is the call that
            // stalls offline, which is why the headers above did not wait on it.
            val original = runCatching { repo.fetchEmail(credentials, replyToId) }.getOrNull()

            if (original == null) {
                // No full body available (offline, uncached, or the fetch failed). The headers are
                // already prefilled from the cache if there was one; either way, say the original
                // couldn't load so the missing quote / forward content isn't a silent surprise.
                //
                // ⛔ Unless the quote is already on screen: this is a composer being rebuilt after
                // the app was killed, its body was restored WITH the quote in it, and the banner
                // would be drawn on top of the very thing it says could not be loaded (G6). The
                // sentence is what is dropped — the bail-out below is not, since `original` really
                // is null and nothing under it can run.
                if (!quoteAlreadyOnScreen) prefillFailed()
                return@launch
            }

            // The fetched original has the complete To AND Cc, so it can settle the sending identity
            // the cached row could not (#81). Still before any body is (re)built below, so whatever
            // signature lands belongs to the identity finally selected.
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
                // message/rfc822 part. Empty body (plus the signature, as for a forward), no
                // threading ids, and no buildForwarded — the original travels as the .eml only.
                if (cached == null) _prefill.value = buildPrefill(original, mode, selves(credentials))
                // Say so while the source is on its way, as attach() does: the composer is on
                // screen and sendable before the part is, and a "Fwd:" with an empty body and no
                // attachment must never look finished.
                _attachmentStatus.value = getApplication<Application>().getString(R.string.status_attaching)
                runCatching {
                    // The attachment ceiling, restated on what actually came back. Over JMAP the
                    // download itself already refuses a blob over it (downloadBlob's default
                    // maxBytes, ContentTooLargeException); over IMAP the parser drains a literal
                    // over its own 32 MiB into an EMPTY source, which rawSource then refuses as
                    // unreadable. So this line is reached only if one of those two stops holding.
                    val bytes = repo.rawSource(credentials, replyToId)
                    DownloadLimits.enforce(bytes.size.toLong(), DownloadLimits.ATTACHMENT_MAX_BYTES)
                    stageOutgoing(
                        credentials, bytes, type = "message/rfc822",
                        name = safeFileName(original.subject, "message") + ".eml",
                        disposition = "attachment", cid = null,
                    )
                }.onSuccess { part ->
                    // In a straight line, NOT through attach(): that one arms _attachmentsTouched,
                    // and the composer would open "dirty" — a discard box on the way back without
                    // a key typed (#70).
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

            // Reply / reply-all: take the real threading ids from the fetched original, then hand
            // the quoted body to the screen out-of-band via [replyQuote] so it lands WITHOUT
            // touching the To/Subject the user already sees (or has begun editing). When nothing was
            // cached, the headers weren't prefilled yet, so emit the full prefill (quote included).
            inReplyTo = original.messageId
            references = original.references + original.messageId
            if (cached == null) {
                _prefill.value = buildPrefill(original, mode, selves(credentials))
            } else if (!quoteAlreadyOnScreen) {
                // The whole body, not just the quote: the screen swaps it in wholesale, and it must
                // keep the signature the cache-first prefill already put there.
                //
                // ⛔ Not a second time. After a process death the screen still shows the quote it
                // restored, and by then the user may well have typed under it: handing the quote
                // over again lands on a body that no longer equals its baseline, so the screen
                // takes the other branch and toasts "quoted message not added" over a body that
                // carries it (G5). Written as an `else if` rather than a guard on the line below
                // so that emission stays the one line ReplyQuoteConsumedWiringTest pins whole.
                _replyQuote.value = replyBody(quote(original, resolveOutgoingDateZone(settings.quotedDatesUtc)))
            }
        }
    }

    /**
     * Reopen one of THIS PHONE's own drafts — a row of `local_drafts` the server has not got (#95).
     *
     * ⛔ **Nothing on this path may touch the network, and nothing may read the cache.** The id
     * names a row no server has ever heard of: `fetchEmail` answers "Message is not in the cache."
     * on IMAP and an unknown id on JMAP, `cachedEmail` answers null, and offline the first of them
     * blocks on the connect timeout before failing. A fallback to either could only make the screen
     * lie. The row IS the draft, and [localDraftPrefill] projects it — a pure function a JVM test
     * executes field by field, rather than a prefill built by hand here where a forgotten column
     * (Bcc, In-Reply-To, the attachments) is silently a dropped one.
     *
     * ⭐ The row is taken out of the upload worker's way for as long as this composer holds it
     * ([LocalDraftState.EDITING], given back by [releaseLocalDraftEdit]). Without that lease the
     * network coming back mid-edit uploads the draft and `discardLocalDraft` deletes the row AND
     * its attachment directory under the user; her next "Save" then lands on a fresh row with no
     * files, beside a copy of the older text the server now keeps.
     *
     * ⚠ [_editingDraft] is deliberately left null: it is the SERVER row the list's delete moves to
     * the Trash, and there is no such row here. The Delete button is offered all the same, on
     * [editingLocalDraftId] — the lease this open really holds — and it goes to
     * [deleteEditingLocalDraft], which is the emptying gesture reached by a button.
     *
     * ⛔ **A row that is not there is SAID, not swallowed** — and said on [_draftLoadFailed], the
     * one flow [draftReopenView] reads to take the editor OFF the screen. The upload worker can
     * consume the row between the list being drawn and the tap landing on it, and both early exits
     * used to be a bare `?: return`: the composer then opened blank, with nothing on it and not a
     * word, over a draft the user had just seen in the list. A sentence on `_attachmentStatus`
     * fixed neither half — it is a status line UNDER an editor that is still drawn, still editable
     * and still offering Save over a draft this screen never read.
     */
    private suspend fun prepareLocalDraft(id: String) {
        val credentials = credentials()
        if (credentials == null) {
            noticeLocalDraftNoAccount()
            return
        }
        // ⛔ The lease is taken, remembered and — if this screen is popped while the take is still
        // out — GIVEN BACK BY THIS COROUTINE, under the cancellation shelter the decision carries.
        // Armed after the call instead, the id was never remembered when viewModelScope died inside
        // it: the row stayed EDITING, held back from the upload worker and from abandon() alike,
        // and the draft reached the server only after the next cold start (#95).
        val lease = takeLocalDraftEditOrGiveItBack(
            id = id,
            take = { repo.takeLocalDraftForEdit(credentials.id, it) },
            // Remembered synchronously, and BEFORE any field is written: the back gesture can
            // arrive at the very next frame. Both halves — ids collide between two accounts of one
            // server (#31), and an unscoped release hands ANOTHER account's open row to the worker.
            arm = { taken ->
                editingLocalDraftAccountId = credentials.id
                _editingLocalDraftId.value = taken.id
            },
            giveBack = { runCatching { repo.releaseLocalDraftEdit(credentials.id, it) } },
        )
        val row = when (lease) {
            // The row is gone — the worker consumed it between the list being drawn and the tap
            // landing, or the user threw it away. Nothing will bring it back, so the sentence must
            // not ask for anything: compose_local_draft_gone.
            LocalDraftEdit.Gone -> {
                noticeLocalDraftGone()
                return
            }
            // The row is there and something else really owns it right now: its files are being
            // staged, or the worker is uploading it. A different sentence, because it asks for
            // something different and something that WORKS: close this screen and open it again.
            // ⭐ An EDITING row is NOT here: it is this phone's own stale lease, taken back above.
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
            // …and the styling the row stored, out of the SAME projection as the text
            // (`localDraftPrefill` reads the html part first and falls back to the text column).
            bodyRanges = fields.bodyRanges,
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
        // The fidelity verdict travels WITH the id, exactly as it does for an undone send: `false`
        // here would launder a body the composer already knows it cannot reproduce, and the next
        // send would destroy the original this row was written to spare (#63).
        editingDraftLossyOnOpen = fields.bodyIsLossy
        // ⭐ The row's OWN id: `localDraftTarget` looks a `local-draft:` id up by id, so a re-save
        // lands on this very row. The server draft this row stands in for is NOT armed here — a
        // local id names nothing a server can be asked to destroy (`serverDraftReplacedBy`), and
        // [editingDraftUidValidity] therefore stays null: a numbering is the one thing that lets an
        // expunge through, and there is no server id here for it to belong to.
        // ⛔ [editingDraftAccountId] stays null for the same reason, and this route arms it
        // NOWHERE: it says which server hears about an id read off a server, and this id was read
        // off this phone. What the gestures that consume the row destroy under is
        // [editingLocalDraftAccountId] — the account the LEASE was taken under.
        editingDraftId = fields.editingDraftId
        // ⭐ The server draft this row stands in for, carried to the gestures that consume the row —
        // emptying it, sending it, scheduling it — since each of those lifts the mask the row holds
        // over that copy. Kept apart from the pair above on purpose: this id is not what a re-save
        // lands on, and its numbering must never end up beside an id the server did not issue.
        replacedServerDraftId = fields.replacedServerDraftId
        replacedServerDraftUidValidity = fields.replacedServerDraftUidValidity
        // ⭐ Armed from the two terms the row carries, at the one place they are read, so the delete
        // confirmation can say BEFORE the tap that the copy on the server stays (#95). The decision
        // is the same one the empty save's notice is posted from — one function, executed.
        // ⛔ `addressingIsProvenEmpty = true`, and it is NOT an assertion that the original was
        // copied to nobody: this dialog answers BEFORE the finger, and the addressing cannot be
        // established there without a server read at every reopen of the composer — a blocking read
        // in front of the gesture that exists because there was no network. Passing "proven empty"
        // leaves this dialog's rule EXACTLY what it was.
        // ⭐ The gap that leaves is ONE-WAY, which is the whole reason it is acceptable: this
        // dialog only ever ADDS a line, `compose_emptied_draft_server_copy_kept`
        // (`ComposeScreen`, the second element of the body). Answering `true` here can only make it
        // withhold that line for a copy the destruction then keeps — and posts the same sentence
        // about, a moment later. It can never make the dialog promise a copy stays when the
        // gesture is about to destroy it, which is the direction that would lie.
        // ⚠ A SECOND way into that same one-way gap since the composer can lose its body on the
        // way into a parcel: this line reads what the ROW held, while the gesture reads
        // [editingDraftLossy], which also carries what was lost SINCE. After such a loss the
        // dialog withholds the line and the destruction keeps the copy anyway. Same direction,
        // same debt, widened — not a second one, and deliberately not closed here: recomputing it
        // would put a second writer on a flow that is armed at the one place its terms are read.
        _deleteKeepsServerCopy.value = emptiedLocalDraftKeepsServerCopy(
            fields.replacedServerDraftId,
            fields.bodyIsLossy,
            addressingIsProvenEmpty = true,
        )
    }

    /**
     * The user emptied a draft this phone was keeping, and saved: destroy the SERVER draft it stood
     * in for, then take the row away (#95 × #69).
     *
     * ⛔ **Consuming the row alone is the defect.** While the row exists the server draft is taken out
     * of the Drafts list (`replacedServerIds` / `hidesServerRow`, `ui/inbox/LocalDraftRows.kt`, both
     * reading `replacesEmailId`); once the row goes, nobody names it any more, the mask lifts, and the
     * draft the user has just emptied comes back at the top of Drafts carrying the text from before
     * her edit — permanently, and looking like a sync bug. ⚠ The mask is not total: `hidesServerRow`
     * only hides a row that stands for ITSELF (`threadCount == 1`), so a server draft that represents
     * a conversation is drawn beside its local row — the duplicate accepted there, on purpose.
     *
     * The destroy travels the route every permanent destroy in this app takes: held-back WorkManager
     * work under a network constraint, carrying the folder and the FROZEN numbering, so it survives
     * the process and fires when the network returns. ⛔ Through [MessageDestroyWorker.destroyDurably]
     * and not `schedule`: the latter takes the account's unique work slot with `REPLACE` and would
     * drop a permanent delete confirmed seconds ago, still inside its undo window.
     *
     * The order of the acts is decided and executed in [destroyThenConsumeEmptiedLocalDraft], what
     * the order CARRIES in [planForEmptiedLocalDraft], and which folder it is opposed to in
     * `draftDestroyFolder` — all three pure, all three run by a JVM test, because an order missing its
     * folder or its numbering destroys nothing in silence.
     *
     * ⭐ A draft this phone never read WHOLE keeps its server copy, and the screen says so: see
     * [planForEmptiedLocalDraft]. `editingDraftLossy` is that verdict, carried off the row by
     * `localDraftPrefill` — the same value the save opposes to `draftReplacementIsFaithful`.
     *
     * ⭐ …and so does a copy whose ADDRESSING this phone cannot prove was empty
     * ([MailRepository.emptiedDraftAddressingIsProvenEmpty]), the second term of that same
     * decision: the row cannot tell a draft with no Cc from a Cc it never read, and the copy behind
     * it is the only thing still holding those addresses.
     */
    private suspend fun destroyReplacedServerDraft(localDraftId: String) {
        // ⛔ **The account the ROW was leased under, and never the one being written as.** Handed
        // `credentials()` — as both callers used to — this destroyed under the account of the
        // identity the "From" picker shows, and that picker covers EVERY account: the id being
        // destroyed and the numbering it was frozen under were read off the other account's draft,
        // so the folder lookup, the destroy and the eviction all left for a server that never
        // issued that id. They failed in silence while `consumeEditingLocalDraft` took the row away
        // correctly under `editingLocalDraftAccountId` — mask lifted, and the draft from BEFORE the
        // edit back at the top of Drafts, for good. ⛔ No fallback on `credentials()`: throwing
        // destroys nothing, and both callers already catch it, draw the banner and give the lease
        // back. The refusal itself is executed in EmptiedLocalDraftTest.
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
            // Read here, like `mailboxId` just above, because establishing it means asking the
            // server and the decision itself stays pure. ⛔ Under the account the ROW was leased
            // under, never the one being written as — the id belongs to that server.
            // ⛔ **And the two guards in front of the read are not a second decision** — each one
            // skips a question whose answer cannot reach the outcome, and hands over `false`,
            // "not proven", which is the only honest word for something never asked. With no
            // server draft the guard's first term has already decided; with a lossy body its
            // second has (`emptiedLocalDraftKeepsServerCopy(id, true, x)` is the same for both
            // values of x, executed in EmptiedLocalDraftTest). ⚠ Unlike `mailboxId`, this read
            // leaves the phone: unguarded it would put a blocking IMAP fetch in front of the one
            // gesture that exists BECAUSE there was no network (#95), for an answer that could
            // change nothing.
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
     *
     * ⛔ **This is not a second way to destroy anything.** A deletion asked for IS the emptying —
     * [destroyReplacedServerDraft], written for "the user emptied it and saved", reached by a
     * button instead of by an empty save. The same plan under the same proofs: the server copy this
     * row replaced is destroyed only where it can be, KEPT and said where this phone never read it
     * whole ([planForEmptiedLocalDraft]), and the local row and its files go last
     * ([destroyThenConsumeEmptiedLocalDraft]). Until this existed the only way out of a draft the
     * phone was keeping was to empty it by hand and save, which nothing on screen said.
     *
     * The acts, in this order, and each one is a defect prevented:
     *
     *  - the lease is READ, not blanked ([heldEditingLocalDraft]). It refuses while a send is in
     *    flight (INV-6: that send consumes the row itself) and on a row the close has already given
     *    back (#127) — which is all this gesture needs of a one-shot take. ⛔ Blanking it up front,
     *    as this was written, is `abandonPlan`'s defect one gesture further along: the composer then
     *    answers "I hold no row" to everything downstream and a failure leaves the row `EDITING`
     *    with nobody left to release it — a ghost no startup sweep takes back, which the first
     *    reopen-and-close uploads: the draft that was just deleted, filed in Drafts;
     *  - the row goes back to the upload worker when nothing was destroyed, and to NOBODY when
     *    something was: [localDraftLeaseAfterDelete], handed the lease AS IT STANDS at the end. The
     *    failure is atomic — everything that throws is in front of any destruction — so a deletion
     *    that failed leaves a draft that is still exactly a draft;
     *  - ⛔ the screen closes on [localDraftDeleted], never on `ComposeState.Done`: Done is the
     *    tern's flight, written for a message that went OUT, and it lands on the inbox instead of
     *    the Drafts list this composer was opened from.
     *
     * ⚠ Stated debt, not fixed here: the failure banner is the SAVE one ("couldn't save the draft"),
     * because a deletion has no banner of its own. It is wrong in wording and right in substance —
     * nothing was destroyed, the draft is still there.
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
     * The lease on this phone's own draft row as this composer holds it, or null when it holds none
     * or may not act — ⛔ **read, never cleared**. Who gets the row back is a decision taken at the
     * END of the gesture ([localDraftLeaseAfterDelete]) and it cannot be taken on a field this
     * function has already emptied: that is exactly how a failed deletion came to leave the row
     * `EDITING` with nobody to release it.
     *
     * It still refuses twice, and both refusals are the useful half of [takeEditingDraft]'s one-shot
     * (#127): during a send, which consumes this very row itself (INV-6), and on a lease the close
     * has already handed back — the X and the trash landing in the same frame.
     */
    private fun heldEditingLocalDraft(): String? {
        if (_state.value is ComposeState.Sending) return null
        return _editingLocalDraftId.value
    }

    /**
     * Say a draft of the PHONE's own store could not be opened because no account resolved (#95).
     *
     * ⛔ No new string, and no new meaning: `compose_draft_load_failed` is what the SERVER reopen
     * answers for this same cause (`draftLoadNoticeFor(offline = false)` resolves to this very id)
     * and what [noticeLocalDraftBusy] says two arms below. It is not "offline": the link may be
     * perfect, no account resolved.
     *
     * ⚠ **What that sentence is worth here, plainly.** The dominant case is transitory — the store
     * is between writes, or the screen opened before an account was current — and "Close this
     * screen and try opening it again" then works. But [credentials] answers null when NO account
     * resolves and the store is empty besides, which is also what a deleted or signed-out account
     * looks like, and there the sentence invites a gesture that will return nothing. Stated debt,
     * not a demonstration: a tenth string across nine locales was judged too dear for a path this
     * narrow, and telling those two apart from here is not free either.
     *
     * ⭐ On [_draftLoadFailed], like every other verdict of this route: that is the flow
     * [draftReopenView] turns into `DeadEnd`, i.e. the one that stops the editor being drawn.
     */
    private fun noticeLocalDraftNoAccount() {
        _draftLoadFailed.value = R.string.compose_draft_load_failed
    }

    /**
     * Say the draft the phone was keeping is not on the phone any more (#95) — the upload worker
     * consumed it between the Drafts list being drawn and the tap landing, or it was thrown away.
     *
     * ⛔ **The one new string of this branch, and it had to be new.** `compose_draft_load_failed`
     * ("… Close this screen and try opening it again") asks for something that CANNOT work: there
     * is no row left to reopen. `compose_draft_offline` would accuse a network that has nothing to
     * do with it. And `compose_prefill_failed` means "the quoted original of a reply is missing" —
     * a third meaning would leave it saying nothing at all. The sentence is true of the dominant
     * case (the uploader took it to the server, where it is in Drafts) and deliberately promises
     * nothing about the other one.
     *
     * ⭐ On [_draftLoadFailed], for the same reason as its two neighbours: a verdict on
     * `_attachmentStatus` is a line of status under an editor that is still drawn, still editable,
     * and still offering Save over a draft this screen does not hold — which is how a second draft
     * gets written.
     */
    private fun noticeLocalDraftGone() {
        _draftLoadFailed.value = R.string.compose_local_draft_gone
    }

    /**
     * Say the draft is on the phone but not this composer's to open right now — its files are being
     * staged, or the worker is uploading it (`localDraftMayBeTakenForEdit`). ⭐ An `EDITING` row is
     * NOT one of these: it is this phone's own stale lease and it is taken back, not refused.
     *
     * ⛔ A DIFFERENT sentence from [noticeLocalDraftGone]'s, and no new string:
     * `compose_draft_load_failed` ("… Close this screen and open it again."), already translated
     * into the nine locales, and here it is exactly true — both states are transitory, and
     * reopening really does work. On a row that is GONE the same words would be an instruction that
     * cannot be obeyed, which is why that one has a sentence of its own.
     *
     * ⭐ On `_draftLoadFailed`, so that ONCE THIS VERDICT IS OUT the editor is not drawn
     * (`draftReopenView`, whose `DeadEnd` arm this reaches while `applied` is still false). That is
     * the point rather than the wording: a blank composer with a live Save over a draft this screen
     * does not hold is the "second draft on the server" the same flow was introduced to end.
     *
     * ⭐ **The debt this used to state is paid.** The time BEFORE the verdict was the other half of
     * the same defect: `_draftLoading` was never raised on the local route, so `draftReopenView`
     * answered `Editor` throughout `credentials()` and the lease take, and Save was live over an
     * empty composer for as long as those took. The LOCAL arm of [prepare] now raises the flag
     * before its coroutine and lowers it from a `finally`, so this verdict is drawn on a screen
     * that was waiting, not on one that was inviting a save. The price is a spinner on a path that
     * is normally instantaneous (a Room read).
     */
    private fun noticeLocalDraftBusy() {
        _draftLoadFailed.value = R.string.compose_draft_load_failed
    }

    /**
     * Take the reopened local draft away — ⛔ **its row AND its staged files** — because the
     * composer has just committed the message somewhere else (#95): queued for sending, scheduled,
     * or emptied out by the user (#69).
     *
     * ⛔ **Never on its own.** On the send routes it is [commitThenConsumeLocalDraft]'s `consume`,
     * so it runs strictly after the outbox (or scheduled) row exists and never on a commit that
     * threw. Left undone, the row stays at the top of Drafts with its "not on the server yet" pill
     * after the message has gone, `SternaApplication` puts it back to `PENDING` at the next launch,
     * and the worker files a copy of an already-sent message in the server's Drafts, for good.
     *
     * The lease is dropped synchronously with it: there is nothing left to give back.
     */
    private suspend fun consumeEditingLocalDraft(id: String) {
        val account = editingLocalDraftAccountId ?: return
        _editingLocalDraftId.value = null
        repo.consumeLocalDraft(account, id)
    }

    /**
     * Leaving the composer without committing the message anywhere — the close button, the back
     * gesture, "Discard" in the unsaved-changes dialog. A message reopened from the outbox has two
     * ways out, and [body] — the RAW text of the editor at the close, never through `bodiesForSend`
     * — decides which:
     *
     *  - the ordinary close gives it straight back to the queue, exactly as it was (#70): closing
     *    an editor is not deleting a mail, and the queue is the only place that copy lives. Deleting
     *    it needs the Outbox screen's delete, which asks first;
     *  - a close over a body this composer LOST and never got back ([closingParksQueuedRow])
     *    PARKS it instead — FAILED at the cap, "editing was interrupted" — because giving it back
     *    re-arms it at 0 and the message leaves in under a second, when the screen has just told her
     *    to close and reopen it. Parked, the row is still there to reopen.
     *
     * Anything else (a new compose, a reply, an undone send) has no queued original and this does
     * nothing to the queue.
     *
     * App-scoped on purpose: the caller pops this screen right after, which kills [viewModelScope].
     */
    fun abandon(body: String, emptying: Boolean = false) {
        // All three answers are decided in one place, in values, and executed here (#95 × #70). A
        // send in flight gives back NOTHING — it owns the message and consumes both rows itself —
        // and outside one the local row is independent: a composer with no queued row still hands
        // its own draft row back to the upload worker. `abandonPlan` is executed in
        // SendDraftTargetTest; every damage it prevents is written there — [emptying], the close
        // that accompanies a deletion and must hand the phone's own row to nobody, and the park,
        // the close over a lost body that must not hand the queued row back to the worker.
        val plan = abandonPlan(
            sending = _state.value is ComposeState.Sending,
            emptying = emptying,
            parkOutbox = closingParksQueuedRow(composerBodyWasLost, body),
            localDraftId = _editingLocalDraftId.value,
            outboxId = editingOutboxId,
        )
        plan.localDraftToRelease?.let { releaseLocalDraftEdit(it) }
        // The row never left the queue — it only sat in EDITING — so giving it back is just flipping
        // its state to QUEUED and re-arming delivery. Nothing is rebuilt, nothing can be lost (#70).
        plan.outboxToRelease?.let { id ->
            editingOutboxId = null
            _editingOutbox.value = false
            appScope.launch {
                runCatching { repo.releaseOutboxEdit(id) }
                    .onFailure { android.util.Log.w("SternaCompose", "couldn't release the edited outbox item", it) }
            }
        }
        // ⛔ The other door, and NEVER releaseOutboxEdit: parked FAILED with its reason, off the
        // worker, reopenable. The lease is dropped synchronously either way.
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
     * Give back the lease taken by [prepareLocalDraft] (#95): the row goes from EDITING to PENDING
     * and its upload is re-armed. The repository refuses to touch a row in any other state, so a
     * save that has already re-written the row PENDING is not fought over.
     *
     * ⛔ App-scoped, for [abandon]'s reason and it is not a precaution: this runs as the screen is
     * popped, and `viewModelScope` is cancelled with it — the write would simply never happen and
     * the draft would sit EDITING until the next launch reverted it.
     *
     * ⛔ [id] is HANDED IN, by [abandonPlan]: read back off the field here, the plan's decision not
     * to release anything during a send could be undone by a single call slipping past it.
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
     * [consumeEditingOutbox], for a save that committed the message somewhere the row's own copy is
     * not: the draft is on the PHONE, the upload never happened, so nothing may be destroyed on its
     * strength and nothing may be sent either.
     *
     * ⛔ Leaving the row as the composer found it (EDITING) was the defect: no screen shows an
     * EDITING row, and the next launch gives it back to the queue and delivers the pre-edit text.
     * [state] comes from `draftSaveParksTheQueuedRowAs`, and the wording the user reads is resolved
     * by the Outbox screen FROM that state (`outboxStateLabel`) — no reason text travels here, and
     * none is written over the row's own `lastError`.
     *
     * ⛔ App-scoped, for the same reason as [abandon] and it is not a precaution: the save runs in
     * `viewModelScope` (through [submit]), and the back gesture during the saving spinner cancels
     * it. A park scheduled on that scope would simply never write, leaving the row EDITING — the
     * defect, restored, on the one path where the user is not looking. The id is read and cleared
     * SYNCHRONOUSLY, before the launch, so ordering against [consumeEditingOutbox] does not depend
     * on when a coroutine is scheduled.
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
     * The composer's field values, kept so a PGP interaction round-trip can retry the send.
     *
     * The read-receipt choice rides here with them, and for the same reason: the OpenPGP dialog
     * round-trip re-enters [sendInternal] with THIS record and nothing else, so a choice left out
     * of it is a choice silently dropped from every signed or encrypted send.
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
        // ⛔ A body this composer LOST never leaves, and never takes the only copy of the text with
        // it (#95 × the resume parcel). The row in `local_drafts` under an offline draft is the ONLY
        // place that text exists, and the commit below destroys it: sent on an empty body, the
        // message goes out reduced to the signature and the draft is gone, in silence, with the undo
        // record built after the consume carrying the same empty body. The decision is executed in
        // `LostBodySendTest`; the guard only has to hand it the RAW body.
        // ⛔ `args.body.text` — the composer's own text, styling aside — NEVER the `textBody` of
        // `bodiesForSend` below: that one carries the signature, is never blank, and would make
        // this line dead code that reads as a guard.
        // ⛔ And ABOVE the re-entry guard: nothing is flagged `Sending` for a send that is refused.
        // ⛔ `holdsStoredDraft` takes ALL THREE stores — the reopened outbox row included: what the
        // refusal says is the whole point of it, and "type it again" is the one instruction that
        // DESTROYS the text on every route this protects.
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
                // Sign/encrypt NOW, while the provider can still show UI — the outbox
                // worker runs headless later. The outbox then only ever holds the
                // signed/encrypted entity.
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
                // Which server draft this send replaces, and under which numbering — ⛔ ONE answer
                // for both, and the same one for the outbox row and for the record an undone send
                // reopens from. For a draft reopened off this phone's own store the composer holds
                // the ROW's id, which names nothing a server can destroy; the server copy the row
                // stands in for is named on the row, and without it the send unmasks that copy and
                // puts the pre-edit text back in Drafts (#95). Executed in SendDraftTargetTest.
                // …and NOT AT ALL when that draft was read under another account than the one
                // this message is going out under (#31 × #63). The "From" picker covers every
                // account, so the copy created below is born on B while the original lives on A:
                // there is no transaction spanning two servers, so the id is not carried and
                // nothing is destroyed — the original stays visible in A's Drafts. Carried, it
                // would reach `performSend` beside THIS row's credentials and expunge a bare
                // number in B's own Drafts. Executed in SendDraftTargetTest.
                val sendTarget = sendDraftTarget(
                    editingDraftId = editingDraftId,
                    editingDraftUidValidity = editingDraftUidValidity,
                    replacedServerDraftId = replacedServerDraftId,
                    replacedServerDraftUidValidity = replacedServerDraftUidValidity,
                ).unlessDraftBelongsElsewhere(draftAccountId = carriedDraftAccount(), writingAccountId = credentials.id)
                // Persist the send held for the undo window; the worker delivers it after.
                //
                // ⛔ And ONLY THEN take the phone's own draft row away, if this composer was
                // reopened from one (#95). The order is the correction, and it is grid rule 1: that
                // row is the only copy of the text and the line below is what makes a second one.
                // Left behind instead, it stays at the top of Drafts with its "not on the server
                // yet" pill after the message has gone, and the next launch re-arms it — the worker
                // then files a copy of an already-sent message in the server's Drafts, for good.
                val id = commitThenConsumeLocalDraft(
                    localDraftId = _editingLocalDraftId.value,
                    commit = {
                        repo.enqueueSend(
                            credentials, recipients, subject, textBody, replyTo, refs,
                            attachments, htmlBody, identity?.name, identity?.email, ccList, bccList,
                            holdMs = SendOutbox.HOLD_MS,
                            pgpMode = mode.takeIf { it != PgpMode.OFF },
                            prebuiltEntity = pgpEntity,
                            // Editing a saved draft (#63): once this send is delivered, the worker
                            // destroys the draft it came from, so Drafts keeps no stale duplicate —
                            // ⛔ and NEVER one of this phone's own draft ids, which names nothing a
                            // server could be asked to destroy (`sendDraftTarget`).
                            draftEmailId = sendTarget.emailId,
                            // …under the numbering frozen when that id was read (#99), and taken
                            // from the SAME answer as the id: the two halves are meaningful only
                            // together. It is persisted with the row because the destroy runs at
                            // DELIVERY: an offline send leaves when the network returns, and the
                            // folder may have been renumbered by then. Handing `null` here would
                            // destroy nothing; handing a fresh read would destroy the wrong message.
                            draftUidValidity = sendTarget.uidValidity,
                            // — and ONLY against proof: the composer's own verdict rides along, and
                            // enqueueSend leaves the id off the row when it (or the addressing read)
                            // cannot show the send reproduces the draft. The message still goes out;
                            // the original then survives in Drafts instead of being destroyed behind
                            // a send that silently dropped what this editor could not carry.
                            bodyIsLossy = editingDraftLossy,
                            // Asked for on this screen, written at DELIVERY time by a headless
                            // worker — so it has to be on the row, not in this process.
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
                    // The styling as the composer held it — NOT re-parsed from the html that went
                    // out, which carries the signature substituted verbatim and the forwarded
                    // original appended and would not parse back (#131).
                    bodyRanges = body.ranges,
                    fromAccountId = _selectedFrom.value?.accountId,
                    fromIdentityEmail = identity?.email,
                    attachments = attachments, inReplyTo = replyTo, references = refs,
                    forwardedText = forwarded?.text, forwardedHtml = forwarded?.html,
                    pgpMode = mode.takeIf { it != PgpMode.OFF }?.name,
                    // ⛔ The same answer as the row above, and deliberately the same VALUE: undoing
                    // this send reopens the composer from THIS record, whose local row has already
                    // been consumed — the server draft it stood in for can only be named here now.
                    // A `local-draft:` id restored here would be handed straight back to the queue.
                    draftEmailId = sendTarget.emailId,
                    // Undoing this send reopens the composer from THIS record: without the frozen
                    // numbering the reopened message could no longer destroy the draft it replaces.
                    draftUidValidity = sendTarget.uidValidity,
                    draftBodyIsLossy = editingDraftLossy,
                    requestReceipt = args.requestReceipt,
                )
                val app = getApplication<Application>()
                // WYSIWYG (#70): every send is queued behind the undo window, but the message only
                // actually leaves once the worker submits it — which has not happened yet while the
                // undo hold is running. So never claim "sent" here: say "Sending…" when there is a
                // connection to hand it to, and "queued" when offline. This stays honest under a VPN
                // killswitch too (transport looks usable but the send is blocked): the real outcome
                // surfaces via the Outbox — a failed send lands there with its failure badge, a
                // delivered one clears. (The queue/retry path itself is unchanged — this only picks
                // the wording.)
                val queuedOffline = !hasUsableNetwork(app)
                outbox.hold(
                    label = app.getString(
                        if (queuedOffline) R.string.status_message_queued
                        else R.string.status_message_sending,
                    ),
                    draft = draft,
                ) {
                    // Undo within the window: drop the queued row so nothing is sent, and nothing
                    // stays parked in the Outbox. Delete the row first (the user-visible artifact),
                    // then cancel its worker; should the cancel be a no-op, a later worker run finds
                    // no row and simply exits.
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
     * Schedule the message to be sent at [sendAtMillis]. Persisted to Room and fired by
     * WorkManager (survives the app closing). Attachments are not carried in v1.
     *
     * Answers whether the gesture was ACCEPTED, and nothing more: the row is written in
     * `viewModelScope`, so `true` says only that this function reached the launch, never that the
     * insert succeeded. The screen needs exactly that much — it confirms with
     * `compose_scheduled_toast`, and every refusal below leaves without scheduling anything, two of
     * them saying why on `_notices`. Announced unconditionally, that toast reads "Scheduled —
     * Mon 1 Sep, 09:00" over a message that was refused, and the next toast contradicts it.
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
        // PgpMode but OFF would go out unsigned/plaintext, #35) and its table carries no attachments
        // yet (so it would send amputated, audit A2/A3). The toolbar disables the button for both;
        // this is the choke point. Only the pgp case has user-facing wording — attachments can't
        // reach here through the UI.
        if (!scheduleSendAllowed(_pgpMode.value, _attachments.value.isNotEmpty())) {
            if (_pgpMode.value != PgpMode.OFF) _notices.tryEmit(R.string.compose_pgp_no_draft)
            return false
        }
        // …and the same refusal as the immediate send, for the same reason: a scheduled send commits
        // just as truly — the row is inserted, its worker booked — and consumes the phone's own
        // draft row on the way out. ⛔ `body.text` is this function's own parameter, the raw one,
        // styling aside; the `textBody` computed below carries the signature and can never be blank.
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
                // The same one answer as the send above: id and numbering together, and the server
                // draft named off the row when this composer was reopened from one (#95) — then
                // withheld WHOLE when the composer cannot show the message reproduces the draft
                // (#63). The scheduled row has nowhere to say so, so the id is the carrier; the
                // numbering leaves with it, because the pair is one decision. Both executed in
                // SendDraftTargetTest.
                val sendTarget = sendDraftTarget(
                    editingDraftId = editingDraftId,
                    editingDraftUidValidity = editingDraftUidValidity,
                    replacedServerDraftId = replacedServerDraftId,
                    replacedServerDraftUidValidity = replacedServerDraftUidValidity,
                ).unlessBodyIsLossy(editingDraftLossy)
                    // …and withheld again, WHOLE, when the draft belongs to another account than
                    // the one this row is written under: a different question from the verdict
                    // above and neither replaces the other. The row carries `accountId =
                    // credentials.id`, so an id of A on it fires against B's server at the hour
                    // chosen — nothing to destroy on JMAP, a bare number in B's Drafts on IMAP.
                    .unlessDraftBelongsElsewhere(draftAccountId = carriedDraftAccount(), writingAccountId = credentials.id)
                // ⛔ Same order as the send above, same reason (#95): the scheduled row is written
                // and its worker booked FIRST, and only then does the phone's own draft row go.
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
                                // A scheduled send of an edited draft still replaces it (#63), but
                                // this row cannot carry the fidelity verdict (no column). So the id
                                // itself is the carrier: written only when the composer's verdict
                                // is clean, withheld — original kept, duplicate in Drafts — when it
                                // is not. ScheduledSendWorker relies on exactly this when it
                                // re-enters enqueueSend with bodyIsLossy = false. ⛔ And never one
                                // of this phone's own ids, which names nothing to destroy — the two
                                // guards answer different questions and neither replaces the other,
                                // which is why both are taken above, on the PAIR.
                                draftEmailId = sendTarget.emailId,
                                // The row waits for hours, so the frozen numbering waits with it
                                // (#99): read at the hour the send fires it would be the number
                                // recorded after a renumbering, and the expunge would land on
                                // another draft. Off the same answer as the id above.
                                draftUidValidity = sendTarget.uidValidity,
                                // The row waits for hours; the request has to wait with it, or the
                                // hand-over to the outbox drops it (ScheduledSendWorker).
                                requestReceipt = requestReceipt,
                            ),
                        )
                        ScheduledSends.enqueue(getApplication(), id, scheduledSendDelayMillis(sendAtMillis, System.currentTimeMillis()))
                    },
                    consume = { local -> consumeEditingLocalDraft(local) },
                )
                // The message now waits in the scheduled table instead; the outbox row it came from
                // is consumed rather than given back, or it would send twice (#70).
                consumeEditingOutbox()
                _state.value = ComposeState.Done
            } catch (t: Throwable) {
                _state.value = ComposeState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
        // Accepted — the coroutine above owns what happens next. A failure inside it lands on
        // `ComposeState.Error`, which the screen already shows; this answer is about the GESTURE.
        return true
    }

    /**
     * The outgoing (text, html) bodies. The body is sent exactly as the composer shows it — the
     * signature is already inside it, inserted when compose opened, so nothing is appended here any
     * more (WYSIWYG). Two things still happen:
     *  - an HTML alternative is ALWAYS produced, signature or not: a lone text/plain body is subject
     *    to format=flowed reflow by some servers (e.g. Stalwart), which joins single newlines on
     *    retrieval and flattens the message to one line. The explicit `<br>` survives that;
     *  - an imported HTML signature is substituted for its plain block in the html alternative, but
     *    only while that block is untouched (see [htmlBodyWithSignature]);
     *  - the styling the user applied (#131) is written into the html alternative and only there:
     *    the text alternative is the body's plain text.
     * For a forward, the carried original is then appended below, identically, to both.
     */
    private suspend fun bodiesForSend(userBody: RichBody, identity: StoredIdentity?): Pair<String, String?> {
        val html = htmlBodyWithSignature(
            userBody, signatureTextOf(identity), signatureHtmlOf(identity),
            settings.signatureDelimiter.first(),
        )
        val fwd = forwarded ?: return userBody.text to html
        return "${userBody.text}\n\n${fwd.text}" to "$html<br><br>${fwd.html}"
    }

    /**
     * Carry a reopened draft's file attachments back into compose (#63), staged exactly like a
     * forward's: JMAP re-uses/stages blobs, IMAP re-stages bytes. Inline images are not carried —
     * the plain-text editor can't reference them. Best-effort per part: one failed download
     * doesn't drop the rest. Returns whether EVERY part came across — a partial carry means a
     * re-save cannot reproduce the draft, so the original must survive it.
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
     * A draft is worth persisting only if it carries real content: a recipient, a non-blank
     * subject, a non-blank body, or at least one attachment (#69). A wholly empty compose does
     * not count — saving one only litters Drafts with an empty shell. Delegates to
     * [draftHasContent] so this save gate and the toolbar's greyed-out Save icon share one rule
     * (no drift).
     */
    private fun hasDraftContent(
        to: String,
        cc: String,
        bcc: String,
        subject: String,
        body: String,
    ): Boolean = draftHasContent(to, cc, bcc, subject, body, _attachments.value.isNotEmpty())

    fun saveDraft(to: String, cc: String, bcc: String, subject: String, body: RichBody, requestReceipt: Boolean) {
        // ⛔ A save is the CAUTIOUS gesture, and on one route it was the destructive one: a queued
        // message reopened by Outbox → Edit and redrawn empty over a resume slot that lost the body
        // is saved as an empty draft, and consumeEditingOutbox() at the foot of this function then
        // deletes the queued row and its durable attachment directory (#95 × #70 × the resume parcel).
        // That row is the only copy of the text; the send route already refuses this and tells her to
        // close and reopen the message, on this same screen, while this button destroyed what she
        // would have reopened. Same sentence, because it is the same true advice.
        // ⛔ HERE and not lower: everything below writes — the empty-draft branch destroys, the
        // submit() block replaces — so a refusal written after any of it refuses nothing.
        // ⛔ The RAW `body` this function was handed, never a body through bodiesForSend: that one
        // carries the signature, is never blank, and would make this line dead code reading as a guard.
        // ⛔ And it is NOT lostBodySendWording: that answers what a SEND owes, is asked exactly
        // twice, and a third call would be a second answer to a question this route never asks. The
        // decision is executed in LostBodySendTest; the guard only has to hand it the three terms.
        if (lostBodySaveDestroysQueuedRow(bodyWasLost = composerBodyWasLost, body = body.text, holdsQueuedRow = editingOutboxId != null)) {
            _notices.tryEmit(R.string.compose_body_lost_reopen_draft)
            return
        }
        // The plaintext guarantee, enforced where the upload actually happens (#35): an encrypted
        // message never leaves a readable copy on the server, draft included. The toolbar hides
        // Save/Schedule and the leave dialog only offers Discard while encrypting, so this is
        // belt-and-braces — but it is the one choke point every save route goes through, so a
        // future caller cannot reintroduce the leak. Refuse and say so rather than saving: the
        // composer stays open with the text intact, nothing is lost and nothing is uploaded.
        if (!draftSaveAllowed(_pgpMode.value)) {
            _notices.tryEmit(R.string.compose_pgp_no_draft)
            return
        }
        // Empty by the #69 rule: persist nothing. A brand-new compose leaves no trace at all (no
        // server create, no local row, no outbox entry); an opened draft the user has emptied has
        // its original deleted so no empty shell lingers in Drafts (or reappears on sync). Either
        // way the screen then closes exactly as a normal save would.
        if (!hasDraftContent(to, cc, bcc, subject, body.text)) {
            val original = editingDraftId
            // The numbering frozen when that id was read — carried, never re-read (#99).
            val originalUidValidity = editingDraftUidValidity
            // …and the ACCOUNT both were read from, captured with them and for the same reason: the
            // expunge below is addressed to it, and never to the identity the "From" picker is
            // showing — that picker covers every account, and the id and the numbering above belong
            // to the one this draft was opened from (#31 × #63).
            val originalAccountId = editingDraftAccountId
            // ⛔ The phone's own row is handed back to NOBODY on this close, and the "nobody" is
            // said to abandon() rather than by blanking the field: abandon() would hand the row
            // back PENDING and RE-ARM its upload at the row's own instant — which for a draft saved
            // with no network is now — so the pre-edit text of the draft the user has just emptied
            // would race the delete below to the server. Blanking the field instead said it in a
            // place that means something else, and a submit() that FAILS then left the row EDITING
            // with nobody left to release it: never uploaded again, and nothing on screen about it.
            // The lease therefore stays armed here; the row is destroyed a few lines down, or the
            // next close gives it back for real.
            // Nothing is being saved, so a message reopened from the outbox is not being moved
            // anywhere: it goes back to the queue rather than evaporating on an empty save (#70).
            // The raw body goes with it: lost and empty, the row is parked, not queued — though the
            // guard at the top of this function has already refused that save over a queued row.
            // ⛔ Its TEXT and not the RichBody: abandon() asks this body one question only — is it
            // lost, i.e. does closingParksQueuedRow() park the queued row — and that answer is read
            // off the characters. No style can change it, so none is being dropped here (#131).
            abandon(emptying = true, body = body.text)
            if (original == null) {
                _state.value = ComposeState.Done
            } else if (draftOpenRoute(original) == DraftOpenRoute.LOCAL) {
                // ⛔ NOT repo.discardDraft: for an id no server ever issued, `destroyableUnderNumbering`
                // answers an empty list and the IMAP route destroys NOTHING — the screen closed as a
                // success while the emptied draft stayed in the queue and went up on the next
                // network. Its row and its staged files go together instead (#95 × #69) — ⛔ and so
                // does the SERVER draft it was standing in front of, or that one is unmasked by the
                // very act of taking the row away and comes back with its pre-edit text for good.
                // ⛔ And the credentials submit() resolved are NOT passed on: they belong to the
                // "From" identity on show, while the row and the server draft it stands in front
                // of belong to the account this draft was opened from.
                submit(to) { _, _ -> destroyReplacedServerDraft(original) }
            } else {
                // ⛔ …and the SERVER route asks the same question the route above asks, because it
                // is the same draft and the same expunge: a draft this composer never read WHOLE
                // keeps its server copy (emptiedServerDraftIsKept, the twin of the decision
                // planForEmptiedLocalDraft takes). "Empty" here is draftHasContent's answer, and it
                // counts neither an inline image nor a calendar part — so a draft written elsewhere
                // and made of one inline image is DRAWN empty, and destroying it on that reading
                // expunges, for ever, what was never shown. The word is owed, and it is emitted
                // INSIDE the lambda: submit() reaches Done after this returns, and a notice posted
                // to a screen already gone is a notice nobody reads.
                // ⛔ …and the same is true of the ADDRESSING, which is the second term of that
                // decision and the one both routes were deciding without: the copy about to be
                // expunged is the only thing that still holds a Cc this composer never managed to
                // display, and the ordinary save has refused to destroy a copy on less than a
                // server-side proof since #63. Here there is not even a duplicate left behind.
                // ⛔ And the credentials submit() resolved are NOT what it goes out under: they
                // belong to the "From" identity on show, while the id and the numbering being
                // expunged were read off the account the draft was OPENED from — hence `{ _, _ ->`
                // and the resolution below, from the frozen account, with no fallback.
                submit(to) { _, _ ->
                    // ⛔ The account the draft was READ FROM, resolved here and nowhere else.
                    // `?: credentials` appended to this call is the whole defect back: an id
                    // JMAP's destroyAll spares (the emptied draft returns on the next sync), or
                    // an IMAP number expunged in the OTHER account's Drafts when the two
                    // UIDVALIDITYs happen to agree — and its cached bodies dropped even when
                    // they do not. Gone account, no credentials: this throws, submit() catches
                    // it, the banner is drawn and NOTHING is destroyed. The refusal itself is
                    // executed in EmptiedServerDraftTest.
                    // ⛔ A local fun, and NOT a val computed up front: for a draft this composer
                    // never read whole the copy is kept whatever the account says, and resolving
                    // an account that was removed from the phone during the edit would turn that
                    // true notice into a "could not save" banner over a gesture that failed at
                    // nothing. It is asked only where an answer is going to be used.
                    fun destroyingCredentials(): AccountCredentials =
                        credentialsDestroyingEmptiedServerDraft(
                            openedUnderAccountId = originalAccountId,
                            composingAsAccountId = _selectedFrom.value?.accountId ?: accountId,
                            lookup = { store.credentials(it) },
                        ) ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                    // ⛔ NOT a decision taken here — the decision is the one function below, and
                    // this is the term it reads. `!editingDraftLossy &&` skips a server read whose
                    // answer cannot change the outcome (emptiedServerDraftIsKept(true, x) is true
                    // for both values of x, executed in EmptiedServerDraftTest), and what it hands
                    // over when it skips is `false` — "not proven", which is exactly what a
                    // question never asked deserves. ⛔ Under the account the draft was OPENED
                    // from: `credentials` here would read the addressing of another server.
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
            // Keep a reply draft threaded, carry the attachments the chips are showing into the
            // saved copy, and replace the draft being edited (#63) rather than piling up a copy
            // per save — unless the copy can't hold everything the original did, in which case
            // the repository keeps the original and says so here. Carry the composer's chosen From
            // so a delegated sub-account's draft is saved under the identity it will send as, never
            // the login's address (issue #31).
            val identity = selectedIdentity()
            // What this save REPLACES: the id frozen at the open and the numbering it belongs to,
            // as ONE pair (#99) — and NOTHING AT ALL when that pair was read under another account
            // than the one being saved as (#31 × #63). Unlike every other account mismatch on this
            // branch the destroy cannot simply be re-addressed: the new copy is created on the
            // account being written as, while the original stays on the one it was opened from, and
            // no transaction spans two servers. So the original is kept — visible in its own
            // Drafts, deletable by hand — and the duplicate is the price. That is already what
            // happens, by accident (`draftAddressingIsCarried` finds nothing under B; JMAP's
            // destroyAll spares an unknown id); here it is stated, with its reason. ⛔ The two
            // halves come out of the SAME answer, never one from here and one from the field.
            val replaces = SendDraftTarget(emailId = editingDraftId, uidValidity = editingDraftUidValidity)
                .unlessDraftBelongsElsewhere(draftAccountId = carriedDraftAccount(), writingAccountId = credentials.id)
            val outcome = repo.saveDraft(
                credentials, recipients, subject, body.text,
                // ⛔ The styling as a SECOND part, decided by the one pure function every route
                // uses (`draftHtmlToSave`): null for a body with no styling, so a plain draft is
                // stored, uploaded and appended exactly as it was before #131. ⛔ NOT
                // `htmlBodyWithSignature`, which is the SEND's html — it substitutes an imported
                // signature verbatim and would not parse back on reopen, so the styling would
                // survive one save and vanish on the next.
                html = draftHtmlToSave(body),
                cc = parseAddrs(cc), bcc = parseAddrs(bcc),
                inReplyTo = inReplyTo, references = references,
                replacesEmailId = replaces.emailId,
                replacesUidValidity = replaces.uidValidity,
                attachments = _attachments.value,
                bodyIsLossy = editingDraftLossy,
                composerBodyWasLost = composerBodyWasLost,
                fromName = identity?.name, fromEmail = identity?.email,
                requestReceipt = requestReceipt,
            )
            // ⛔ Which outcomes are worth a word is decided in the data layer and EXECUTED there
            // (draftSaveNeedsNotice): a draft kept on the phone because the network was down is a
            // successful save (#95), and saying anything about it here would be the reported
            // symptom with softer wording.
            if (draftSaveNeedsNotice(outcome)) {
                _notices.tryEmit(R.string.compose_draft_original_kept)
            }
            // ⛔ A queued row the save did NOT put on the server is PARKED, and parked FIRST: left
            // as the composer found it (EDITING) it is on no screen at all, and the next launch
            // hands it back to the queue and sends the pre-edit message with no undo window — after
            // the user tapped "save as draft". Where it goes is decided in the data layer and
            // EXECUTED here (draftSaveParksTheQueuedRowAs). Before the consume, because that one
            // clears editingOutboxId (#95 × #70).
            draftSaveParksTheQueuedRowAs(outcome)?.let { parkEditingOutbox(it) }
            // ⛔ The queued row it came from is consumed — its row AND its staged files — only once
            // the draft has really reached Drafts, and that too is decided in the data layer and
            // EXECUTED here (draftSaveConsumesTheQueuedRow). Unconditional, a save made with no
            // network destroyed the only copy the user could still see: KEPT_ON_DEVICE means the
            // draft is on the phone alone, and nothing displays it yet (#95 × #70).
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
            // The success as a VALUE, not as a position in the try: what the `finally` below does
            // with the row depends on whether `op` came back, and nothing else can tell it.
            var saved = false
            try {
                val credentials = credentials() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                op(credentials, parseAddrs(to))
                saved = true
                _state.value = ComposeState.Done
            } catch (t: Throwable) {
                // Saving a draft, not sending: the error banner must say so (#63 — a failed
                // draft save used to read "couldn't send", pointing users at the wrong step).
                _state.value = ComposeState.Error(t.message ?: t.javaClass.simpleName, whileSaving = true)
            } finally {
                // ⛔ A save gives the phone's own row back too, and until this line nothing did
                // (#95 × #31). `abandon()` is the only other release and only the CANCEL of the
                // screen calls it; this route leaves on ComposeState.Done, on the understanding
                // that the save rewrote the row itself. It did — unless the "From" picker was
                // moved to another account, which it may be for any draft since that picker covers
                // every account: `unlessDraftBelongsElsewhere` then answers SendDraftTarget(null,
                // null), a NEW row is minted under the account being written as, and the reopened
                // row stays EDITING for ever, which no startup sweep takes back.
                // ⛔ HERE and not in saveDraft's lambda, on purpose. submit() has exactly three
                // callers and all three are in saveDraft; send() and scheduleSend() do NOT come
                // through it, each having its own viewModelScope.launch. So the risk
                // abandonPlan(sending = true) closes elsewhere — re-arming the upload of a row a
                // send in flight is already consuming — cannot arise at this point, and that is
                // the reason the branching sits at this level rather than higher up.
                // On both empty-save routes the lease is already null when this is reached:
                // abandon(emptying = true) does not clear it (that is its whole object) and
                // consumeEditingLocalDraft does.
                localDraftLeaseAfterSave(_editingLocalDraftId.value, saved)?.let { releaseLocalDraftEdit(it) }
            }
        }
    }

    /**
     * Initial fields for a reply/reply-all/forward of [original]. [quoteBody] is set false when the
     * original's full body couldn't be loaded (offline reply to a never-opened mail): the To/Subject
     * headers are still prefilled from the cached row, but the quoted body is skipped rather than
     * quoting the truncated preview. Ignored for a forward (its body is empty here anyway).
     */
    private suspend fun buildPrefill(
        original: Email,
        mode: String?,
        selves: Collection<String>,
        quoteBody: Boolean = true,
    ): DraftFields {
        // Resolved by SUSPENDING on the stored setting (#120), like the signature settings in
        // [replyBody]: a composer opened moments after launch must not fall back to the default
        // while DataStore is still opening — with the switch on, that beat would write local time.
        val zone = resolveOutgoingDateZone(settings.quotedDatesUtc)
        val opening = composeOpening(mode)
        return when (opening) {
            // Same fields for both forwards: what differs (carried inline vs. attached as .eml)
            // happens in prepare(), after this prefill.
            ComposeOpening.FORWARD, ComposeOpening.FORWARD_ATTACHMENT -> DraftFields(
                to = "",
                subject = withPrefix(original.subject, opening.subjectPrefix),
                // The editable body starts empty (just the user's note, plus the signature when the
                // "signature on replies and forwards" setting is on); the original is carried separately
                // to send time so its formatting survives. See [buildForwarded].
                body = replyBody(""),
            )
            ComposeOpening.REPLY_ALL -> DraftFields(
                to = replyAllRecipients(original, selves),
                subject = withPrefix(original.subject, opening.subjectPrefix),
                body = replyBody(if (quoteBody) quote(original, zone) else ""),
                // Travels with the body above, and says exactly what that body holds: when the
                // original was not cached, THIS is how the quote reaches the screen (there is no
                // out-of-band `replyQuote` on that path), so it is the only thing that can let the
                // composer keep quiet about a quote it is showing after a process death.
                quoted = quoteBody,
            )
            ComposeOpening.REPLY -> DraftFields(
                to = replyRecipient(original),
                subject = withPrefix(original.subject, opening.subjectPrefix),
                body = replyBody(if (quoteBody) quote(original, zone) else ""),
                // Same claim as the reply-all above, and `quoteBody` and not `true`: when the full
                // body could not be loaded the quote was skipped, the body carries none, and
                // claiming otherwise would silence a failure the reader must hear about.
                quoted = quoteBody,
            )
        }
    }

    /**
     * The quoted original for a reply: a localised attribution line with the date formatted for the
     * device (it used to paste the raw ISO timestamp and an English "On …, … wrote:"), then the
     * original prefixed with "> " — cut at the sender's signature delimiter, so a long exchange does
     * not accumulate one signature per round (D4).
     *
     * [zone] is the clock the attribution's date speaks — [resolveOutgoingDateZone], suspended on
     * the quoted-dates-in-UTC setting (#120). A parameter WITHOUT a default, on purpose: quote()
     * has three callers (two in [buildPrefill], one on the background-enrichment path), and a
     * default zone here would let a new or forgotten caller leak the device's time zone silently.
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
     * Prebuild the forwarded-original blocks (text + cleaned html) carried to send time, and
     * re-stage the original's inline images and file attachments as outgoing parts so the recipient
     * receives them. Inline images keep their Content-ID (so the forwarded HTML's `<img cid:>` still
     * resolves); a cid whose image fails to download is neutralised to "[image]" rather than broken.
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
        // forward opened looking complete while it was short of what the original carried. Say so —
        // same reflex as [carryDraftAttachments] — rather than sending an amputated forward silently.
        // (Inline images are handled above: a missing one is neutralised to "[image]", not dropped.)
        if (carried < fileParts.size) _notices.tryEmit(R.string.compose_forward_attachment_dropped)

        val app = getApplication<Application>()
        return buildForwardedBlocks(
            from = o.from.joinToString { it.display() },
            subject = o.subject.orEmpty(),
            date = MailDates.formatFull(o.receivedAt, resolveOutgoingDateZone(settings.quotedDatesUtc)),
            to = o.to.joinToString { it.display() },
            // The whole original is forwarded, signature included: it is the message being passed
            // on, not a quote of it.
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
     * Stage outgoing-attachment bytes the same way for a picked file or a carried forward part:
     * IMAP writes a cache temp file (read back to build the MIME), JMAP uploads a blob. [disposition]
     * is "inline" with a [cid] for a carried inline image, else "attachment".
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
        // IMAP has no blob store; and with PGP on, JMAP also stages locally — the
        // attachment travels INSIDE the signed/encrypted entity, so uploading a
        // plaintext blob would leak it to the server for nothing.
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
     * Build the PGP/MIME entity for the message: assemble the exact inner MIME
     * entity (bodies + attachments — the same builder normal sends use), then
     * detached-sign (SIGN) or encrypt to all recipients + self, signed (ENCRYPT) or unsigned
     * (ENCRYPT_UNSIGNED) — the split is [pgpEncryptArgs] and nothing else.
     * Throws [PgpInteractionNeeded] when the provider wants the user first.
     *
     * ⛔ [mode] is a PARAMETER, and `_pgpMode.value` must never be read in here. The send captures
     * the mode ONCE and that one value gates the entity, the signer and the outbox row alike.
     * Read again in here, it could answer something else: `pgp.findKeys` below suspends on a
     * round-trip to the provider, and the mode moves under it — deriveAutoMode drops an untouched
     * encrypt-by-default lock to OFF the moment a recipient turns out to have no key, and the
     * address fields stay editable while the send is in flight. The message would then go out
     * UNSIGNED while the composer had said "encrypted and signed": the one property this mode
     * exists for, decided by which coroutine resumed first.
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
        // ⛔ NAMED FOR THE ROLE THAT SURVIVES BOTH MODES. This key is the signer AND a recipient
        // (encrypt-to-self, so the Sent copy can be reopened). Calling it "signKeyId" is what makes
        // "drop the signer" read as "drop this key", which would take it out of the recipients too
        // and make every unsigned encrypted message unreadable, for ever. See [pgpEncryptArgs].
        val selfKeyId = account.pgpSignKeyId

        // Attachment bytes must be local to ride inside the entity. Staged files
        // (IMAP, or JMAP once PGP was on) read back directly; a blob attached
        // BEFORE the user enabled PGP is fetched back once.
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
        // ⭐ The subject goes in TWICE, and only one of them is this one. The message's real
        // `Subject:` is written by `OutgoingMime.build` on the envelope, in the clear, unchanged —
        // the `subject = ""` just below is this throwaway carrier's own, and the entity built from
        // it never grows an envelope. What [protectedSubject] adds is a copy INSIDE the bytes about
        // to be encrypted (RFC 9788 § 3), so a recipient can tell that the subject they were shown
        // is the subject that was sent. Null on SIGN and on a blank subject; the decision is the
        // pure function of that name, executed by ProtectedSubjectTest.
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
                // selfKeyId is a recipient in BOTH modes (encrypt-to-self, the readable Sent copy);
                // it is the signer only in ENCRYPT. ⛔ Do not rebuild either array here.
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
