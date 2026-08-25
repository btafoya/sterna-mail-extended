package app.sterna.core.data.mail

import app.sterna.core.data.db.OutboxState
import app.sterna.core.imap.ImapAddress
import kotlin.coroutines.cancellation.CancellationException

/**
 * Outcome of [MailRepository.saveDraft]. [ORIGINAL_KEPT] means the new draft could NOT reproduce
 * everything the edited one held — an attachment that couldn't be re-attached, an HTML body the
 * plain-text editor flattened, or an addressing that could not be shown to have survived — so the
 * original was deliberately left on the server: a duplicate in Drafts is recoverable, a destroyed
 * original is not (#63).
 */
enum class DraftSaveOutcome {
    SAVED,
    ORIGINAL_KEPT,

    /**
     * The draft is on the PHONE and not yet on the server: it was written locally before the upload
     * was attempted, and the upload failed — offline, a dropped socket, a server that refused (#95).
     *
     * ⛔ Not an error, and the composer must not show one. Nothing was lost: the text, its
     * addressing and its attachments are durable, and the server draft this one replaces (if any)
     * was left alone precisely because the upload did not happen.
     *
     * ⚠ Nothing SAYS so on screen yet. Marking a draft "not on the server" in the list is its own
     * volet, with the nine translations that go with it; here the value exists so no caller can read
     * a failed upload as a failed save.
     */
    KEPT_ON_DEVICE,
}

/**
 * Whether the composer has to tell the user something about a save's [outcome].
 *
 * ⭐ Executed rather than restated, because the answer for [DraftSaveOutcome.KEPT_ON_DEVICE] is the
 * user-visible half of issue #95: an offline save that produced a notice — or worse, the error
 * banner — would be the reported symptom with extra steps. Only a save that deliberately left the
 * edited original behind has anything to report.
 */
fun draftSaveNeedsNotice(outcome: DraftSaveOutcome): Boolean = outcome == DraftSaveOutcome.ORIGINAL_KEPT

/**
 * Whether a save's [outcome] permits the composer to CONSUME the queued outbox row it was editing
 * (#70 × #95) — i.e. to run `deleteOutbox`, which drops the row AND its staged attachment directory.
 *
 * ⛔ The destructive half of the save, and the one that must read the outcome. The route is: a
 * message is waiting to be sent, the user taps "edit", then "save" while the phone has no network.
 * The upload cannot happen, so the draft is [DraftSaveOutcome.KEPT_ON_DEVICE] — and until the volet
 * that shows local drafts lands, NOTHING on screen displays it. Deleting the queued row there takes
 * away the last copy the user can see, plus its files, in exchange for one that is invisible.
 *
 * Before the draft was written locally at all, `saveDraft` simply threw offline and this line was
 * never reached: the row stayed in the queue, on the Outbox screen, recoverable. Restoring that is
 * the whole rule — the queued row is consumed only once the draft has really reached the server.
 *
 * ⚠ The row is NOT left as it was either: see [draftSaveParksTheQueuedRowAs], which says where a
 * row that survives its save has to be put so it is neither hidden nor sent.
 */
fun draftSaveConsumesTheQueuedRow(outcome: DraftSaveOutcome): Boolean =
    outcome != DraftSaveOutcome.KEPT_ON_DEVICE

/**
 * The state the edited outbox row must be PARKED in after a save with this [outcome], or null when
 * that save consumed the row ([draftSaveConsumesTheQueuedRow]) and there is nothing left to park.
 *
 * ⛔ Leaving the row where the composer found it — [OutboxState.EDITING] — is a SEND the user never
 * asked for, and it is the defect this answers. EDITING is filtered out of the Outbox screen and
 * off the badge alike ([OutboxLogic.isWaitingInOutbox], [OutboxLogic.activeCount]), so the message
 * is on no screen at all; and the next launch reverts EDITING rows to QUEUED
 * (`MailRepository.revertEditingOutbox`) and re-arms them — the PRE-EDIT text goes out, with no
 * undo window, after the user tapped "save as draft". The KDoc above used to call that deliberate.
 *
 * ⛔ [OutboxState.FAILED] is the OTHER wrong answer, and it was the first attempt at this fix. It
 * has four of the five properties wanted, and the fifth is not a wording detail: FAILED is what
 * `OutboxLogic.needsFailureBanner` reads, so it lights "Some messages didn't send. Tap to review."
 * across the Inbox and tints the outbox dot red — over a save that did exactly what was asked, with
 * nothing left that could ever clear it, since there is no deferred upload here whose success would
 * take the banner back down.
 *
 * So [OutboxState.KEPT_AS_DRAFT], a value of its own, which costs no migration (`state` is TEXT and
 * Room stores the constant's name) and answers each question separately:
 *  - [OutboxLogic.isReadyToSend] false — the worker never sends it on its own;
 *  - [OutboxLogic.isWaitingInOutbox] true — the Outbox screen lists it;
 *  - it counts on the badge with NO grace ([OutboxLogic.activeCount]) — it shows at once;
 *  - [OutboxLogic.canEdit] true — it reopens, and Retry still sends it by hand;
 *  - [OutboxLogic.needsFailureBanner] FALSE — nothing failed, so nothing shouts.
 *
 * ⭐ Parking at SAVE time is also what keeps this out of the way of the startup recovery: a row
 * already parked is no longer EDITING, so `revertEditingOutbox` and [OutboxLogic.stateAfterEdit] —
 * whose job is a process death DURING an edit, and which stay as they are — never see it. That is
 * why `MailRepository.parkOutboxEdit` writes the state and nothing else: the `attemptCount` ruse
 * that kept a FAILED-parked row parked has no reader left.
 */
fun draftSaveParksTheQueuedRowAs(outcome: DraftSaveOutcome): OutboxState? =
    if (draftSaveConsumesTheQueuedRow(outcome)) null else OutboxState.KEPT_AS_DRAFT

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
 * Whether reopening a saved draft in this composer LOST something the server copy still holds —
 * the verdict `ComposeViewModel.prepare` computes on the draft it just fetched, and the one
 * [MailRepository.enqueueSend] reads back as `bodyIsLossy` before it may write a destroyable id
 * onto an outbox row. `true` means "this screen cannot give that draft back", so the original is
 * kept: a duplicate in Drafts is recoverable, a destroyed original is not (#63).
 *
 * ⭐ Public and taking primitives, unlike its `internal` sisters, for one reason: the caller lives
 * in `:app` and is an `AndroidViewModel` no JVM test can instantiate. Pulling the decision out
 * here is what makes it EXECUTABLE (`ReopenedDraftLossyTest`); the wiring that feeds it is all
 * that is left to a source lint (`DraftVerdictWiringTest`).
 *
 * The legs, each one a thing the composer shows the user nothing about:
 *  - [hasHtmlBody]: a draft authored elsewhere in HTML is flattened to plain text on open;
 *  - [inlineImageCount]: inline images are not carried into the editor;
 *  - [calendarPartCount]: calendar parts are not carried either;
 *  - [receiptHeader]: RFC 8098's `Disposition-Notification-To` of the draft as the SERVER holds
 *    it. The request lives in a header of the server copy and this composer's receipt checkbox
 *    comes back CLEAR when a draft is reopened (debt D1), so the message goes out asking for
 *    nothing — and destroying the original behind it takes the only copy that carried the
 *    request, in silence, with nothing on screen having said so.
 *
 * ⭐ The receipt leg asks whether the header is THERE, not whether it parses: absent or blank is
 * "nothing was asked for", and anything else — `<>`, an empty group, a word that is not an
 * address — is PRESENT-BUT-UNREADABLE, which is an unknown, and an unknown keeps the original.
 * Deliberately NOT [ReadReceiptHeader.recipients]`.isNotEmpty()`: that function decides no policy
 * (its own KDoc says so) and answers the same empty list for "no header" and "a header I could
 * not read", so reading it here would turn every malformed request into "nothing to lose",
 * authorise the destroy, and take the only copy that carried the request — the same mistake
 * [copiedAddressesOrNull] exists to refuse on the addressing. ⚠ The price, assumed: a draft whose
 * receipt header is malformed is kept, i.e. a duplicate in Drafts, which the user can delete.
 *
 * ⚠ This verdict also governs [MailRepository.saveDraft] (the composer hands it over as
 * `bodyIsLossy`), so a draft that asks for a receipt now RE-SAVES more cautiously too: it leaves
 * the server copy in place and the user sees a duplicate in Drafts. Deliberate, and the cheaper
 * of the two mistakes — a duplicate is deletable, a dropped receipt request is not recoverable.
 */
fun reopenedDraftIsLossy(
    hasHtmlBody: Boolean,
    inlineImageCount: Int,
    calendarPartCount: Int,
    receiptHeader: String?,
): Boolean = hasHtmlBody ||
    inlineImageCount > 0 ||
    calendarPartCount > 0 ||
    !receiptHeader.isNullOrBlank()

/**
 * The draft id a send may destroy once delivered, or null — the send path's use of the SAME rule
 * [MailRepository.saveDraft] answers to, never a second one (#63). [MailRepository.enqueueSend]
 * writes what this returns onto the outbox row, and [MailRepository.performSend] destroys only
 * what the row names: no proof, no id, no destroy — the message still goes out, nothing ever
 * holds it back, and the original survives in Drafts as a duplicate.
 *
 * Both attachment legs of [draftReplacementIsFaithful] receive [attachmentCount]: unlike a save,
 * the send is all-or-nothing about its attachments (enqueueSend rolls its row back on a part it
 * cannot stage, and delivery refuses to send short), so what goes out is exactly what compose was
 * showing. [bodyIsLossy] is the composer's verdict on the reopened draft, and `true` is also what
 * an unknown answers — a verdict that survived no persistence forbids the destroy.
 * [addressingIsCarried] is asked only when there is an id to destroy, so a send that replaces
 * nothing costs no server read.
 *
 * ⭐ `receiptRequestIsCarried = true`, and NOT a server read — the same shape of justification as
 * the `addressingIsCarried = true` of [MailRepository.saveDraft]'s JMAP branch: the leg is already
 * answered upstream by [bodyIsLossy]. On all four paths that can bring an id here, a clean
 * `bodyIsLossy == false` carries the reopen verdict of [reopenedDraftIsLossy], whose receipt leg
 * has already answered — SUBJECT TO the known hole below:
 *  - immediate send: `ComposeViewModel.prepare` computes the verdict with [reopenedDraftIsLossy],
 *    whose receipt leg answers `true` for a draft whose header asked for one;
 *  - a send undone and reopened: the verdict travels WITH the id
 *    (`SendOutbox.ComposeDraft.draftBodyIsLossy`), so it cannot be laundered back to false;
 *  - a message reopened from a persisted outbox row: the verdict did not survive that table, so
 *    `composeDraftOf` answers the unknown — `true` — and no id is written at all;
 *  - a scheduled send: `scheduleSend` writes the id only against a clean verdict, and the worker
 *    comes back through that one door with `bodyIsLossy = false`.
 *
 * ⚠ The hole, known and NOT closed here: all four paths read the header the composer was given,
 * and on a JMAP server that refuses `header:Disposition-Notification-To:asText`,
 * `dispositionNotificationTo` comes back `null` for EVERY message — a draft that does carry the
 * request included. The receipt leg then says "nothing asked", the verdict is clean, and the
 * original is destroyed. [MailRepository.saveDraft] does not have that hole: it asks
 * `client.refusesReceiptHeader` and turns a refusal into the unknown (`receiptRequestedInProperty`),
 * and nothing carries that bit down to the composer, which is what closing this would take. Not a
 * regression, and deliberately out of this change's scope: before it, the send destroyed the
 * original with no proof at all, on every server.
 *
 * ⛔ Deliberately NOT a read of the draft's header here (no `imapDraftReceiptRequested`): it would
 * be a full `FETCH` of the source, run inside `enqueueSend` BEFORE the outbox row exists, while
 * the message is only in the composer's RAM — and it would expire under `SEND_PROOF_BUDGET_MS` on
 * any draft of any size, which reads as "unknown" and would keep every such original.
 */
internal suspend fun replaceableDraftIdOrNull(
    draftEmailId: String?,
    attachmentCount: Int,
    bodyIsLossy: Boolean,
    addressingIsCarried: suspend (String) -> Boolean,
): String? {
    if (draftEmailId == null) return null
    val faithful = draftReplacementIsFaithful(
        attachmentsIn = attachmentCount,
        attachmentsCarried = attachmentCount,
        bodyIsLossy = bodyIsLossy,
        addressingIsCarried = addressingIsCarried(draftEmailId),
        receiptRequestIsCarried = true,
    )
    return if (faithful) draftEmailId else null
}

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
 * The addressing a replacement can offer as proof: its Cc AND its Bcc.
 *
 * ⭐ The Bcc counts SINCE 2026-08-25, and it did not before — the change is in what the phone
 * writes, not in what this rule wants. Both halves of the reason have to hold, and they do:
 *  - at SAVE time, `OutgoingMime.build(…, BlindCopies.WRITTEN)` now writes a real `Bcc:` header into
 *    the copy APPENDed to Drafts (and only into that copy), so an address typed into the composer's
 *    Bcc really is in the replacement the server keeps. Reopening it hands the address back. It is
 *    carried in fact, not by wish;
 *  - at SEND time no `Bcc:` header is written anywhere, and the address is carried all the same:
 *    the blind recipients go out in the envelope's `RCPT TO` and the mail is DELIVERED to them.
 *    Nothing is lost by destroying an original whose blind copy has just been delivered, even
 *    though no header of the sent message names it.
 *
 * ⛔ Being offered here is not being proven: this is one side of [draftAddressingIsCarried], whose
 * safe direction is unchanged. If the reopened draft gave nothing back — a read that failed, a
 * server copy that never carried the header, a cache row older than the `bccJson` column that no
 * back-fill ever filled in (schema v21 added it and nothing rewrites old rows) — then [bcc] is
 * empty here, the original's address is not in this set, the proof fails and the server copy
 * SURVIVES as a duplicate in Drafts. The unknown still keeps the draft.
 */
internal fun draftReplacementAddressing(cc: List<String>, bcc: List<String>): Set<String> =
    (cc + bcc).toSet()

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
