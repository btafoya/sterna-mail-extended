package app.sterna.core.data.mail

import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.imap.OutgoingMime
import java.util.Base64

/**
 * The message disposition notification (MDN) of RFC 8098 — the answer to the
 * `Disposition-Notification-To:` [ReadReceiptHeader] can read, and the outbox row that carries it.
 *
 * Pure string assembly, like [app.sterna.core.imap.PgpMime]: no repository, no `Context`, no
 * socket, so every byte that will leave the device is decided by a function a JVM test can run.
 *
 * ⛔ **Nothing here is translated, and nothing here may ever be.** An MDN is read twice: once by a
 * correspondent whose language this phone cannot know, and once by their mail client, which parses
 * the second part as a set of fields. The house precedent is [UNSUBSCRIBE_SUBJECT] and its
 * reasoning applies word for word — what the reader SEES is translated, what leaves the device and
 * has to be understood by a stranger and their software is fixed English. That includes the
 * subject. ⛔ Nothing in this file belongs in `strings.xml`.
 */

/**
 * The outbox row a read receipt becomes: exactly the arguments
 * [MailRepository.enqueueSend] is handed, and nothing else.
 *
 * A data class rather than seven positional parameters at the call site, for the same reason
 * [UnsubscribeMail] is one — the mapping is then *executed* by its test, and what is pinned is not
 * "a receipt is sent" but "this recipient, this subject, this identity, this entity, and the
 * contacts list left alone".
 */
internal data class ReadReceiptMail(
    val to: List<String>,
    val subject: String,
    val body: String,
    val fromName: String?,
    val fromEmail: String?,
    /** The `multipart/report` that replaces the body on the wire; see [readReceiptEntity]. */
    val prebuiltEntity: String,
    /**
     * ⛔ Always false, and it is a decision, not an oversight.
     *
     * Every other caller of `enqueueSend` learns its recipients into the contacts the composer
     * suggests, because the user wrote to somebody. Here the user wrote to nobody: they opened a
     * message and let its sender know. Putting that sender into "people you write to" would build
     * a list out of who mails HER — newsletters, notifications, a stranger's cold pitch — off a
     * gesture that was never addressed to anyone.
     */
    val learnRecipients: Boolean = false,
) {
    /**
     * What the reader was, or will be, shown — read straight off the fields that are being queued,
     * never rebuilt.
     *
     * `MailRepository.sendReadReceipt` returns this so the banner offering the receipt has the
     * strings without recomputing them. Recomputing is the failure mode the single-wording rule
     * exists against: two call sites deriving "the address that received" independently drift, and
     * the day they do, the banner names one address while another leaves.
     */
    fun preview(): ReadReceiptPreview = ReadReceiptPreview(subject = subject, body = body)
}

/**
 * The fixed English word a read receipt's subject is built from — ⛔ NOT a translated label, see
 * this file's header.
 */
internal const val READ_RECEIPT_SUBJECT = "Read receipt"

/**
 * What the receipt will say, both on screen and on the wire: the [subject] on its line and the
 * [body] in the mail, exactly as they will leave the device.
 *
 * The twin of [UnsubscribeMailPreview], and it exists for the same reason: the banner that will
 * one day offer to send this has to show the very strings [readReceiptMail] puts in the outbox,
 * because a dialog that names one text while another leaves is worse than a dialog that names
 * nothing.
 */
data class ReadReceiptPreview(
    val subject: String,
    val body: String,
)

/**
 * The subject and human-readable body of the receipt for a message titled [originalSubject],
 * received at [finalRecipient] — ONE executed decision, read both by [readReceiptMail] (which
 * sends them) and by the reader's confirmation (which shows them).
 *
 * [originalSubject] comes from a message a stranger wrote and is echoed verbatim. It is safe in
 * both places it lands: in the mail's `Subject` header `OutgoingMime`'s RFC 2047 encoder turns any
 * control character into an encoded-word, and in the body it is base64'd inside its part by
 * [readReceiptEntity], where it cannot begin a line at all — so it can neither add a header nor
 * end a part.
 *
 * The wording promises no more than an MDN can keep: the message was *displayed*. Neither the
 * protocol nor this app knows whether anyone read it, and saying otherwise to a correspondent who
 * cannot check would be a lie the app makes on the reader's behalf.
 */
fun readReceiptPreview(originalSubject: String?, finalRecipient: String): ReadReceiptPreview {
    val title = originalSubject?.trim().orEmpty()
    return ReadReceiptPreview(
        subject = if (title.isEmpty()) READ_RECEIPT_SUBJECT else "$READ_RECEIPT_SUBJECT: $title",
        body = buildString {
            append("This is a read receipt.\n\n")
            append(
                if (title.isEmpty()) {
                    "The message you sent to $finalRecipient was displayed."
                } else {
                    "The message you sent to $finalRecipient with the subject \"$title\" was displayed."
                },
            )
            append("\n\nIt says the message was shown on the recipient's device. It does not say it was read.")
        },
    )
}

/**
 * The name this app reports itself under in an MDN (RFC 8098 §3.2.2, `ua-name`).
 *
 * ⛔ No version, and ⛔ no host name. The field's classic form is `machine.example.com; Foomail 97`
 * and both halves are optional; the ABNF accepts a bare `ua-name`, which is all a correspondent
 * needs to know. A host name would name the reader's machine to a stranger, and a version would
 * tie this file to `versionName` — one more thing to bump, silently wrong the day it is not.
 */
internal const val READ_RECEIPT_UA = "Sterna Mail"

/**
 * The `multipart/report` entity of RFC 8098 §3: a part for the person and a part for their
 * software, in that order.
 *
 * ⭐ [boundary] is an ARGUMENT and never drawn in here, exactly as
 * [app.sterna.core.imap.PgpMime.wrapSigned] takes its own: a function that reaches for a random
 * number is a function whose output cannot be pinned, and these bytes are the whole point. The
 * draw lives at the call site.
 *
 * `report-type=disposition-notification` on the top-level `Content-Type` is what tells the
 * receiving client this is a receipt and not a two-part mail; `OutgoingMime.buildBodyEntity`
 * cannot express it (it knows `text/plain`, `text/html`, `multipart/mixed` and
 * `multipart/related`), which is why this goes out through the pre-built entity seam
 * ([app.sterna.core.imap.OutgoingMessage.prebuiltEntity]) rather than through the normal body
 * construction.
 *
 * ⚠ [humanText], [finalRecipient] and [originalMessageId] all originate in a message a stranger
 * wrote. [humanText] is base64'd, so it cannot start a line and cannot end a part. The other two
 * land in header-shaped fields and go through [OutgoingMime.headerSafe], the same sink the message
 * headers use: with CR and LF gone, a hostile `Message-ID` can neither add a field of its own
 * (`Disposition: …; deleted` on a line it invented) nor reach the start of a line where a boundary
 * delimiter would be recognised.
 */
internal fun readReceiptEntity(
    boundary: String,
    humanText: String,
    finalRecipient: String,
    originalMessageId: String?,
): String {
    // One sanitised spelling used for the parameter AND for the delimiters, so the two cannot
    // disagree about where the parts begin.
    val b = OutgoingMime.headerSafe(boundary).replace("\"", "")
    // ⚠ The angle brackets are REMOVED, not trimmed off the ends. A Message-ID read from a received
    // message arrives wrapped in them (`<abc@x>`), so trimming is what keeps `<<abc@x>>` from going
    // out; but [OutgoingMime.headerSafe] passes `<` and `>` through, so a value carrying them in
    // the middle — an injection attempt that the CR/LF strip has already flattened into one line —
    // would still leave `<evil@x>Disposition: …>`, which is not a `msg-id` and which a strict
    // parser is entitled to drop, taking the machine-readable part with it.
    val messageId = originalMessageId
        ?.let { OutgoingMime.headerSafe(it) }
        ?.filterNot { it == '<' || it == '>' }
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    return buildString {
        append("Content-Type: multipart/report; report-type=disposition-notification;\r\n")
        append(" boundary=\"$b\"\r\n")
        append("\r\n")
        // 1. For the person who asked. base64 like every other body part this app writes.
        append("--$b\r\n")
        append("Content-Type: text/plain; charset=utf-8\r\n")
        append("Content-Transfer-Encoding: base64\r\n")
        append("\r\n")
        append(base64(humanText))
        append("\r\n")
        // 2. For their software. Fields only, in the order RFC 8098 §3.1's ABNF requires.
        append("--$b\r\n")
        append("Content-Type: message/disposition-notification\r\n")
        append("\r\n")
        append("Reporting-UA: $READ_RECEIPT_UA\r\n")
        append("Final-Recipient: rfc822; ${OutgoingMime.headerSafe(finalRecipient)}\r\n")
        if (messageId != null) append("Original-Message-ID: <$messageId>\r\n")
        // manual-action: a person tapped this. MDN-sent-manually: nothing here fires on its own.
        append("Disposition: manual-action/MDN-sent-manually; displayed\r\n")
        append("--$b--\r\n")
    }
}

/**
 * MIME base64: 76-column lines separated by CRLF, which is what `Base64.getMimeEncoder()` already
 * produces on its own (`getMimeEncoder()` == `getMimeEncoder(76, "\r\n")`).
 *
 * ⛔ NOT written as `getMimeEncoder().encodeToString(…).replace("\n", "\r\n")`. That replace runs
 * over separators that are ALREADY `\r\n` and turns every one of them into `\r\r\n` — a bare CR
 * inside a base64 body, which a DATA payload may not contain (RFC 5321 § 2.3.8). Parsers shrug at
 * it, so nothing looks wrong on a plain message; on a SIGNED one the bare CR sits in the signed
 * bytes, and a relay that straightens the line makes the recipient read "invalid signature".
 */
private fun base64(text: String): String =
    Base64.getMimeEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

/**
 * The identity a receipt goes out under: the one whose address the message was DELIVERED to, or
 * the account's default (its first) when [deliveredTo] names none of them or is not known.
 *
 * ⛔ Not `identities.first()`. An account can hold several aliases ([StoredIdentity]'s own KDoc says
 * so), and the first is merely the composer's default — so a receipt for a message addressed to the
 * second alias used to leave from the first, telling a correspondent that a mailbox they never
 * wrote to had read their mail. Matching is case-insensitive: an address is, and a sender who typed
 * `Ann@Example.org` addressed the same mailbox as `ann@example.org`.
 */
internal fun receiptIdentity(identities: List<StoredIdentity>, deliveredTo: String?): StoredIdentity? =
    deliveredTo?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { addr -> identities.firstOrNull { it.email.equals(addr, ignoreCase = true) } }
        ?: identities.firstOrNull()

/**
 * Build the read receipt for a message whose sender asked for one at [receiptTo], or **null** when
 * there is nothing to answer.
 *
 * ⭐ Null is the whole guard, and it is why this returns a nullable rather than throwing.
 * [receiptTo] comes from [ReadReceiptHeader.recipients], which answers with an EMPTY LIST for a
 * header that is absent, blank, `<>`, or a word that is not an address — and `enqueueSend` opens
 * with `require(recipients.isNotEmpty())`. Handing it an empty list would throw an exception at a
 * reader who did nothing wrong; the case is ruled out here instead, before anything is built and
 * long before anything is queued.
 *
 * [identityName]/[identityEmail] are the identity the receipt is SENT UNDER, carried explicitly
 * for the reason [unsubscribeMail] carries them: a delegated sub-account submits through its LOGIN
 * (issue #31), so without this the receipt would come back from the login address — an address the
 * sender never wrote to, about a message they will not recognise. See [receiptIdentity] for how
 * one is picked.
 *
 * ⭐ [deliveredTo] is the address the message ARRIVED at, and it wins the `Final-Recipient` field
 * and the sentence the correspondent reads. It is a parameter and not a guess: an account with
 * several aliases has several addresses, only one of them was written to, and naming another says
 * to a stranger "you wrote to this mailbox" about a mailbox they never used. The caller — the
 * gesture that opened the message — is the only place that knows it. Null when it does not, and
 * then the identity's own address answers, then [accountAddress]: a receipt naming the wrong alias
 * is still better than none, but it is the last answer, not the first.
 */
internal fun readReceiptMail(
    receiptTo: List<String>,
    originalSubject: String?,
    originalMessageId: String?,
    identityName: String?,
    identityEmail: String?,
    deliveredTo: String?,
    accountAddress: String,
    boundary: String,
): ReadReceiptMail? {
    val to = receiptTo.map { it.trim() }.filter { it.isNotEmpty() }
    if (to.isEmpty()) return null
    val finalRecipient = listOf(deliveredTo, identityEmail, accountAddress)
        .firstOrNull { !it.isNullOrBlank() }?.trim() ?: accountAddress
    val preview = readReceiptPreview(originalSubject, finalRecipient)
    return ReadReceiptMail(
        to = to,
        subject = preview.subject,
        body = preview.body,
        fromName = identityName,
        fromEmail = identityEmail,
        prebuiltEntity = readReceiptEntity(
            boundary = boundary,
            // The same string the reader was shown and the same string the row stores: one text.
            humanText = preview.body,
            finalRecipient = finalRecipient,
            originalMessageId = originalMessageId,
        ),
    )
}
