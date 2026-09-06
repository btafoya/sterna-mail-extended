package app.sterna.ui.scheduled

import app.sterna.core.data.db.ScheduledSendEntity
import app.sterna.core.data.getOrElseUnlessCancelled
import app.sterna.core.data.mail.DraftSaveOutcome
import app.sterna.core.data.text.draftHtmlToSave
import app.sterna.core.data.text.richBodyFrom
import app.sterna.core.data.text.toPlainText
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** What the "Scheduled" screen has to say after a cancellation (#170). The X used to drop the job
 *  and delete the queued row, and scheduling saves NO draft — so that row was the only copy of the
 *  message. Cancelling now files it in Drafts first, and this says which of the three happened. */
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
    /** The `text/html` part the returned draft is stored with (#131), or null. NOT the row's own
     *  `htmlBody`, which is the SEND's html: only the part that reads back as styling survives. */
    val html: String?,
    val inReplyTo: List<String>,
    val references: List<String>,
    val fromName: String?,
    val fromEmail: String?,
    val requestReceipt: Boolean,
)

/**
 * The scheduled [row] read back into the fields `saveDraft` takes — the same splitting
 * [app.sterna.send.ScheduledSendWorker] does at fire time, the row being stored as text and a cancel
 * that parsed it differently filing a draft addressed differently from the queued message.
 *
 * `htmlBody` is carried since #131, so a styled message keeps its bold while a forward's html is
 * flattened to text. The row's html is NOT stored as it stands: it is what the SEND built, and a
 * draft carrying it would reopen as text one save later. `draftEmailId` / `draftUidValidity` are
 * NOT carried: naming the server draft would let the save DESTROY it while the deposit flattens the
 * body, and a duplicate in Drafts is recoverable where a destroyed original is not.
 * [fromName]/[fromEmail] are carried, or the draft goes back under the login's address (#31).
 */
fun draftFromScheduledSend(row: ScheduledSendEntity): ScheduledSendDraft {
    // The html first, the stored text as the fallback, and the draft's own html derived from what
    // came back rather than copied from the row: both halves out of ONE answer. Same accepted cost
    // as `composeDraftOf` — an unparsable html files a draft holding `- milk` as letters. And the
    // text filed is `toPlainText`, never `rich.text` (#131).
    val rich = richBodyFrom(row.htmlBody) { row.textBody }
    return ScheduledSendDraft(
        to = row.recipients.split(",").map { it.trim() }.filter { it.isNotEmpty() },
        cc = row.cc?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
        bcc = row.bcc?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
        subject = row.subject,
        body = toPlainText(rich),
        html = draftHtmlToSave(rich),
        inReplyTo = row.inReplyTo?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
        references = row.references?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
        fromName = row.fromName,
        fromEmail = row.fromEmail,
        requestReceipt = row.requestReceipt,
    )
}

/**
 * What to say for a save's [outcome], `null` meaning the account was gone before a save was tried.
 * [DraftSaveOutcome.KEPT_ON_DEVICE] is a SUCCESS here: reading it as a failure would keep the
 * scheduled row, so the message would GO OUT at its hour after the user cancelled it.
 * [DraftSaveOutcome.ORIGINAL_KEPT] is one too, there being no original of ours to spare.
 * Exhaustive over the enum: a fourth outcome must fail to compile here.
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
 * Two orders, and together they are the whole correction. [saveDraft] BEFORE the destruction, the
 * scheduled row being the only copy of the message: deleted first, a deposit that throws leaves it
 * on neither side. [deleteRow] BEFORE [dropWorker], the delete being committed when it returns while
 * `cancelUniqueWork` is asynchronous: a row that outlives its job is re-armed at every process start
 * and goes out anyway. Pinned as source text by `ScheduledCancelDropOrderTest`.
 *
 * What reaches [ScheduledCancelReport.NOT_CANCELLED] is narrow: `saveDraft` does NOT throw for a
 * transport failure, offline and a refused server both answering KEPT_ON_DEVICE.
 *
 * Sheltered from cancellation, both halves together: the user leaves this screen at once, and a
 * cancellation between deposit and delete would leave the message in Drafts AND still scheduled.
 * The boundary is read BEFORE the shelter, when nothing is written yet.
 *
 * [credentials] is asked for the account of the ROW, the screen not being filtered by account.
 * And a `null` from it is NOT proof that the account is gone — it also answers null for a secret
 * that would not decode — which is why [accountIsGone] is asked separately: destroying the row on an
 * unproven null reproduces #170 with a snackbar claiming a false cause.
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
    // The boundary, read BEFORE the shelter: a caller already gone leaves the message scheduled.
    coroutineContext.ensureActive()
    return withContext(NonCancellable) {
        val c = credentials(r.accountId)
        val outcome = when {
            // A deposit that throws destroys nothing: the row stays, and it is still the copy.
            // Not `runCatching { }.getOrNull()`, which reads a cancellation as a failed save.
            c != null -> runCatching { saveDraft(c, draftFromScheduledSend(r)) }
                .getOrElseUnlessCancelled { return@withContext ScheduledCancelReport.NOT_CANCELLED }
            // No credentials AND the account really is gone: nowhere to file it, and nothing left
            // that could send it.
            accountIsGone(r.accountId) -> null
            // No credentials but the account is still there: we have proved nothing, destroy nothing.
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
