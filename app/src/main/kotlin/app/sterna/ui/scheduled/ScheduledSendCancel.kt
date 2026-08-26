package app.sterna.ui.scheduled

import app.sterna.core.data.db.ScheduledSendEntity
import app.sterna.core.data.getOrElseUnlessCancelled
import app.sterna.core.data.mail.DraftSaveOutcome
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * What the "Scheduled" screen has to say after a cancellation (#170).
 *
 * The X on a row used to drop the WorkManager job and delete the queued row, and nothing else —
 * and scheduling saves NO draft (`ComposeViewModel.scheduleSend` never calls `saveDraft`), so that
 * row was the only copy of the message. Cancelling destroyed the text, with no confirmation and no
 * undo. Cancelling now files the message in Drafts first, and this says which of the three things
 * happened so the screen can be honest about it.
 */
enum class ScheduledCancelReport {
    /** The message is in Drafts — on the server, or durably on the phone with an upload booked. */
    RETURNED_TO_DRAFTS,

    /** The row's account no longer exists, so there was nowhere to file it; the row is dropped. */
    ACCOUNT_GONE,

    /** Filing it failed, so NOTHING was destroyed: the row is still scheduled and still the copy. */
    NOT_CANCELLED,
}

/** The fields a scheduled row becomes when it is filed back as a draft. */
data class ScheduledSendDraft(
    val to: List<String>,
    val cc: List<String>,
    val bcc: List<String>,
    val subject: String,
    val body: String,
    val inReplyTo: List<String>,
    val references: List<String>,
    val fromName: String?,
    val fromEmail: String?,
    val requestReceipt: Boolean,
)

/**
 * The scheduled [row] read back into the fields `saveDraft` takes — the same splitting
 * [app.sterna.send.ScheduledSendWorker] does at fire time, deliberately: the row is stored as text
 * ("comma-separated", "space-separated message-ids"), and a cancel that parsed it differently from
 * the send would file a draft addressed differently from the message that was queued.
 *
 * ⛔ `htmlBody` has NO destination and is not carried: `saveDraft` takes no HTML parameter (stated
 * debt at `LocalDraftReopen.kt:119`), and the HTML body is DERIVED from the typed text — so the
 * text survives whole, exactly as it does through "Save draft". Inventing a carrier here would
 * claim a fidelity nothing downstream can honour.
 *
 * ⛔ `draftEmailId` / `draftUidValidity` are NOT carried either, and that is the decision of § 3:
 * naming the server draft this message was edited from would let the save DESTROY it, while the
 * deposit flattens the HTML body. A duplicate in Drafts is recoverable; a destroyed original is
 * not. The columns stay on the row for the SEND path, which still uses them.
 *
 * ⭐ [fromName]/[fromEmail] are carried: without them the returned draft goes back under the login
 * account's address rather than the identity the user chose (#31).
 */
fun draftFromScheduledSend(row: ScheduledSendEntity): ScheduledSendDraft = ScheduledSendDraft(
    to = row.recipients.split(",").map { it.trim() }.filter { it.isNotEmpty() },
    cc = row.cc?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
    bcc = row.bcc?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
    subject = row.subject,
    body = row.textBody,
    inReplyTo = row.inReplyTo?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
    references = row.references?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
    fromName = row.fromName,
    fromEmail = row.fromEmail,
    requestReceipt = row.requestReceipt,
)

/**
 * What to say for a save's [outcome], `null` meaning the account was gone before a save was tried.
 *
 * ⭐ [DraftSaveOutcome.KEPT_ON_DEVICE] is a SUCCESS here, and that is the answer that matters. An
 * offline deposit writes a durable local row and books the upload (see the KDoc of
 * [DraftSaveOutcome]); reading it as a failure would keep the scheduled row, and the message would
 * then GO OUT at its hour after the user cancelled it — the opposite of the gesture, on the one
 * path where nothing on screen says the draft exists.
 *
 * [DraftSaveOutcome.ORIGINAL_KEPT] is a success too: it means a copy was made and some other
 * original was spared, and there is no original of ours here to spare — we name none.
 *
 * Exhaustive over the enum on purpose (no `else`): a fourth outcome added to `saveDraft` must fail
 * to compile here rather than be classified by whichever branch happens to catch it.
 */
fun scheduledCancelReport(outcome: DraftSaveOutcome?): ScheduledCancelReport = when (outcome) {
    null -> ScheduledCancelReport.ACCOUNT_GONE
    DraftSaveOutcome.SAVED -> ScheduledCancelReport.RETURNED_TO_DRAFTS
    DraftSaveOutcome.ORIGINAL_KEPT -> ScheduledCancelReport.RETURNED_TO_DRAFTS
    DraftSaveOutcome.KEPT_ON_DEVICE -> ScheduledCancelReport.RETURNED_TO_DRAFTS
}

/**
 * Whether the queued row may now be destroyed — i.e. whether another copy of the text exists, or
 * whether there is no account left that could ever send it.
 */
fun scheduledCancelDropsTheRow(report: ScheduledCancelReport): Boolean =
    report != ScheduledCancelReport.NOT_CANCELLED

/**
 * Cancel the scheduled send [id]: file it in Drafts, and ONLY THEN drop its row and its job (#170).
 *
 * ⛔ **Two orders, and together they are the whole correction**; both are grid rule 1 (nothing
 * irreversible first).
 *
 * ⛔ **[saveDraft] before the destruction.** The scheduled row is the only copy of the message;
 * [saveDraft] is what creates the other one. Deleted first — or in a `finally` — a deposit that
 * throws leaves the message on NEITHER side: the reported defect, in worse. Same shape, and the
 * same reason, as [app.sterna.core.data.mail.commitThenConsumeLocalDraft].
 *
 * ⛔ **[deleteRow] before [dropWorker].** The delete is a committed write when it returns;
 * `WorkManager.cancelUniqueWork` is asynchronous and its return proves nothing. A row that outlives
 * its job is re-armed at EVERY process start (`AppContainer.init`) and the cancelled message goes
 * out at its hour, with a copy left in Drafts. A job that outlives its row fires on nothing
 * (`ScheduledSendWorker.kt:24` returns on a missing row), so the row is the single source of truth
 * and the job a satellite. Pinned as source text by `ScheduledCancelDropOrderTest`.
 *
 * ⚠ **What actually reaches [ScheduledCancelReport.NOT_CANCELLED] is narrow, and saying otherwise
 * once cost this file a review.** `saveDraft` does NOT throw for a transport failure: offline, a
 * dropped socket, a server that refuses, an account with no Drafts folder are all caught inside
 * `saveDraftLocalFirst` and answered [DraftSaveOutcome.KEPT_ON_DEVICE] — a success, and the row
 * goes. A throw here is a local write that failed (Room, attachment staging), or an addressing
 * `requireSingleLineAddresses` refuses before anything is written.
 *
 * ⛔ …plus the one case that is NOT a save failure at all: [credentials] answering `null` while
 * [accountIsGone] says the account is still there. See below.
 *
 * ⛔ **Sheltered from cancellation, both halves together.** The caller runs off a tap on a screen
 * the user leaves at once; cancellation landing between the deposit and the delete would leave the
 * message in Drafts AND still scheduled, so it goes out anyway. The boundary is read BEFORE the
 * shelter is entered ([ensureActive]): nothing has been written yet at that point, so a caller
 * already gone takes nothing with it and the scheduled message simply stays scheduled.
 *
 * ⛔ [credentials] is asked for the account of the ROW, never a displayed one: the screen is not
 * filtered by account (`ScheduledSendDao.kt:19`) and does not show which account a line belongs to,
 * so a message would be filed into someone else's Drafts — or fail to be filed at all.
 *
 * ⛔ **And a `null` from [credentials] is NOT proof that the account is gone**, which is why
 * [accountIsGone] exists and is asked separately. `AccountStore.credentials` answers `null` for a
 * removed account AND for one whose stored secret would not decode (`readPassword`, an invalidated
 * Keystore key), and `AccountStore.accounts()` answers an EMPTY LIST when the blob itself cannot be
 * read — which is exactly why `accountsUnreadable()` was built (`AccountStore.kt:304`). Destroying
 * the row on an unproven `null` reproduces #170 with a snackbar claiming a cause that is false: the
 * only copy of the text deleted because a key rotated. Unproven ⇒ nothing is destroyed.
 *
 * A [row] that is no longer there (already fired, already cancelled) is `null`: nothing to do and
 * nothing to say.
 */
suspend fun <C> cancelScheduledSend(
    id: Long,
    row: suspend (Long) -> ScheduledSendEntity?,
    credentials: suspend (String) -> C?,
    accountIsGone: suspend (String) -> Boolean,
    saveDraft: suspend (C, ScheduledSendDraft) -> DraftSaveOutcome,
    dropWorker: (Long) -> Unit,
    deleteRow: suspend (Long) -> Unit,
): ScheduledCancelReport? {
    val r = row(id) ?: return null
    // The boundary, read BEFORE the shelter: nothing is written yet, so a caller already gone
    // leaves the message scheduled — which is where the user last saw it.
    coroutineContext.ensureActive()
    return withContext(NonCancellable) {
        val c = credentials(r.accountId)
        val outcome = when {
            // A deposit that throws destroys nothing: the row stays, and it is still the copy.
            // ⛔ Not `runCatching { }.getOrNull()`: that reads a cancellation as a failed save.
            c != null -> runCatching { saveDraft(c, draftFromScheduledSend(r)) }
                .getOrElseUnlessCancelled { return@withContext ScheduledCancelReport.NOT_CANCELLED }
            // No credentials AND the account really is gone: nowhere to file it, and nothing left
            // that could ever send it either — the row goes, and the screen says no draft was kept.
            accountIsGone(r.accountId) -> null
            // No credentials but the account is still there — an unreadable secret, an unreadable
            // account blob. We cannot file it and we have proved nothing: destroy nothing.
            else -> return@withContext ScheduledCancelReport.NOT_CANCELLED
        }
        val report = scheduledCancelReport(outcome)
        if (scheduledCancelDropsTheRow(report)) {
            deleteRow(id)
            dropWorker(id)
        }
        report
    }
}
