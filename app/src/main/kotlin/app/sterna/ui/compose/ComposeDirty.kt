package app.sterna.ui.compose

import app.sterna.core.data.text.RichBody

/**
 * The unsaved-changes verdict, pulled out of the composable so it can be unit-tested (#70/#94).
 *
 * Recipients are compared as order-independent SETS, not strings: tapping a chip to edit it reorders
 * the field, and a string compare then fired the discard dialog on an untouched reply (#94).
 * Attachments are compared through an explicit "touched" flag, not their presence, because a reopened
 * message opens with them already set (#70). The body counts styling as well as text (#131), and the
 * read-receipt box is compared against the value the message OPENED with — ticking or clearing it is
 * an edit, and left out of the verdict a cleared box was lost in silence.
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
