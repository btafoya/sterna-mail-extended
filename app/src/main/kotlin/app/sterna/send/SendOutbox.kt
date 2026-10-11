package app.sterna.send

import app.sterna.core.data.mail.MailSender
import app.sterna.core.data.text.Block
import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.Link
import app.sterna.core.data.text.Span
import app.sterna.core.data.text.richBodyFrom
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.ui.compose.splitAssembledForward
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI façade for the undo-send window over the persistent outbox. The send itself is a queued outbox
 */
class SendOutbox(private val scope: CoroutineScope) {
    data class Pending(val token: Long, val label: String)

    data class ComposeDraft(
        val to: String,
        val cc: String,
        val bcc: String,
        val subject: String,
        val body: String,
        /**
         * The inline styling [body] carries (#131).
         */
        val bodyRanges: Map<Inline, List<Span>> = emptyMap(),
        /** The list blocks [body] carries (#131), beside the spans; empty is the honest answer. */
        val bodyBlocks: List<Block> = emptyList(),
        /** The links [body] carries (#131), for the same reasons; empty is the honest answer. */
        val bodyLinks: List<Link> = emptyList(),
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
         * The IMAP numbering [draftEmailId] was frozen under (#99), so a message that comes back to
         * back to the composer (an undone send, a queued item reopened) still knows which
         * numbering its draft id belongs to. Dropped here, the next send freezes nothing and the
         * draft it replaces is left behind in Drafts.
         */
        val draftUidValidity: Long?,
        /**
         * Whether the composer could not reproduce [draftEmailId]'s content, the verdict that
         * decides whether delivery may destroy it (#63), carried with the id so that an undone send
         * cannot restore the id while the verdict resets to a clean false.
         */
        val draftBodyIsLossy: Boolean,
        /**
         * Set only when the draft came out of the outbox (#70): the id of the queued row, which
         * stays in the queue marked editing while this composer holds it. Closing the composer
         * gives it back ([MailSender.releaseOutboxEdit]); sending/saving/deleting consumes it.
         */
        val editingOutboxId: Long? = null,
        /** Whether the message asks for a read receipt (RFC 8098), so an undone send reopens asking. */
        val requestReceipt: Boolean = false,
        val quotesDecrypted: Boolean = false,
    )

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private val _restored = MutableStateFlow<ComposeDraft?>(null)
    val restored: StateFlow<ComposeDraft?> = _restored.asStateFlow()

    private var lastJob: Job? = null
    private var heldDraft: ComposeDraft? = null
    private var heldUndo: (suspend () -> Unit)? = null
    private var counter = 0L

    /**
     * Show the undo affordance for [holdMs]. After it elapses the snackbar clears and the worker
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

    fun undo() {
        lastJob?.cancel()
        _pending.value = null
        val undoAction = heldUndo
        heldUndo = null
        heldDraft?.let { _restored.value = it }
        heldDraft = null
        if (undoAction != null) scope.launch { undoAction() }
    }

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
 */
internal fun composeDraftOf(draft: MailSender.OutboxDraft): SendOutbox.ComposeDraft {
    // The row's html first, its text as the fallback (#131): a queued message this app styled
    // reopens exactly as it was written, while a forward's html and an imported HTML signature do
    val split = splitAssembledForward(draft.body, draft.htmlBody)
    val rich = if (split != null) {
        richBodyFrom(split.noteHtml) { split.noteText }
    } else {
        richBodyFrom(draft.htmlBody) { draft.body }
    }
    return SendOutbox.ComposeDraft(
        to = draft.to,
        cc = draft.cc,
        bcc = draft.bcc,
        subject = draft.subject,
        body = rich.text,
        bodyRanges = rich.ranges,
        bodyBlocks = rich.blocks,
        bodyLinks = rich.links,
        fromAccountId = draft.fromAccountId,
        fromIdentityEmail = draft.fromEmail,
        attachments = draft.attachments,
        inReplyTo = draft.inReplyTo,
        references = draft.references,
        forwardedText = split?.forwarded?.text,
        forwardedHtml = split?.forwarded?.html,
        pgpMode = draft.pgpMode,
        draftEmailId = draft.draftEmailId,
        draftUidValidity = draft.draftUidValidity,
        // A persisted outbox row carries no fidelity verdict, so reopened it is unknown, and an
        // unknown is lossy: re-sending still goes out whole, only the destroy is refused.
        draftBodyIsLossy = true,
        editingOutboxId = draft.outboxId,
        requestReceipt = draft.requestReceipt,
    )
}
