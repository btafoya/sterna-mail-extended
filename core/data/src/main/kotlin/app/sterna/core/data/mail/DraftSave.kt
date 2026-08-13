package app.sterna.core.data.mail

import app.sterna.core.imap.ImapAddress
import kotlin.coroutines.cancellation.CancellationException

/**
 * Outcome of [MailRepository.saveDraft]. [ORIGINAL_KEPT] means the new draft could NOT reproduce
 * everything the edited one held — an attachment that couldn't be re-attached, an HTML body the
 * plain-text editor flattened, or an addressing that could not be shown to have survived — so the
 * original was deliberately left on the server: a duplicate in Drafts is recoverable, a destroyed
 * original is not (#63).
 */
enum class DraftSaveOutcome { SAVED, ORIGINAL_KEPT }

/**
 * Whether a re-saved draft reproduced the one it replaces, i.e. whether the original may be
 * destroyed. Pure decision logic of [MailRepository.saveDraft], extracted for unit tests:
 *
 * - every attachment handed to the save must have made it into the new draft
 *   ([attachmentsCarried] == [attachmentsIn]) — compose shows those parts as chips, so dropping
 *   them AND destroying the original would delete files the user was just looking at;
 * - [bodyIsLossy] marks content this composer cannot give back (a draft authored elsewhere in
 *   HTML is flattened to plain text on open; inline images and calendar parts aren't carried).
 * - [addressingIsCarried] says the replacement provably holds the addresses the original was
 *   addressed to — see [draftAddressingIsCarried]. An attachment that goes missing is countable
 *   at the save; a recipient that goes missing is not, because the composer cannot tell a draft
 *   with no Cc from a Cc it never managed to read.
 * - [receiptRequestIsCarried] says the same of the read receipt the original asked for — see
 *   [draftReceiptRequestIsCarried]. It is the same shape of loss as the addressing: the request
 *   lives in a header of the server copy, the composer's checkbox comes back clear when the draft
 *   is reopened, and destroying that copy takes the request with it in silence.
 *
 * Anything else is a duplicate in Drafts, which the user can delete. Data loss, they cannot undo.
 */
internal fun draftReplacementIsFaithful(
    attachmentsIn: Int,
    attachmentsCarried: Int,
    bodyIsLossy: Boolean,
    addressingIsCarried: Boolean,
    receiptRequestIsCarried: Boolean,
): Boolean = !bodyIsLossy &&
    attachmentsCarried == attachmentsIn &&
    addressingIsCarried &&
    receiptRequestIsCarried

/**
 * Whether [replacement] provably carries the addressing of the draft it replaces.
 *
 * ⭐ `original == null` means "could not be established", and that is NOT an empty addressing:
 * it is an unknown, and an unknown answers false. This is the whole point of the function. A
 * draft re-saved while the addressing could not be read (offline, a fetch that failed, a message
 * gone from the folder) must leave the server copy alone — that copy is the only thing still
 * holding the addresses, and destroying it loses them for good, silently.
 *
 * Inclusion, one way round only: every address the original had must be in the replacement. A
 * replacement may ADD recipients (the user just typed one) and still be faithful; it may not
 * drop one. Compared trimmed and case-folded, since a mailbox re-typed with different case or
 * whitespace is the same mailbox — and a blank is not an address, so it is not lost either.
 */
internal fun draftAddressingIsCarried(original: Set<String>?, replacement: Set<String>): Boolean {
    if (original == null) return false
    val carried = normalisedAddresses(replacement)
    return normalisedAddresses(original).all { it in carried }
}

/**
 * The same question asked of the id being replaced, with [originalAddressing] doing the reading.
 *
 * Here so the decision — including "no original, nothing to prove" and "the read blew up, so the
 * answer is unknown" — is executable in a JVM test: the caller ([MailRepository.saveDraft]) needs
 * Room and an Android context, so nothing inside it can be reached from one.
 */
internal suspend fun draftAddressingIsCarried(
    replacesEmailId: String?,
    replacement: Set<String>,
    originalAddressing: suspend (String) -> Set<String>?,
): Boolean {
    // No original means nothing will be destroyed, so there is nothing to prove.
    if (replacesEmailId == null) return true
    val original = try {
        originalAddressing(replacesEmailId)
    } catch (cancelled: CancellationException) {
        // Not a failed read: the caller is going away. Rethrown, or a cancelled save would look
        // like a merely unknown addressing and carry on.
        throw cancelled
    } catch (failure: Throwable) {
        // Offline, socket dropped, folder gone: unknown, which forbids the destroy.
        null
    }
    return draftAddressingIsCarried(original, replacement)
}

/**
 * Whether [replacement] provably carries the read-receipt request of the draft it replaces
 * (RFC 8098's `Disposition-Notification-To`).
 *
 * ⭐ A [replacement] that asks for a receipt carries ANY original, including one that could not be
 * read: whatever the old copy held, the new one holds a request, so nothing can be lost by
 * destroying it. Answering "unknown" there would leave a duplicate in Drafts — and cost a server
 * round trip to establish something that cannot change the verdict — every single time a draft
 * that asks for a receipt is saved.
 *
 * ⭐ `original == null` means "could not be established", and answers false when the replacement
 * asks for nothing — the same rule, and for the same reason, as [draftAddressingIsCarried]: the
 * server copy is then the only thing that holds the request, and destroying it loses it silently.
 * What made this decidable at all is that the header is now READ when a message is opened (JMAP's
 * `header:Disposition-Notification-To:asText` property, IMAP's `readReceiptHeaderOf`); before
 * that, the answer was unknown every time and the field was simply left out of the guard.
 *
 * The cases, and the asymmetry between them is the point:
 *  - replacement asks → carried, whatever the original was, read or not;
 *  - original asked, replacement does not → NOT carried: the request is about to be dropped, and
 *    a duplicate in Drafts is the cheaper of the two mistakes;
 *  - original did not ask → carried. Nothing is lost by destroying an original that had none;
 *  - original unknown, replacement asks for nothing → NOT carried.
 */
internal fun draftReceiptRequestIsCarried(original: Boolean?, replacement: Boolean): Boolean =
    replacement || original == false

/**
 * The same question asked of the id being replaced, with [originalRequested] doing the reading —
 * the twin of [draftAddressingIsCarried], and executable in a JVM test for the same reason: the
 * caller ([MailRepository.saveDraft]) needs Room, a context and a socket.
 */
internal suspend fun draftReceiptRequestIsCarried(
    replacesEmailId: String?,
    replacement: Boolean,
    originalRequested: suspend (String) -> Boolean?,
): Boolean {
    // No original means nothing will be destroyed, so there is nothing to prove.
    if (replacesEmailId == null) return true
    // Nor is there anything to prove when the replacement itself asks for a receipt: no answer
    // could change the verdict, so the read is skipped — a round trip saved on every save of a
    // draft that asks for one.
    if (replacement) return true
    val original = try {
        originalRequested(replacesEmailId)
    } catch (cancelled: CancellationException) {
        // Not a failed read: the caller is going away. Rethrown, or a cancelled save would look
        // like a merely unknown answer and carry on.
        throw cancelled
    } catch (failure: Throwable) {
        // Offline, socket dropped, folder gone, a server that will not serve the property:
        // unknown, which forbids the destroy.
        null
    }
    return draftReceiptRequestIsCarried(original, replacement)
}

/**
 * The addresses of the Cc/Bcc [entries] an IMAP envelope gave for a draft, or `null` when one of
 * them is present but yields no address.
 *
 * ⭐ Present-but-unreadable is an unknown, never an entry quietly left out. A server can hand back
 * a recipient with an empty address — it happens with an address it judged invalid, measured on
 * the bench and handled in the composer (`ComposeText.displayAddresses`). Filtering that entry out
 * would shrink the set the destroy decision reads, and if it was the only Cc the set would shrink
 * to EMPTY: "nothing to lose", destroy authorised, and the copy holding the address is gone. So a
 * single unreadable entry makes the whole read unknown, and [draftAddressingIsCarried] keeps the
 * original.
 */
internal fun copiedAddressesOrNull(entries: List<ImapAddress>): Set<String>? {
    val addresses = entries.map { it.email?.trim().orEmpty() }
    if (addresses.any { it.isEmpty() }) return null
    return addresses.toSet()
}

private fun normalisedAddresses(addresses: Set<String>): Set<String> =
    addresses.mapNotNull { it.trim().lowercase().ifEmpty { null } }.toSet()
