package app.sterna.ui.compose

import app.sterna.core.data.text.RichBody

/**
 * The unsaved-changes verdict, pulled out of the composable so it can be unit-tested (#70/#94).
 *
 * Two mistakes it exists to avoid:
 *  - counting a PRE-FILLED field as an edit. A message reopened from the outbox (or a forward) opens
 *    with Cc/Bcc and attachments already set; comparing them against "" or "any attachment" made the
 *    discard dialog fire on a close where the user changed nothing (#70 BLOCKER 3).
 *  - comparing recipient fields as STRINGS. Tapping a chip to edit it reorders the field, so a
 *    string compare saw a change where the set of addresses was identical, again firing the dialog
 *    on an untouched reply (#94).
 *
 * So recipients are compared as order-independent sets, the subject as text, the body as text AND
 * styling (#131: a word made bold with nothing typed is an edit), and attachments via an explicit
 * "the user touched them" flag rather than their mere presence.
 *
 * The read-receipt box is a field like the others, and it is compared against the value the message
 * OPENED with for exactly the same reason: on a message reopened from the outbox the box opens
 * already ticked, so "ticked" is not an edit — but ticking or CLEARING it is. Left out of the verdict
 * it was lost in silence, with no dialogue at all: clearing the box and leaving gave `dirty = false`,
 * so the editor closed straight through `cancel()`, the queued row went back untouched, and the
 * message went out still asking while the screen had just said it would not.
 */
internal object ComposeDirty {
    private fun addressSet(value: String): Set<String> =
        recipientTokens(value).map { it.trim().lowercase() }.toSet()

    fun isDirty(
        onlyCopy: Boolean,
        to: String,
        initialTo: String,
        cc: String,
        initialCc: String,
        bcc: String,
        initialBcc: String,
        subject: String,
        initialSubject: String,
        body: RichBody,
        initialBody: RichBody,
        requestReceipt: Boolean,
        initialRequestReceipt: Boolean,
        attachmentsTouched: Boolean,
    ): Boolean =
        // An undone send lives only on this screen: guard it even untouched, closing destroys it.
        onlyCopy ||
            addressSet(to) != addressSet(initialTo) ||
            addressSet(cc) != addressSet(initialCc) ||
            addressSet(bcc) != addressSet(initialBcc) ||
            subject != initialSubject ||
            body != initialBody ||
            requestReceipt != initialRequestReceipt ||
            attachmentsTouched
}
