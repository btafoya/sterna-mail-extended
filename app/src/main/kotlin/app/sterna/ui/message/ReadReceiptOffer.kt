package app.sterna.ui.message

import androidx.annotation.StringRes
import app.sterna.R
import app.sterna.core.data.mail.MessageCrypto
import app.sterna.core.data.mail.ReadReceiptHeader
import app.sterna.core.data.mail.ReadReceiptPreview
import app.sterna.core.jmap.model.Email

/**
 * Whether a message that asked for a read receipt (RFC 8098) may raise the question. Nothing here
 * sends or draws anything: it answers "is there a question to ask?" and hands back the request.
 *
 * Two things it keeps apart: a setting that has not LOADED is not a setting that is off, hence three
 * values; and being MARKED read is not being read — `setRead` has nine callers, only the reader
 * settling on a message is a person looking at it, and `SECURITY.md` promises nothing leaves on open.
 */
enum class ReadReceiptSetting {
    /** DataStore has not answered yet. Not "off": nothing is offered and nothing is remembered, so
     *  the next message settled on asks again. Treating this as "on" would put the question in front
     *  of every reader who never asked for it. */
    NOT_LOADED,

    /** Read and off (the default): the header is ignored, no banner, no menu entry, no trace. */
    OFF,

    /** Read and on: the app ASKS. Not "sends" — there is deliberately no third position. Turning the
     *  switch on is consent to be asked, never consent to answer. */
    ON,
}

/** How a message came to be marked read — the parameter that carries "a person had it on screen",
 * which no boolean called `read` can carry. Do not add a value meaning "close enough": the eight
 *  non-reader ways a message becomes read are all [OTHER]. */
enum class ReadMarking {
    /** The reader's pager SETTLED on this message and it was still unread until that moment — the
     *  unread → read transition, and the only one a human made. */
    READER_SETTLE_UNREAD,

    /** The reader settled on a message that was ALREADY read. No transition, so nothing is offered:
     * reopening must not re-ask, which keeps this work free of an "already answered" column. Marking
     *  unread again and reopening does ask again. */
    READER_SETTLE_ALREADY_READ,

    /** Every other way a message becomes read: a swipe, unfolding a conversation, a multi-select,
     *  "mark all read", a notification action, archive/delete, a Sieve `addflag`, or a `$seen` from
     *  another device. Nobody read anything here. */
    OTHER,
}

/**
 * What would be answered, captured whole at the moment the question is raised: read again when the
 * reader taps, a second read would answer for the neighbouring message, or for none — same failure as
 * [PendingUnsubscribe].
 *
 * [deliveredTo] is the address of THIS account the message arrived at, carried rather than worked out
 * later so an account with several aliases does not answer under a name it never wrote to.
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
 * THIS REQUEST LEAVES THE DEVICE, to an address the SENDER chose, with a copy in Sent. So the
 * subject is the one from the COVER, handed in by the caller, and never [Email.subject]: after a
 * decrypt that field holds the PROTECTED subject (RFC 9788, #128). [outgoingSubject] has no default
 * on purpose. It raises no question by itself: [offeredReadReceipt] turns this into a banner.
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
 * What the cover subject becomes after `openMessage` has answered: the [held] one, or the [opened]
 * message's when that one is still the envelope.
 *
 * [MessageCrypto.Decrypted] hands back [held] UNCHANGED: `openMessage` serves the decrypt cache
 * first, so reopening a message decrypted earlier returns the PROTECTED subject (#128), which read as
 * a cover would leave the device in clear. [MessageCrypto.Locked] is not excluded, nothing having
 * been decrypted. A null subject never erases what is [held].
 */
internal fun coverSubjectAfterOpen(held: String?, opened: Email, crypto: MessageCrypto?): String? =
    if (crypto is MessageCrypto.Decrypted) held else opened.subject ?: held

/**
 * The question to put to the reader, or null when there is none — the whole of the policy in one
 * place, executed by its test. Null when the setting has not loaded, when it is off, when the message
 * was marked read by anything but the reader settling on it, when it was already read, when there is
 * no header or none naming a usable address (this is the guard that keeps `enqueueSend` from ever
 * seeing an empty recipient list), or when the reader has already answered.
 *
 * A function of the CURRENT state, not an event, which is what lets it be replayed: turning the
 * switch on with a message open raises the question, and off takes it away, through one decision.
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
 * Whether the reader has already answered on this message. Its own type, not "the offer is null":
 * [offeredReadReceipt] is REPLAYED whenever the setting changes, and a null offer is exactly what a
 * refusal leaves behind, so switching off and on again would put back the question just declined.
 * It lives and dies with the page: this work remembers nothing across a reload.
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

/** Lifecycle of the receipt on the open message. Session-only, like [UnsubscribeState]: remembering it
 * would be a column in the message table. There is no `Sent` — a receipt has one road, the durable
 *  outbox — and a state nothing can produce is a branch no test could reach. */
sealed interface ReadReceiptState {
    /** Nothing done: the question stands, if there is one. */
    data object Idle : ReadReceiptState

    /** The reader said yes and the queueing call is in flight (a database write, so: briefly). */
    data object Sending : ReadReceiptState

    /** In the outbox; delivery and retry are the outbox's business from here. [preview] is what
     * `sendReadReceipt` HANDED BACK, never rebuilt here: two sites deriving the same wording drift,
     *  and the banner would name one text while another leaves. */
    data class Queued(val preview: ReadReceiptPreview) : ReadReceiptState

    /** Nothing was queued: the send threw, or it found nothing to answer after all. */
    data object Failed : ReadReceiptState
}

/** What the reader is told after a send attempt. A null [preview] means nothing was queued, and the
 *  honest word is [ReadReceiptState.Failed] — not Idle, which would put the button back as if nothing
 *  had been pressed, nor Queued, which would name a row that does not exist. */
fun readReceiptSendOutcome(preview: ReadReceiptPreview?): ReadReceiptState =
    if (preview == null) ReadReceiptState.Failed else ReadReceiptState.Queued(preview)

/**
 * How TALL the strip is allowed to be, which outranks everything else about it. The measured header
 * height keys the `remember` that builds the body's HTML document, so a strip that changes SIZE
 * cancels the body load in flight: before the first reveal the reader waits behind the spinner, after
 * it the message reloads and she loses her place.
 *
 * `heightIn(min = ButtonDefaults.MinHeight)` does not hold two shapes level — it is a FLOOR, and a
 * `TextButton` stands above it while a bare `Text` sits on it (measured, #153: 8 dp, 13 dp at
 * `font_scale 2.0`). So the three non-failure states draw the SAME button.
 */
enum class ReadReceiptStripShape {
    /** The line is held to ONE line, so the row is exactly the button's height — the same height in
     *  every state wearing this shape, at every font scale. */
    BUTTON_ROW,

    /** The one shape allowed to grow: the line may wrap, because a failure has to say what happened,
     * and it is only reached AFTER a deliberate gesture. Not "no button": the button is drawn in
     *  every state, and here it is live again. */
    SENTENCE,
}

/** What the read-receipt strip draws in a given state, on the pattern of [UnsubscribeStripBody].
 * None of these strings leaves the device: the mail itself is fixed English built by
 *  `readReceiptPreview`, held out of `strings.xml` by `ReadReceiptMailTest`. */
enum class ReadReceiptStripBody(
    @StringRes val line: Int,
    /** The BUTTON's own label, which changes with the state — not a constant "Send" left greyed under
     *  a line saying the answer is already queued. And the button never leaves: replacing it with a
     *  bare [String] once spent is the measured height defect above. */
    @StringRes val button: Int,
    /** Whether [line] carries a `%1$s` — see [ReadReceiptStrip.named] for what fills it. */
    val names: Boolean,
    val shape: ReadReceiptStripShape,
) {
    /** The question: who asked to be told, a live button to answer, and a gesture to refuse. The only
     *  state that sends anything from a standing question, and it sends nothing on its own. */
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

    /** In the outbox, and the furthest this app can honestly say — [ReadReceiptState] has no `Sent` and
     *  must not grow one. The button says what became of the gesture. */
    QUEUED(
        line = R.string.message_read_receipt_queued,
        button = R.string.message_read_receipt_send_queued,
        names = true,
        shape = ReadReceiptStripShape.BUTTON_ROW,
    ),

    /**
     * Nothing was queued. The button comes back: a refused enqueue or a dead identity is a retry,
     * not a dead end.
     */
    FAILED(
        line = R.string.message_read_receipt_failed,
        button = R.string.message_read_receipt_send,
        names = false,
        shape = ReadReceiptStripShape.SENTENCE,
    ),
    ;

    /** Whether the button may fire — `enabled`, and the same question `sendReadReceipt` re-runs before
     *  anything leaves. [FAILED] acts because nothing left the device. */
    val acts: Boolean get() = this == ASK || this == FAILED

    /** Whether the refusal is on offer: only while the question stands untouched, since once a receipt
     * is queued there is nothing left to refuse and nothing this app can recall. Deliberately not
     *  extended to [FAILED], which would teach `declineReadReceipt` a second job. */
    val declines: Boolean get() = this == ASK
}

/** Everything the strip draws, or null when there is no strip at all. [named] fills the line's `%1$s`
 *  and is CARRIED, not looked up: asking, who the receipt would go to; queued, the subject of the row
 * `sendReadReceipt` actually put in the outbox, handed back and never rebuilt here. */
data class ReadReceiptStrip(val body: ReadReceiptStripBody, val named: String)

/** See [ReadReceiptStrip] — null means the header draws nothing at all, not an empty row, and is the
 * answer for very nearly every message. This function re-derives none of it: [offeredReadReceipt]
 *  took the decision, and asking it twice is how two guards end up disagreeing. */
fun readReceiptStrip(offer: ReadReceiptRequest?, state: ReadReceiptState): ReadReceiptStrip? =
    when (state) {
        ReadReceiptState.Idle -> offer?.let {
            ReadReceiptStrip(ReadReceiptStripBody.ASK, it.receiptTo.joinToString(", "))
        }
        ReadReceiptState.Sending -> ReadReceiptStrip(ReadReceiptStripBody.SENDING, "")
        is ReadReceiptState.Queued -> ReadReceiptStrip(ReadReceiptStripBody.QUEUED, state.preview.subject)
        ReadReceiptState.Failed -> ReadReceiptStrip(ReadReceiptStripBody.FAILED, "")
    }
