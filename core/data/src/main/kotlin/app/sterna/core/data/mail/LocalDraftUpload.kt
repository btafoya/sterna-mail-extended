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

/**
 * Getting a draft the phone kept (#95) to the server LATER, and every decision that goes with it,
 * as functions a JVM test can execute: the worker is a `CoroutineWorker`, the store is Room and the
 * scheduler is WorkManager, so none of the three can be reached from one.
 *
 * ⛔ **Three things this deliberately does NOT have, and they are the whole point.** The outbox has
 * all three (`OutboxLogic.MAX_ATTEMPTS`, `OutboxState.FAILED`, `deleteOutbox`) because an
 * undeliverable message has a recipient waiting and has to stop being retried:
 *  - **no attempt cap.** A phone can be weeks away from the network its account is on;
 *  - **no terminal state.** [LocalDraftState] has none to park a row in, on purpose;
 *  - **no automatic deletion.** A row is consumed by an upload that went through, and by nothing
 *    else. The hundredth failure leaves the draft exactly as the first one did.
 *
 * What IS bounded is the DELAY between two attempts ([LOCAL_DRAFT_RETRY_MAX_MILLIS]) — the opposite
 * trade: a draft is never given up on, it is simply asked about less often.
 */

/** The wait after the first failure — the outbox's own base delay (`Outbox`), for the same reason. */
const val LOCAL_DRAFT_RETRY_BASE_MILLIS: Long = 30_000L

/**
 * ⭐ **How long one deferred attempt may hold a socket** — the `ImapReadBudget` the upload runs
 * under, and the only thing that ends an `APPEND` whose peer stopped answering.
 *
 * ⛔ Without it the attempt's reads are `soTimeout = 0`: the link dies mid-`APPEND`, the thread sits
 * in a `read()` that never returns, the row stays `UPLOADING` with `attemptCount` frozen, no backoff
 * is ever armed and the account's IMAP mutex is held by a thread nobody can interrupt. Cancelling
 * the coroutine does not help — that is measured, not feared.
 *
 * The floor is a server that has just swallowed an attachment: it answers in seconds, and a minute
 * is far more than the worst honest case. The ceiling is WorkManager's ten minutes, after which a
 * worker is stopped whatever it is doing — the attempt has to finish, record its failure and book
 * its own retry INSIDE its own run, or the row is left exactly as this constant exists to prevent.
 *
 * ⛔ A bound on the WAIT, never a cap on attempts: an expiry is an ordinary failure, and
 * [localDraftAttemptAfterFailure] writes it on the row like any other.
 */
const val LOCAL_DRAFT_UPLOAD_BUDGET_MS: Int = 60_000

/**
 * ⭐ **How long one deferred attempt may spend UPLOADING** — five minutes, wall clock, from
 * `upload(row)` being entered to it returning.
 *
 * ⛔ **NOT from the row being marked in flight**, which is what this said and it was false. The
 * budget is installed inside the `upload` seam alone (`MailRepository.uploadLocalDraft`), so the
 * `localDraftDao.byId` that reads the row and the `markUploading` that flags it both run BEFORE it
 * and are bounded by nothing. Two Room writes, small — but outside the bound, and said so rather
 * than rounded in.
 *
 * ⛔ **This is not a third read budget, it is a bound one level up, and that is the point.** Three
 * separate blocking primitives have been found under this one attempt and bounded one by one — an
 * IMAP read ([LOCAL_DRAFT_UPLOAD_BUDGET_MS]), the session exit (`LOGOUT_READ_BUDGET_MS`), and then
 * the WAIT FOR THE ACCOUNT'S IMAP MUTEX, which is where 6 min 29 was measured: a `loadFolder`
 * retrying under `runWithRetry` holds `Pooled.mutex`, the attempt queues behind it, and the read
 * budget never starts ticking because the attempt never gets the session. That list has no end, so
 * the bound stops being written per primitive and is written on the ATTEMPT.
 *
 * The floor is an order of magnitude above one read's minute, so that a bounded READ fails first and
 * writes its own, true cause on the row; reaching this bound means the attempt was stuck somewhere
 * that has no budget of its own. The ceiling is half of WorkManager's ten minutes, after which a
 * worker is stopped whatever it is doing — leaving room to record the failure and re-book the retry
 * inside the same run.
 *
 * ⛔ It is a bound on the WAIT, never a cap on attempts: [withLocalDraftAttemptBudget] converts an
 * expiry into an ordinary failure, which [localDraftAttemptAfterFailure] writes on the row like any
 * refusal from the server.
 */
const val LOCAL_DRAFT_ATTEMPT_BUDGET_MS: Long = 5L * 60 * 1000

/**
 * What an attempt that ran out of [LOCAL_DRAFT_ATTEMPT_BUDGET_MS] is written down as.
 *
 * ⛔ **An ordinary [Exception], deliberately NOT a [CancellationException].** The whole conversion
 * exists because `withTimeout` throws a [TimeoutCancellationException], which IS one: left as it is,
 * [uploadLocalDraftOnce] reads it as "the worker is being stopped", rethrows it untouched, and the
 * row stays `UPLOADING` with no counter, no cause and no backoff — the exact symptom that was
 * measured, now under a surface that merely looks bounded.
 */
class LocalDraftAttemptExpired(budgetMs: Long, cause: Throwable? = null) :
    Exception("The upload did not finish within ${budgetMs / 1000} s.", cause)

/**
 * Run one deferred upload [attempt] under a wall-clock bound of [budgetMs], and turn running out of
 * it into an ORDINARY failure (#95).
 *
 * ⛔ **`withTimeout` JOINS its block, so this bounds the ANNULABLE waits and nothing else.** What it
 * really closes is the wait this volet was written for — the account's IMAP mutex, a `Mutex.lock()`
 * suspension, which gives way the instant its coroutine is cancelled — and every other suspending
 * wait under the attempt. ⛔ It does NOT end a blocking call already engaged on a thread, and two
 * such calls under this very attempt carry no bound of their own:
 *  - **name resolution**, `core/imap/src/main/kotlin/app/sterna/core/imap/ImapClient.kt:61-62`: the
 *    connect timeout is a socket option, and no socket option covers the lookup that precedes it;
 *  - **the JMAP call**, `core/jmap/src/main/kotlin/app/sterna/core/jmap/JmapClient.kt:2750-2752`:
 *    OkHttp is built with `connectTimeout` and `readTimeout` and NO `callTimeout`, so a response
 *    that trickles in for ever is bounded by neither of them.
 * On either of those the coroutine comes back on the deadline and the THREAD keeps waiting: what is
 * bounded is the ATTEMPT, not the socket. ⛔ Never write "whatever it waits on" here — the same
 * reading is already argued at `app/src/main/kotlin/app/sterna/ui/inbox/BoundedRefresh.kt:61` and
 * `core/data/src/main/kotlin/app/sterna/core/data/mail/ImapMailService.kt:997`.
 *
 * ⛔ **Only the expiry is converted.** A real cancellation — WorkManager stopping the worker — must
 * stay a cancellation all the way out: it is what makes [uploadLocalDraftRebooking] re-book the run
 * with `onlyIfNothingBooked = true`. Catching [CancellationException] in general instead would
 * record a system stop as a failed attempt, push the backoff out, and take away the one path that
 * re-books a stopped worker.
 *
 * ⚠ `withTimeout` cancels the scope it makes for [attempt], and NOT the coroutine calling this — so
 * the caller is still active afterwards and the `record` that follows the throw really runs, **as
 * long as the expiry arrives on its own**. ⛔ It often does not: the worker runs under a network
 * constraint, and losing the network is at once the likeliest reason the attempt ran out of time and
 * WorkManager's reason to stop the worker. A stop landing there cancels the CALLER, and then the
 * suspending `record` throws before it writes anything — which is why [uploadLocalDraftOnce] writes
 * the attempt under `NonCancellable`, not a reason it need not. `LocalDraftAttemptIsBoundedTest`
 * executes both halves rather than assuming either.
 */
suspend fun <T> withLocalDraftAttemptBudget(budgetMs: Long, attempt: suspend () -> T): T =
    try {
        withTimeout(budgetMs) { attempt() }
    } catch (expired: TimeoutCancellationException) {
        throw LocalDraftAttemptExpired(budgetMs, expired)
    }

/**
 * The longest a draft ever waits between two attempts: six hours.
 *
 * ⛔ A cap on the DELAY, never on the number of attempts. Without it the doubling runs away and the
 * fiftieth failure would book a wake-up years out, which is an attempt cap wearing a clock. With it,
 * a phone that has been offline for a fortnight still asks four times a day, forever.
 */
const val LOCAL_DRAFT_RETRY_MAX_MILLIS: Long = 6L * 60 * 60 * 1000

/**
 * How long to wait before the next upload attempt of a draft that has failed [attemptCount] times —
 * 30 s, then doubling, then flat at [LOCAL_DRAFT_RETRY_MAX_MILLIS].
 *
 * The doubling is bounded before the shift, not after: `30_000 shl 64` is not a huge number, it is
 * `30_000` again (Kotlin masks the shift), so an unbounded count would silently wrap back to a
 * 30-second retry storm at the exact moment the backoff mattered most.
 */
fun localDraftRetryDelayMillis(attemptCount: Int): Long {
    val failures = attemptCount.coerceAtLeast(1)
    val doublings = (failures - 1).coerceAtMost(MAX_DOUBLINGS)
    return (LOCAL_DRAFT_RETRY_BASE_MILLIS shl doublings).coerceAtMost(LOCAL_DRAFT_RETRY_MAX_MILLIS)
}

/** Enough doublings to pass the cap several times over, and few enough not to wrap the shift. */
private const val MAX_DOUBLINGS = 40

/** What a failed upload writes on the row: the counter, the cause, and when to come back. */
data class LocalDraftAttempt(
    val attemptCount: Int,
    val error: String?,
    val atMillis: Long,
    val notBeforeMillis: Long,
)

/**
 * The row's next state after [failure], at [nowMillis].
 *
 * ⚠ [LocalDraftEntity.attemptCount] is read off the row and incremented here rather than counted by
 * the caller: the save and the worker both fail into this, and a counter kept by either would reset
 * every time the other one ran. **Between two deferred attempts** the count therefore accumulates,
 * and the wait doubles with it.
 *
 * ⭐ **A re-save starts the count again, and that is the shipped behaviour.** `localDraftRow` builds
 * a fresh entity every time the user saves, so the row handed here after a re-save carries a count
 * of zero and the wait goes back to [LOCAL_DRAFT_RETRY_BASE_MILLIS]. It is defensible and it is
 * kept: the user has just touched this draft, so asking the server again promptly is what she would
 * expect, and the backoff exists to spare the battery of a phone nobody is holding — not to punish
 * a draft that is being worked on. ⛔ It is stated here because the opposite was written and was
 * false; the risk it carries is bounded by the fact that nothing here is ever given up on anyway.
 */
fun localDraftAttemptAfterFailure(
    row: LocalDraftEntity,
    failure: Throwable,
    nowMillis: Long,
): LocalDraftAttempt {
    val attempts = row.attemptCount + 1
    return LocalDraftAttempt(
        attemptCount = attempts,
        // The transport's own words, kept so a later screen can say why the draft is still here.
        // `javaClass.simpleName` for the exceptions that carry no message at all.
        error = failure.message ?: failure.javaClass.simpleName,
        atMillis = nowMillis,
        notBeforeMillis = nowMillis + localDraftRetryDelayMillis(attempts),
    )
}

/** What one upload attempt concluded. ⛔ None of the four is "give up": there is no such outcome. */
enum class LocalDraftUploadStep {
    /** The draft is on the server and the local row has been consumed. */
    UPLOADED,

    /** The upload failed. The row is untouched but for its backoff — it will be tried again. */
    KEPT_FOR_LATER,

    /** Not this row's turn: its backoff has not run out, or a composer is holding it. */
    NOT_DUE,

    /** No such row: it was uploaded or discarded between the scheduling and the wake-up. */
    GONE,
}

/**
 * The verdict of one attempt, and the instant to come back at — `null` meaning "book nothing".
 *
 * ⛔ A null [retryAtMillis] never means "this draft is finished with". It means there is nothing for
 * a TIMER to do: the row is gone, or it is open in a composer, whose own save re-arms it.
 */
data class LocalDraftUploadResult(val step: LocalDraftUploadStep, val retryAtMillis: Long?)

/**
 * Whether one row may be uploaded at [nowMillis] — the same question `LocalDraftDao.pending` asks of
 * the whole table, for the single row a worker was woken up for.
 *
 * Exhaustive on purpose: a fifth state has to answer out loud rather than inherit an `else`.
 * [LocalDraftState.EDITING] is held by a composer (#70's rule) and [LocalDraftState.STAGING] still
 * says `attachmentsJson` "[]" while its files are being copied — uploading that sends the draft
 * amputated. `LocalDraftDaoSqlTest` runs the SQL over the same table of rows this is pinned on, so
 * the two readings cannot drift apart.
 */
fun localDraftUploadIsDue(state: LocalDraftState, notBeforeMillis: Long, nowMillis: Long): Boolean =
    when (state) {
        LocalDraftState.PENDING, LocalDraftState.UPLOADING -> nowMillis >= notBeforeMillis
        LocalDraftState.STAGING, LocalDraftState.EDITING -> false
    }

/**
 * ⭐ **One deferred attempt at one draft, and the order is the whole of it.**
 *
 * 1. no row at all — it reached the server already, or the user threw it away: nothing to do;
 * 2. not its turn ([localDraftUploadIsDue]) — come back when its backoff runs out, and book NOTHING
 *    for a row a composer is holding: `notBeforeMillis` is in the past for it, so a wake-up would
 *    fire at once and again and again for as long as the user keeps typing;
 * 3. mark it [LocalDraftState.UPLOADING] — a marker of "an attempt started", NOT a lock: both
 *    [localDraftUploadIsDue] and `LocalDraftDao.pending` count an `UPLOADING` row as due, on purpose,
 *    so a row abandoned by a dying process is picked up again. Two live workers can therefore hold
 *    the same row, and what stands between them and two copies on the server depends on the ROW:
 *    the `Message-ID` search ([appendDraftUnlessAlreadyThere]) only runs when
 *    [localDraftMayAlreadyBeOnServer] says so, i.e. `attemptCount > 0` and a named row — so on a
 *    row that has already failed once it may catch the other worker's copy, and on a FRESH row
 *    (`attemptCount == 0`) BOTH workers skip the search and BOTH `APPEND`, with nothing whatever
 *    in between. Stated, not guarded: the trade is [localDraftMayAlreadyBeOnServer]'s, and a
 *    duplicate in Drafts is the outcome this file prefers to a draft that never left;
 * 4. upload it — through the caller's own upload, i.e. the one that carries every guard about
 *    destroying the original (#63, #99). This function knows nothing about servers;
 * 5. on success, and ONLY on success, consume the row and its staged files;
 * 6. on failure, [record] the attempt: the counter, the cause, and the instant the backoff moves the
 *    row to, ⛔ **under [NonCancellable]** — a stop landing on that write is not rare, it is the
 *    ordinary case (see the note at the call). ⛔ The row itself is not touched otherwise — same
 *    text, same attachments, same id.
 *
 * ⛔ [discard] is on the success path and nowhere else. In a `finally`, or before the outcome is
 * known, it deletes the typed text on exactly the failure this whole chantier exists to survive.
 *
 * ⛔ A failure does NOT reach the caller and is not a reason to stop: it is written on the row and
 * reported as [LocalDraftUploadStep.KEPT_FOR_LATER]. A [CancellationException] is not a failure —
 * the worker is being stopped — so it is rethrown and the row keeps the state it had, which the next
 * startup's `revertUploadingToPending` rescues.
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
        // ⛔ [NonCancellable], the same motif `saveDraftLocalFirst` already applies to the writes it
        // cannot leave half done. [record] is a SUSPENDING Room write, and the two events arrive
        // together by construction: the worker runs under a network constraint, so losing the
        // network is at once the likeliest reason the attempt failed and WorkManager's own reason to
        // stop the worker. Cancelled between the throw and the write landing, `record` throws before
        // writing anything — no counter, no cause, no backoff — and the ordinary failure this branch
        // exists to write down is requalified as a stop, leaving the row UPLOADING exactly as the
        // measured symptom did.
        // ⛔ The WRITE only. The `discard` on the success path and the other callers of
        // `discardLocalDraft` are a separate, already-written debt; widening this would start
        // running deletes inside a coroutine that is going away.
        withContext(NonCancellable) { record(attempt) }
        return LocalDraftUploadResult(LocalDraftUploadStep.KEPT_FOR_LATER, attempt.notBeforeMillis)
    }
    discard(row)
    return LocalDraftUploadResult(LocalDraftUploadStep.UPLOADED, null)
}

/**
 * ⭐ **What a deferred attempt books for itself, including when it is not allowed to finish** (#95).
 *
 * Three outcomes, and the first is the one that was missing:
 *  1. a [CancellationException] — WorkManager stopped the worker mid-upload (ten minutes elapsed,
 *     the network constraint dropped, the process is going away). ⛔ **Nothing else can re-book
 *     here**: every suspending call in a cancelled coroutine throws at once, so the `record` that
 *     arms the row's own backoff never runs and the row keeps whatever state it had. So the retry
 *     is booked FIRST, from [rebook] — deliberately NOT a suspending function, which is why it can
 *     still run — and the cancellation is then let through untouched;
 *  2. a result carrying a [LocalDraftUploadResult.retryAtMillis] — the ordinary re-booking the
 *     worker already did, moved here so a test can execute it;
 *  3. a result carrying none — the row is gone, or a composer holds it and its own save re-arms it.
 *     ⛔ Booking "just in case" here is what turns a row a composer is holding into a wake-up every
 *     few seconds for as long as the user keeps typing.
 *
 * ⛔ [LOCAL_DRAFT_RETRY_BASE_MILLIS] on the cancellation, never 0: a worker stopped under system
 * pressure that re-books itself for *now* is re-stopped and re-books itself again, which is a loop
 * against the very condition that stopped it. Half a minute is the wait a first failure gets, and a
 * stop is not worse news than a failure.
 *
 * ⭐ **The second argument of [rebook] is why the two paths are not the same booking.** The stop path
 * asks for `onlyIfNothingBooked = true`: WorkManager reschedules a worker it stopped ITSELF, and this
 * queue books under one unique name — so a plain booking would CANCEL the system's own retry and put
 * ours 30 s later in its place, delaying the very resumption it exists to guarantee. `true` means
 * "be the safety net, never the replacement". The ordinary-failure path asks for `false`: the row has
 * just been given a new backoff, and that instant must win over whatever was booked before it.
 *
 * ⭐ [now] is a CLOCK, not an instant, and it is read AFTER [upload] returns — never before. Read
 * before, the delay would carry the whole duration of the attempt on top of the backoff the row was
 * just given (up to [LOCAL_DRAFT_UPLOAD_BUDGET_MS] of it), so a draft would come back later than the
 * row itself says, and the slower the link the later it would be. ⛔ It is not read at all on the two
 * paths that do not need it.
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
 * Whether it is worth asking the server whether it already holds this draft (#95).
 *
 * ⛔ Only a row that has already FAILED at least once. On a first attempt the answer is known in
 * advance: the `Message-ID` was minted by the save this row came from ([localDraftMessageId]) and
 * has never been on a wire, so the round trip would be paid on every single save — on the very path
 * the user is waiting on — to be told "no" every time.
 *
 * The duplicate this guards against needs a failed attempt to exist at all: the `APPEND` leaves, the
 * link dies before the server's tagged answer, the client throws, [localDraftAttemptAfterFailure]
 * writes the attempt — and the copy is on the server with nothing on the phone knowing it.
 *
 * ⛔ **And only a row that HAS a name.** The whole comparison is between two `Message-ID`s, and a
 * row with none has nothing to compare: there is no answer the server could give that would mean
 * "this is your draft". [localDraftMessageIdMatches] refuses a blank on either side for that
 * reason, so this guard is the second lock on the same door — a nameless draft belongs on the
 * server, not in a lookup. Not reachable today (the column is `TEXT NOT NULL` and every save mints
 * a value), and it stays because the failure it would cause is the losing one:
 * [appendDraftUnlessAlreadyThere] would abstain and [uploadLocalDraftOnce] would consume the row.
 */
fun localDraftMayAlreadyBeOnServer(row: LocalDraftEntity): Boolean =
    row.attemptCount > 0 && row.messageId.isNotBlank()

/**
 * Whether [reported] — a `Message-ID` read back from a server's ENVELOPE — names the very draft
 * whose own id is [ours] (#95).
 *
 * The decision the de-duplication rests on, pure so it can be EXECUTED by a test instead of read.
 * The two values come from different places and are written differently: ours is the naked id the
 * save minted, and the server reports the header as it stores it, `<id>` brackets included (which
 * `OutgoingMime` wrote). So: trim, drop one enclosing pair of `<>`, trim again, and compare.
 *
 * ⛔ **Equality, never a substring.** `contains` would make `<b@x>` a match for `ab@x`, and the
 * damage is not a wrong screen: a false "it is already there" makes [appendDraftUnlessAlreadyThere]
 * abstain, [uploadLocalDraftOnce] consume the row, and the text exist NOWHERE. A duplicate in
 * Drafts is visible and deletable; that is not. Case is not folded either, for the same reason —
 * every doubt goes to the duplicate.
 *
 * ⛔ **A blank on EITHER side never matches**, however blank the other one is: a message the server
 * reports without a `Message-ID` (the field is `NIL`, so `reported` is null) must not be read as
 * our draft merely because it is as nameless as a row that has lost its own id.
 */
fun localDraftMessageIdMatches(ours: String, reported: String?): Boolean {
    val mine = unbracketedMessageId(ours)
    val theirs = unbracketedMessageId(reported.orEmpty())
    return mine.isNotEmpty() && theirs.isNotEmpty() && mine == theirs
}

/** `  <id@host>  ` → `id@host`. One enclosing pair only, and only when both ends carry it. */
private fun unbracketedMessageId(value: String): String =
    value.trim().removeSurrounding("<", ">").trim()

/**
 * Put [row] on the server unless a lookup says it is already there, and answer whether the bytes
 * actually went out (#95).
 *
 * [alreadyThere] is handed the row's own `Message-ID`, the value NAKED as the row stores it —
 * [localDraftMessageIdMatches] is what puts it next to what the server reports — and it MAY THROW:
 * it is a live server read over the same link that just failed.
 *
 * ⛔ **A lookup that throws appends.** Abstaining on a failed READ is the one outcome that loses
 * something: [uploadLocalDraftOnce] consumes the row the moment the upload returns, so a draft
 * skipped on a search nobody could run exists nowhere afterwards. A duplicate in Drafts is visible,
 * and the user can delete it; text that never arrived cannot be got back. A [CancellationException]
 * is not a failure — the caller is going away — so it is rethrown and nothing is appended.
 */
internal suspend fun appendDraftUnlessAlreadyThere(
    row: LocalDraftEntity,
    alreadyThere: suspend (messageId: String) -> Boolean,
    append: suspend () -> Unit,
): Boolean {
    if (localDraftMayAlreadyBeOnServer(row)) {
        val found = try {
            // ⭐ The row's frozen value, NAKED. The header the APPEND wrote reads `<id>`
            // (OutgoingMime adds the angle brackets), and the brackets are dealt with where the
            // two are compared — [localDraftMessageIdMatches] — not by shaping the value here.
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

/** One work item to book: which row, and how long from now. */
data class LocalDraftUploadJob(val accountId: String, val id: String, val delayMillis: Long)

/** How long from [nowMillis] until [notBeforeMillis] — never negative, so a due row goes at once. */
fun localDraftScheduleDelayMillis(notBeforeMillis: Long, nowMillis: Long): Long =
    (notBeforeMillis - nowMillis).coerceAtLeast(0)

/**
 * The work a startup has to book for one account's [rows] (#95).
 *
 * ⛔ **Every row gets one, backed off or not.** The rows arrive from
 * `MailRepository.localDraftsAwaitingUpload`, which asks the queue about every instant rather than
 * about now, precisely so a draft that failed ten minutes before the reboot is not left with no
 * work item at all — WorkManager's own persistence is what a reboot loses, and this is the
 * safety net for it, exactly as the outbox's re-arm is.
 */
fun localDraftUploadJobs(
    accountId: String,
    rows: List<LocalDraftEntity>,
    nowMillis: Long,
): List<LocalDraftUploadJob> = rows.map {
    LocalDraftUploadJob(accountId, it.id, localDraftScheduleDelayMillis(it.notBeforeMillis, nowMillis))
}

/**
 * The attachments of a deferred upload, read back off the ROW.
 *
 * There is no composer here: the worker wakes up days later with nothing but the row, so the parts
 * are rebuilt from the descriptors [stageLocalDraftAttachments] wrote — a staged file for IMAP, a
 * server blob for JMAP, in [OutboxAttachments]' encoding, exactly as `performSend` rebuilds an
 * outbox item's.
 *
 * ⛔ The count matters as much as the bytes: `uploadDraft` weighs `attachments.size` against the
 * parts it could actually build before it lets `finishDraftSave` destroy the original (#63). An
 * `emptyList()` here would upload every deferred draft amputated AND report the amputation as a
 * faithful replacement.
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
 * Take one account's local drafts off the phone — the rows AND the bytes staged with them (#121's
 * neighbourhood). Called on sign-out, and on nothing else: see `StorageRepository.purgeAccount`.
 *
 * ⛔ **The ids are read BEFORE the rows go.** The per-draft directories are named after the ids
 * ([localDraftDirName]) and nothing else names them: delete the rows first and the attachments of
 * the account just removed stay on the phone for good, with no row left to find them by.
 *
 * The file deletions are best-effort and one `runCatching` EACH, like the orphan sweep's per-table
 * deletes: a directory that cannot be removed must not keep the others.
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
