package app.sterna.send

import app.sterna.core.data.mail.MailRepository
import app.sterna.core.jmap.model.EmailBodyPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI façade for the undo-send window over the persistent outbox. The send itself is now a queued
 * outbox row delivered by [OutboxWorker] (so it survives the app being killed); this only drives
 * the cancellable "Sent · Undo" snackbar for a few seconds. If the user undoes in time, [undo]
 * runs the supplied cancel action (drop the queued row) and hands the draft back via [restored]
 * so compose can reopen with it intact rather than dropping the message.
 */
class SendOutbox(private val scope: CoroutineScope) {
    data class Pending(val token: Long, val label: String)

    /** Everything needed to reopen compose if the user undoes a send. */
    data class ComposeDraft(
        val to: String,
        val cc: String,
        val bcc: String,
        val subject: String,
        val body: String,
        val fromAccountId: String?,
        val fromIdentityEmail: String?,
        val attachments: List<EmailBodyPart>,
        val inReplyTo: List<String>,
        val references: List<String>,
        /** For an undone forward: the carried original (text + html) so resending keeps it. */
        val forwardedText: String? = null,
        val forwardedHtml: String? = null,
        /** The PGP mode the message carried (#35/#70): a signed/encrypted item reopens as such. */
        val pgpMode: String? = null,
        /** The saved draft this message was edited from (#63), so re-sending still replaces it. */
        val draftEmailId: String? = null,
        /**
         * The IMAP numbering [draftEmailId] was frozen under (#99), carried so a message that comes
         * back to the composer — an undone send, a queued item reopened — still knows which
         * numbering its draft id belongs to. Dropped here, the next send freezes nothing and the
         * draft it replaces is left behind in Drafts.
         *
         * ⛔ **No default**, unlike the fields above it and for the reason this class is dangerous:
         * a construction site that leaves it out compiles, reads as complete, and silently answers
         * `null`. The Outbox screen's Edit is exactly such a site, and nothing but the compiler
         * watches it — so the compiler is what watches it. Same rule as
         * [app.sterna.core.data.mail.MailRepository.OutboxDraft.draftUidValidity].
         */
        val draftUidValidity: Long?,
        /**
         * Whether the composer could not reproduce [draftEmailId]'s content — the verdict that
         * decides whether delivery may destroy it (#63), carried WITH the id: an undone send used
         * to restore the id while the verdict silently reset to a clean false, and re-sending
         * destroyed an original the first send had spared. No default on purpose: a construction
         * site that cannot answer must say `true` (unknown, which forbids the destroy), and a
         * site allowed to skip it would be answering the destructive `false` for everyone.
         */
        val draftBodyIsLossy: Boolean,
        /**
         * Set only when the draft came out of the outbox (#70): the id of the queued row, which
         * stays in the queue marked EDITING while this composer holds it. Closing the composer
         * gives it back ([MailRepository.releaseOutboxEdit]); sending/saving/deleting consumes it.
         * Null for an undone send — that message lives only on this screen (the row is gone).
         */
        val editingOutboxId: Long? = null,
        /** Whether the message asks for a read receipt (RFC 8098), so an undone send reopens asking. */
        val requestReceipt: Boolean = false,
    )

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private val _restored = MutableStateFlow<ComposeDraft?>(null)
    /** A draft handed back when the user undoes a send, for compose to reopen with. */
    val restored: StateFlow<ComposeDraft?> = _restored.asStateFlow()

    private var lastJob: Job? = null
    private var heldDraft: ComposeDraft? = null
    private var heldUndo: (suspend () -> Unit)? = null
    private var counter = 0L

    /**
     * Show the undo affordance for [holdMs] (matching the queued item's hold window). After it
     * elapses the snackbar clears and the worker sends; [onUndo] cancels the queued send if the
     * user taps Undo first, and [draft] is kept so compose can reopen.
     */
    fun hold(label: String, draft: ComposeDraft, holdMs: Long = HOLD_MS, onUndo: suspend () -> Unit) {
        val token = ++counter
        _pending.value = Pending(token, label)
        heldDraft = draft
        heldUndo = onUndo
        lastJob = scope.launch {
            delay(holdMs)
            if (_pending.value?.token == token) {
                _pending.value = null
                heldDraft = null
                heldUndo = null
            }
        }
    }

    /** Cancel the held send (drop the queued row) and hand its draft back to reopen compose. */
    fun undo() {
        lastJob?.cancel()
        _pending.value = null
        val undoAction = heldUndo
        heldUndo = null
        heldDraft?.let { _restored.value = it }
        heldDraft = null
        if (undoAction != null) scope.launch { undoAction() }
    }

    /** Hand a draft to compose to reopen with (e.g. when editing a queued/failed outbox item). */
    fun reopen(draft: ComposeDraft) {
        _restored.value = draft
    }

    fun consumeRestored() {
        _restored.value = null
    }

    companion object {
        const val HOLD_MS = 5_000L
    }
}

/**
 * The composer draft a queued outbox item is reopened with (#70), as a pure function so a JVM test
 * can run the mapping the app runs: `OutboxViewModel.edit` is an `AndroidViewModel` method behind
 * WorkManager and Room, so nothing could otherwise see a field left behind here.
 *
 * ⚠ The hand-over is the whole risk. Re-editing a queued message enqueues a NEW row from the
 * composer's state, so any per-message choice this mapping drops is not merely absent from the
 * screen — it is cancelled. That is the PGP mode, the draft being replaced, and the read receipt.
 */
internal fun composeDraftOf(draft: MailRepository.OutboxDraft): SendOutbox.ComposeDraft =
    SendOutbox.ComposeDraft(
        to = draft.to,
        cc = draft.cc,
        bcc = draft.bcc,
        subject = draft.subject,
        body = draft.body,
        fromAccountId = draft.fromAccountId,
        fromIdentityEmail = draft.fromEmail,
        attachments = draft.attachments,
        inReplyTo = draft.inReplyTo,
        references = draft.references,
        pgpMode = draft.pgpMode,
        draftEmailId = draft.draftEmailId,
        draftUidValidity = draft.draftUidValidity,
        // A persisted outbox row carries no fidelity verdict (no column, deliberately — see
        // [SendOutbox.ComposeDraft.draftBodyIsLossy]): reopened, the verdict is unknown, and an
        // unknown is lossy. Re-sending still goes out whole; only the destroy is refused, so the
        // draft it replaced survives in Drafts as a duplicate.
        draftBodyIsLossy = true,
        editingOutboxId = draft.outboxId,
        requestReceipt = draft.requestReceipt,
    )
