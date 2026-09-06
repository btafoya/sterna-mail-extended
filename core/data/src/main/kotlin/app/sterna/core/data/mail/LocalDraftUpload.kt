package app.sterna.core.data.mail

import app.sterna.core.data.db.LocalDraftEntity
import app.sterna.core.data.db.LocalDraftState
import app.sterna.core.data.db.OutboxAttachments
import app.sterna.core.jmap.model.EmailBodyPart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/*
 * Getting a draft the phone kept (#95) to the server later. Unlike the outbox this queue has no
 * attempt cap and no terminal state: only an upload that went through consumes a row.
 */

const val LOCAL_DRAFT_RETRY_BASE_MILLIS: Long = 30_000L

/**
 * The only thing that ends an `APPEND` whose peer stopped answering: without it the reads are
 * `soTimeout = 0`, the row stays `UPLOADING` with no backoff, and the IMAP mutex is held for ever.
 */
const val LOCAL_DRAFT_UPLOAD_BUDGET_MS: Int = 60_000

/**
 * Wall clock from `upload(row)` being entered to it returning. Needed beside the read budget because
 * the wait for the account's IMAP mutex has no bound of its own, and there the read budget never
 * starts ticking. Floor above one read's minute; ceiling half of WorkManager's ten.
 */
const val LOCAL_DRAFT_ATTEMPT_BUDGET_MS: Long = 5L * 60 * 1000

/**
 * An ordinary [Exception], deliberately not a [CancellationException]: the
 * [TimeoutCancellationException] `withTimeout` throws would be read by [uploadLocalDraftOnce] as
 * "the worker is being stopped" and rethrown, leaving the row `UPLOADING` with no backoff.
 */
class LocalDraftAttemptExpired(budgetMs: Long, cause: Throwable? = null) :
    Exception("The upload did not finish within ${budgetMs / 1000} s.", cause)

/**
 * `withTimeout` joins its block, so this bounds the cancellable waits and nothing else: name
 * resolution (`ImapClient-62`) and the JMAP call (`JmapClient-2752`, no `callTimeout`)
 * stay unbounded. Only the expiry is converted — a real cancellation is what makes
 * [uploadLocalDraftRebooking] use `onlyIfNothingBooked`.
 */
suspend fun <T> withLocalDraftAttemptBudget(budgetMs: Long, attempt: suspend () -> T): T =
    try {
        withTimeout(budgetMs) { attempt() }
    } catch (expired: TimeoutCancellationException) {
        throw LocalDraftAttemptExpired(budgetMs, expired)
    }

const val LOCAL_DRAFT_RETRY_MAX_MILLIS: Long = 6L * 60 * 60 * 1000

/**
 * Bounded before the shift, not after: `30_000 shl 64` is `30_000` again (Kotlin masks the shift),
 * so an unbounded [attemptCount] would wrap back to a 30-second retry storm.
 */
fun localDraftRetryDelayMillis(attemptCount: Int): Long {
    val failures = attemptCount.coerceAtLeast(1)
    val doublings = (failures - 1).coerceAtMost(MAX_DOUBLINGS)
    return (LOCAL_DRAFT_RETRY_BASE_MILLIS shl doublings).coerceAtMost(LOCAL_DRAFT_RETRY_MAX_MILLIS)
}

/** Enough doublings to pass the cap several times over, and few enough not to wrap the shift. */
private const val MAX_DOUBLINGS = 40

data class LocalDraftAttempt(
    val attemptCount: Int,
    val error: String?,
    val atMillis: Long,
    val notBeforeMillis: Long,
)

/**
 * The count is read off the row and incremented here rather than kept by the caller: the save and
 * the worker both fail into this, and either one's counter would reset every time the other ran.
 */
fun localDraftAttemptAfterFailure(
    row: LocalDraftEntity,
    failure: Throwable,
    nowMillis: Long,
): LocalDraftAttempt {
    val attempts = row.attemptCount + 1
    return LocalDraftAttempt(
        attemptCount = attempts,
        error = failure.message ?: failure.javaClass.simpleName,
        atMillis = nowMillis,
        notBeforeMillis = nowMillis + localDraftRetryDelayMillis(attempts),
    )
}

enum class LocalDraftUploadStep {
    UPLOADED,
    KEPT_FOR_LATER,

    NOT_DUE,

    GONE,
}

/** A null [retryAtMillis] is not "finished with": the row is gone, or a composer holds it. */
data class LocalDraftUploadResult(val step: LocalDraftUploadStep, val retryAtMillis: Long?)

/**
 * The same question `LocalDraftDao.pending` asks of the whole table, exhaustive so a fifth state has
 * to answer out loud. EDITING is held by a composer; STAGING still says "[]", and uploading that
 * sends the draft amputated.
 */
fun localDraftUploadIsDue(state: LocalDraftState, notBeforeMillis: Long, nowMillis: Long): Boolean =
    when (state) {
        LocalDraftState.PENDING, LocalDraftState.UPLOADING -> nowMillis >= notBeforeMillis
        LocalDraftState.STAGING, LocalDraftState.EDITING -> false
    }

/**
 * `UPLOADING` marks that an attempt started, it is not a lock: [localDraftUploadIsDue] and
 * `LocalDraftDao.pending` both count such a row as due, so one abandoned by a dying process is
 * picked up again — and two live workers can then both `APPEND`, stated and not guarded. [discard]
 * is on the success path only; in a `finally` it would delete the text on the very failure this
 * queue exists to survive.
 */
@Suppress("LongParameterList")
internal suspend fun uploadLocalDraftOnce(
    row: LocalDraftEntity?,
    nowMillis: Long,
    markUploading: suspend () -> Unit,
    upload: suspend (LocalDraftEntity) -> DraftSaveOutcome,
    discard: suspend (LocalDraftEntity) -> Unit,
    record: suspend (LocalDraftAttempt) -> Unit,
): LocalDraftUploadResult {
    if (row == null) return LocalDraftUploadResult(LocalDraftUploadStep.GONE, null)
    if (!localDraftUploadIsDue(row.state, row.notBeforeMillis, nowMillis)) {
        return LocalDraftUploadResult(
            LocalDraftUploadStep.NOT_DUE,
            row.notBeforeMillis.takeIf { it > nowMillis },
        )
    }
    markUploading()
    try {
        upload(row)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
        val attempt = localDraftAttemptAfterFailure(row, failure, nowMillis)
        // NonCancellable: losing the network is at once the likeliest reason the attempt failed and
        // WorkManager's reason to stop the worker, so this Room write would throw before landing.
        withContext(NonCancellable) { record(attempt) }
        return LocalDraftUploadResult(LocalDraftUploadStep.KEPT_FOR_LATER, attempt.notBeforeMillis)
    }
    discard(row)
    return LocalDraftUploadResult(LocalDraftUploadStep.UPLOADED, null)
}

/**
 * On a [CancellationException] nothing else can re-book, since the row's own `record` never runs —
 * hence [rebook] first, not suspending, and for [LOCAL_DRAFT_RETRY_BASE_MILLIS] rather than 0, or a
 * worker stopped under system pressure re-books itself for now and is re-stopped in a loop. That
 * path asks `onlyIfNothingBooked`: this queue books under one unique name, so a plain booking would
 * cancel WorkManager's own retry. [now] is read after [upload], or the delay carries its duration.
 */
suspend fun uploadLocalDraftRebooking(
    now: () -> Long,
    upload: suspend () -> LocalDraftUploadResult,
    rebook: (delayMillis: Long, onlyIfNothingBooked: Boolean) -> Unit,
): LocalDraftUploadResult {
    val result = try {
        upload()
    } catch (cancelled: CancellationException) {
        rebook(LOCAL_DRAFT_RETRY_BASE_MILLIS, true)
        throw cancelled
    }
    result.retryAtMillis?.let { rebook(localDraftScheduleDelayMillis(it, now()), false) }
    return result
}

/**
 * Only a row that has failed at least once: on a first attempt the `Message-ID` has never been on a
 * wire, and the duplicate this guards against needs a failed attempt to exist at all. The name leg
 * is unreachable today, kept because its failure is the losing one: abstain, then consume the row.
 */
fun localDraftMayAlreadyBeOnServer(row: LocalDraftEntity): Boolean =
    row.attemptCount > 0 && row.messageId.isNotBlank()

/**
 * Equality, never a substring: `contains` would make `<b@x>` a match for `ab@x`, and a false
 * "already there" abstains, consumes the row, and leaves the text nowhere. Case is not folded.
 */
fun localDraftMessageIdMatches(ours: String, reported: String?): Boolean {
    val mine = unbracketedMessageId(ours)
    val theirs = unbracketedMessageId(reported.orEmpty())
    return mine.isNotEmpty() && theirs.isNotEmpty() && mine == theirs
}

private fun unbracketedMessageId(value: String): String =
    value.trim().removeSurrounding("<", ">").trim()

/**
 * [alreadyThere] may throw — a live read over the link that just failed — and a lookup that throws
 * appends: abstaining on a failed read is the one outcome that loses the text, since
 * [uploadLocalDraftOnce] consumes the row the moment the upload returns.
 */
internal suspend fun appendDraftUnlessAlreadyThere(
    row: LocalDraftEntity,
    alreadyThere: suspend (messageId: String) -> Boolean,
    append: suspend () -> Unit,
): Boolean {
    if (localDraftMayAlreadyBeOnServer(row)) {
        val found = try {
            // Naked; the brackets the APPEND wrote are dealt with in [localDraftMessageIdMatches].
            alreadyThere(row.messageId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") unreadable: Throwable) {
            false
        }
        if (found) return false
    }
    append()
    return true
}

data class LocalDraftUploadJob(val accountId: String, val id: String, val delayMillis: Long)

fun localDraftScheduleDelayMillis(notBeforeMillis: Long, nowMillis: Long): Long =
    (notBeforeMillis - nowMillis).coerceAtLeast(0)

/**
 * Every row gets a job, backed off or not: WorkManager's own persistence is what a reboot loses, so
 * a draft that failed ten minutes before one must not be left with no work item (#95).
 */
fun localDraftUploadJobs(
    accountId: String,
    rows: List<LocalDraftEntity>,
    nowMillis: Long,
): List<LocalDraftUploadJob> = rows.map {
    LocalDraftUploadJob(accountId, it.id, localDraftScheduleDelayMillis(it.notBeforeMillis, nowMillis))
}

/**
 * The count matters as much as the bytes: `uploadDraft` weighs `attachments.size` against the parts
 * it built before letting `finishDraftSave` destroy the original (#63), so an `emptyList()` here
 * uploads every deferred draft amputated and reports it faithful.
 */
internal fun localDraftUploadAttachments(attachmentsJson: String?): List<EmailBodyPart> =
    OutboxAttachments.decode(attachmentsJson).map {
        EmailBodyPart(
            partId = it.path,
            blobId = it.blobId,
            size = it.size,
            type = it.type,
            name = it.name,
            disposition = it.disposition,
            cid = it.cid,
        )
    }

/**
 * The ids are read before the rows go: the per-draft directories are named after the ids and nothing
 * else names them, so deleting the rows first would strand the bytes with no row to find them by.
 */
internal suspend fun purgeLocalDraftsOfAccount(
    ids: suspend () -> List<String>,
    deleteRows: suspend () -> Unit,
    dirOf: (String) -> File,
) {
    val dirs = ids().map(dirOf)
    deleteRows()
    dirs.forEach { runCatching { it.deleteRecursively() } }
}
