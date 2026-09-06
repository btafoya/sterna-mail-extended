package app.sterna.ui.message

import androidx.annotation.StringRes
import app.sterna.R
import app.sterna.core.data.mail.MessageCrypto
import app.sterna.core.data.mail.ReadReceiptHeader
import app.sterna.core.data.mail.ReadReceiptPreview
import app.sterna.core.jmap.model.Email

/**
 * Whether a message that asked for a read receipt (RFC 8098) may raise the question — ONE decision,
 * written as a function so it can be RUN, and so that the single place allowed to take it is a
 * single call rather than a handful of `if`s grown into a ViewModel.
 *
 * Nothing here sends anything, and nothing here draws anything. It answers "is there a question
 * to ask?" and hands back the very request that would be answered if the reader says yes.
 *
 * The two things it exists to keep apart:
 *
 *  1. **A setting that has not loaded yet is not a setting that is off.** [ReadReceiptSetting] has
 *     three values for that reason. Modelled as a boolean, DataStore's late answer and the
 *     reader's own "no" would be the same `false`, and the guard protecting the overwhelming
 *     majority — everyone who never turned this on — would hold by coincidence of a default rather
 *     than by decision. See `c14fbef3`, where two settings drove an action and waited on the wrong
 *     side of it.
 *  2. **Being marked read is not being read.** `MailRepository.setRead` / `setReadAll` have NINE
 *     callers: a swipe, a multi-select, "mark all read", a notification action, an
 *     archive-that-marks-read, a Sieve rule, a `$seen` arriving from another device, the repo's own
 *     open path — and the reader settling on the message. Only the last one is a person looking at
 *     a message. `SECURITY.md` promises that nothing leaves on open ("This prevents tracking pixels
 *     and read receipts on open"); offering a receipt for a swipe would contradict that sentence
 *     word for word. [ReadMarking] is how the caller says which of the nine it is, and only one
 *     value passes.
 */
enum class ReadReceiptSetting {
    /**
     * DataStore has not answered yet. NOT "off": nothing is offered, and nothing is remembered
     * either — the next message settled on asks the question again, by which time the answer has
     * arrived. Losing the offer costs a receipt that was never owed; treating this as "on" would
     * put the question in front of every reader who never asked for it.
     */
    NOT_LOADED,

    /** Read and off (the default): the header is ignored, no banner, no menu entry, no trace. */
    OFF,

    /**
     * Read and on: the app ASKS. Not "sends" — there is deliberately no third position. Turning
     * the switch on is consent to be asked, never consent to answer.
     */
    ON,
}

/**
 * How a message came to be marked read. The parameter that carries "a person had it on screen",
 * which no boolean called `read` can carry.
 *
 * Do not add a value that means "close enough". The eight non-reader ways a message becomes read
 * are all [OTHER], and they are indistinguishable from each other for this purpose: none of them
 * is a person reading.
 */
enum class ReadMarking {
    /**
     * The reader's pager SETTLED on this message — it is the front-most page, in front of a
     * person — and the message was still unread until that moment. The unread → read transition,
     * and the only one a human made.
     */
    READER_SETTLE_UNREAD,

    /**
     * The reader settled on a message that was ALREADY read. No transition, so nothing is
     * offered: reopening a message must not re-ask, which is what keeps this work free of a
     * "already answered / already refused" column, its migration and its resync. Accepted
     * residual case: marking a message unread again and reopening it does ask again.
     */
    READER_SETTLE_ALREADY_READ,

    /**
     * Every other way a message becomes read: a swipe in the list, unfolding a conversation, a
     * multi-select, "mark all read", a notification's "Mark read" action, the automatic marking on
     * archive/delete, a Sieve `addflag "\\Seen"`, and a `$seen` that arrived FROM THE SERVER
     * because another device read it (`NewMailNotifier`). Nobody read anything here — not on this
     * phone, not now.
     */
    OTHER,
}

/**
 * What would be answered, captured whole at the moment the question is raised.
 *
 * Captured rather than read again when the reader taps: the reader is a pager over live state, and
 * the message behind a banner can be reloaded (a settle, a refresh, a swipe). Read a second time,
 * the send would answer for the neighbouring message — or for none. The same reasoning, and the
 * same failure, as [PendingUnsubscribe].
 *
 * [deliveredTo] is the address of THIS account the message arrived at, and it is carried rather
 * than worked out later: `MailRepository.sendReadReceipt` takes it as a parameter precisely so an
 * account with several aliases does not answer a stranger under a name they never wrote to.
 */
data class ReadReceiptRequest(
    val receiptTo: List<String>,
    val originalSubject: String?,
    val originalMessageId: String?,
    val deliveredTo: String?,
)

/**
 * What [email] would have answered, had it been read by a person, at [deliveredTo], about
 * [outgoingSubject].
 *
 * THIS REQUEST LEAVES THE DEVICE. It becomes a `Subject:` header and a body line in a mail
 * sent to an address the SENDER chose (`Disposition-Notification-To`), and a copy of it stays in
 * Sent. So the subject it carries is the one from the COVER — the envelope — handed in by the
 * caller, and never [Email.subject]: after a decrypt that field holds the PROTECTED subject the
 * ciphertext carried (RFC 9788, issue #128), and putting it here would post the real subject of an
 * encrypted message in clear to a stranger's mailbox. The outgoing paths that already get this
 * right read the stored row rather than the screen (`ComposeViewModel`).
 *
 * [outgoingSubject] has NO default on purpose: the default would be the mistake. A null one is
 * safe — `readReceiptPreview` renders an untitled "Read" on a blank subject — so there is nothing
 * to fall back to.
 *
 * Raises no question by itself: an empty [ReadReceiptRequest.receiptTo] is the normal answer for
 * the overwhelming majority of mail, and [offeredReadReceipt] is what turns any of this into a
 * banner. Split out so the capture is executable on its own — this is where `deliveredTo` gets
 * into the request that will be handed to `sendReadReceipt`.
 */
fun readReceiptRequest(
    email: Email,
    deliveredTo: String?,
    outgoingSubject: String?,
): ReadReceiptRequest = ReadReceiptRequest(
    receiptTo = ReadReceiptHeader.recipients(email.dispositionNotificationTo),
    originalSubject = outgoingSubject,
    // The first id only: `Message-ID` is one field, the list shape is JMAP's, and RFC 8098's
    // `Original-Message-ID` names one message.
    originalMessageId = email.messageId.firstOrNull(),
    deliveredTo = deliveredTo,
)

/**
 * What the cover subject becomes after `openMessage` has answered: the [held] one, or the subject
 * of the [opened] message when that one is still the envelope.
 *
 * [MessageCrypto.Decrypted] hands back [held] UNCHANGED. `MailRepository.openMessage` serves the
 * decrypt cache first, so re-opening a message decrypted earlier in
 * this process returns a body whose [Email.subject] is the PROTECTED one the ciphertext carried
 * (RFC 9788, issue #128) — read as a cover, it would leave the device in clear on the read receipt.
 *
 * [MessageCrypto.Locked] is NOT excluded: nothing has been decrypted there, so its subject is
 * still the envelope's, and it is the freshest read of it. Null [crypto] — ordinary mail, nearly
 * all of it — likewise.
 *
 * A null subject never erases what is [held] (the cached row read a moment earlier), and null
 * [held] stays null: `readReceiptPreview` renders an untitled receipt, and the only other candidate
 * is the subject this guard exists to keep back.
 */
internal fun coverSubjectAfterOpen(held: String?, opened: Email, crypto: MessageCrypto?): String? =
    if (crypto is MessageCrypto.Decrypted) held else opened.subject ?: held

/**
 * The question to put to the reader, or null when there is none — the whole of the policy, in one
 * place, executed by its test rather than described by a comment.
 *
 * Null on every one of these, and each is a separate reason:
 *  - the setting has not been read yet ([ReadReceiptSetting.NOT_LOADED]);
 *  - the reader left it off ([ReadReceiptSetting.OFF]), which is the default and the common case;
 *  - the message was marked read by anything but the reader settling on it ([ReadMarking.OTHER]);
 *  - the reader settled on a message that was already read
 *    ([ReadMarking.READER_SETTLE_ALREADY_READ]);
 *  - the message carries no such header at all (`request == null`);
 *  - the header is there but names no usable address ([ReadReceiptRequest.receiptTo] empty — see
 *    [ReadReceiptHeader.recipients], which answers empty for everything malformed). Empty offers
 *    nothing and throws nothing: `enqueueSend` would refuse an empty recipient list, and this is
 *    the guard that means it is never reached.
 *  - the reader has ALREADY answered on this message ([answered]): said no, or said yes and had it
 *    queued. Replaying the decision is not forgetting the answer.
 *
 * It is a function of the CURRENT state, not an event, and that is what lets it be replayed:
 * whenever the setting changes, whenever the body lands, whenever the page settles. Turning the
 * switch on with a message open therefore raises the question, and turning it off takes it away,
 * through one decision rather than two that can disagree.
 */
fun offeredReadReceipt(
    setting: ReadReceiptSetting,
    marking: ReadMarking,
    request: ReadReceiptRequest?,
    answered: Boolean,
): ReadReceiptRequest? {
    if (setting != ReadReceiptSetting.ON) return null
    if (marking != ReadMarking.READER_SETTLE_UNREAD) return null
    if (answered) return null
    if (request == null || request.receiptTo.isEmpty()) return null
    return request
}

/**
 * Whether the reader has already answered the question on this message — said no, or said yes and
 * had a receipt queued.
 *
 * Its own type, and not "the offer is null", because the two are the opposite of each other in one
 * place that matters: [offeredReadReceipt] is REPLAYED whenever the setting changes, and a null
 * offer is exactly what a refusal leaves behind. Told apart by nothing, turning the switch off and
 * on again would put back the question the reader had just declined.
 *
 * It lives and dies with the page, like [ReadReceiptState] — this work remembers nothing across
 * a reload, which is what keeps it free of a column, a migration and a resync.
 */
enum class ReadReceiptAnswer {
    /** Nothing said yet: the question may be put, and put again. */
    PENDING,

    /** Said no. Nothing left, and nothing may raise it again on this message. */
    DECLINED,

    /** Said yes: a receipt was queued (or the attempt failed, which is not a second chance here). */
    ACCEPTED,
    ;

    /** What [offeredReadReceipt] is handed: the reader has had their say. */
    val answered: Boolean get() = this != PENDING
}

/**
 * Lifecycle of the receipt on the open message, reflected on the banner the next piece of work
 * will draw. Session-only, exactly like [UnsubscribeState], and for the same reason: remembering
 * it would be a column in the message table.
 *
 * **There is no `Sent`.** [UnsubscribeState] has one because a one-click unsubscribe is an HTTP
 * POST this app watches complete. A receipt has one road only — the durable outbox — so the
 * furthest this app can honestly say is [Queued]. A state nothing can produce is a branch the
 * banner would have to draw and no test could ever reach; per WYSIWYG, the state shown is the
 * state there is.
 */
sealed interface ReadReceiptState {
    /** Nothing done: the question stands, if there is one. */
    data object Idle : ReadReceiptState

    /** The reader said yes and the queueing call is in flight (a database write, so: briefly). */
    data object Sending : ReadReceiptState

    /**
     * In the outbox. Delivery, retry and the offline wait are the outbox's business from here.
     *
     * [preview] is what `MailRepository.sendReadReceipt` HANDED BACK — the subject and body of the
     * row it queued. Never rebuilt here: two sites deriving the same wording drift, and the day
     * they do the banner names one text while another leaves.
     */
    data class Queued(val preview: ReadReceiptPreview) : ReadReceiptState

    /** Nothing was queued: the send threw, or it found nothing to answer after all. */
    data object Failed : ReadReceiptState
}

/**
 * What the reader is told after a send attempt, from what the repository handed back.
 *
 * A null [preview] means `sendReadReceipt` queued nothing (an empty recipient list survived all
 * the way down), and the honest word for "you asked, nothing left" is [ReadReceiptState.Failed] —
 * not [ReadReceiptState.Idle], which would silently put the button back as if nothing had been
 * pressed, and not [ReadReceiptState.Queued], which would name an outbox row that does not exist.
 */
fun readReceiptSendOutcome(preview: ReadReceiptPreview?): ReadReceiptState =
    if (preview == null) ReadReceiptState.Failed else ReadReceiptState.Queued(preview)

/**
 * **How TALL the strip is allowed to be, which outranks everything else about it.**
 *
 * The measured height of the reader's header is part of the key of the `remember` that builds the
 * body's HTML document, so a strip that changes size **cancels the body load in flight**: before
 * the body's first reveal the reader waits behind the spinner until the failsafe lifts it, and
 * after it she watches the message reload and lose her place. This strip lives in the header, and
 * every gesture it offers is made there — so its states have to be one height.
 *
 * `heightIn(min = ButtonDefaults.MinHeight)` does NOT hold two different shapes level: it is a
 * FLOOR, and a `TextButton` stands well above it (Material3 wraps it in
 * `minimumInteractiveComponentSize()`, and its vertical inset grows with the font scale) while a
 * bare `Text` sits back down on it. Measured on the bench for the images strip (#153): the header
 * LOST 8 dp at the tap in the normal font, 13 dp at `font_scale 2.0`. So the three states that are
 * not a failure all draw the SAME button, and only its label, its `enabled` and the line beside it
 * change. One shape cannot diverge from itself.
 */
enum class ReadReceiptStripShape {
    /**
     * The line is held to ONE line, so the row is exactly the button's height — the same height in
     * every state wearing this shape, at every font scale.
     */
    BUTTON_ROW,

    /**
     * The one shape allowed to grow: the line may wrap, because a failure has to say what happened.
     * It is only ever reached AFTER a gesture the reader deliberately made (she tapped send and
     * the queueing failed), which is the same licence [UnsubscribeStripBody.FAILED] takes.
     *
     * It is NOT "no button": the button is drawn in every state, and in this one it is live
     * again — see [ReadReceiptStripBody.acts].
     */
    SENTENCE,
}

/**
 * What the read-receipt strip draws in a given state — the decision taken out of the `@Composable`
 * so a JVM test can RUN it, on the pattern of [UnsubscribeStripBody].
 *
 * [line] is what the row SAYS, and it is the only thing that changes between the three
 * [ReadReceiptStripShape.BUTTON_ROW] states. None of these strings is what leaves the device:
 * the mail itself is fixed English built by `readReceiptPreview`, and `ReadReceiptMailTest` holds
 * it out of `strings.xml` on purpose. Only the interface is translated.
 */
enum class ReadReceiptStripBody(
    @StringRes val line: Int,
    /**
     * The BUTTON's own label, which changes with the state — not a constant "Send" left greyed
     * under a line saying the answer is already in the outbox. WYSIWYG: the state shown is the
     * state there is, and the button is the one thing the reader looks at.
     *
     * And the button itself never leaves: replacing it with a bare [String] once it is spent is
     * the measured defect (a `Text` sits back down on the height floor a button stands above). The
     * label is what changes, never the shape.
     */
    @StringRes val button: Int,
    /** Whether [line] carries a `%1$s` — see [ReadReceiptStrip.named] for what fills it. */
    val names: Boolean,
    val shape: ReadReceiptStripShape,
) {
    /**
     * The question: who asked to be told, a live button to answer, and a gesture to refuse.
     * The only state that sends anything from a standing question, and it sends nothing on its
     * own.
     */
    ASK(
        line = R.string.message_read_receipt_ask,
        button = R.string.message_read_receipt_send,
        names = true,
        shape = ReadReceiptStripShape.BUTTON_ROW,
    ),

    /** The reader said yes and the outbox row is being written (a database write, so: briefly). */
    SENDING(
        line = R.string.message_read_receipt_sending,
        button = R.string.message_read_receipt_send_sending,
        names = false,
        shape = ReadReceiptStripShape.BUTTON_ROW,
    ),

    /**
     * In the outbox, and that is the furthest this app can honestly say — [ReadReceiptState] has
     * no `Sent` and must not grow one. The line names the subject of the row that was queued, and
     * the button says what became of the gesture instead of still offering it.
     */
    QUEUED(
        line = R.string.message_read_receipt_queued,
        button = R.string.message_read_receipt_send_queued,
        names = true,
        shape = ReadReceiptStripShape.BUTTON_ROW,
    ),

    /**
     * Nothing was queued. The sentence says so, and the button comes back: a refused enqueue or a
     * dead identity is a retry, not a dead end (`MessageViewModel.sendReadReceipt` puts the
     * question back through the decision when this state is reached).
     */
    FAILED(
        line = R.string.message_read_receipt_failed,
        button = R.string.message_read_receipt_send,
        names = false,
        shape = ReadReceiptStripShape.SENTENCE,
    ),
    ;

    /**
     * Whether the button may fire — `enabled`, and the same question `sendReadReceipt` re-runs for
     * itself before anything leaves.
     *
     * [FAILED] acts for the same reason [UnsubscribeStripBody.FAILED] does: nothing left the
     * device, so there is nothing to protect the stranger from, and the reader who chose to answer
     * should not have to leave the message and come back to try again.
     */
    val acts: Boolean get() = this == ASK || this == FAILED

    /**
     * Whether the refusal is on offer. It exists only while the question stands untouched: once a
     * receipt is queued there is nothing left to refuse, and nothing this app can recall.
     *
     * NOT extended to [FAILED], and that is deliberate rather than an oversight: taking the
     * refusal there would have to clear [ReadReceiptState.Failed] as well (the state, not the
     * offer, is what keeps that row on screen), which means teaching `declineReadReceipt` a second
     * job. The neighbour offers no dismissal on its failure either.
     */
    val declines: Boolean get() = this == ASK
}

/**
 * Everything the strip draws, or null when there is no strip at all.
 *
 * [named] is what [ReadReceiptStripBody.line]'s `%1$s` is filled with, and it is CARRIED rather
 * than looked up by the composable:
 *  - asking, it is who the receipt would go to — the one fact the reader decides on;
 *  - queued, it is the subject of the row `sendReadReceipt` actually put in the outbox, handed
 *    back by the repository and never rebuilt here. Two sites deriving the same wording drift, and
 *    the day they do the strip names one text while another leaves (house precedent:
 *    `unsubscribePreview`).
 */
data class ReadReceiptStrip(val body: ReadReceiptStripBody, val named: String)

/**
 * See [ReadReceiptStrip] — and null means the header draws nothing at all, not an empty row.
 *
 * Null is the answer for very nearly every message, and it comes from [offer]: the question is
 * null while the setting has not loaded, null when the reader left the switch off, null for a
 * message nobody asked about, and null again once the reader has refused. This function does
 * not re-derive any of that — [offeredReadReceipt] took that decision, and asking it twice is how
 * two guards end up disagreeing.
 */
fun readReceiptStrip(offer: ReadReceiptRequest?, state: ReadReceiptState): ReadReceiptStrip? =
    when (state) {
        ReadReceiptState.Idle -> offer?.let {
            ReadReceiptStrip(ReadReceiptStripBody.ASK, it.receiptTo.joinToString(", "))
        }
        ReadReceiptState.Sending -> ReadReceiptStrip(ReadReceiptStripBody.SENDING, "")
        is ReadReceiptState.Queued -> ReadReceiptStrip(ReadReceiptStripBody.QUEUED, state.preview.subject)
        ReadReceiptState.Failed -> ReadReceiptStrip(ReadReceiptStripBody.FAILED, "")
    }
