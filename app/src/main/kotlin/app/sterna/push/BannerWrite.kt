package app.sterna.push

import kotlin.coroutines.cancellation.CancellationException

/** The server write a banner's Mark as read or Delete hands to [BannerWriteWorker]. [action] is the
 *  broadcast action it came from, the name the device log gives it. */
enum class BannerWrite(val action: String, val key: String) {
    MARK_READ(NotificationActionReceiver.ACTION_MARK_READ, "read"),
    DELETE(NotificationActionReceiver.ACTION_DELETE, "delete"),
    ;

    companion object {
        fun ofAction(action: String?): BannerWrite? = entries.firstOrNull { it.action == action }
        fun ofKey(key: String?): BannerWrite? = entries.firstOrNull { it.key == key }
    }
}

/** What the receiver does with a tapped Mark as read or Delete, see [bannerTap]. */
sealed interface BannerTap {
    /** The banner stays and nothing is queued: it is still the user's way to try again. */
    data object KeepBanner : BannerTap

    /** The banner comes down now and [write] is queued, to be carried out off the broadcast. */
    data class HandOver(val write: BannerWrite) : BannerTap
}

/**
 * Decided from local state only. A signed-out account keeps its banner: nothing could carry the
 * write. A Delete that would move nothing ([BannerDeleteAct.DoNothing], #171) keeps it too, and so
 * does a Delete whose decision was never read (null).
 */
fun bannerTap(write: BannerWrite?, signedIn: Boolean, deleteAct: BannerDeleteAct?): BannerTap = when {
    write == null || !signedIn -> BannerTap.KeepBanner
    write == BannerWrite.DELETE && deleteAct != BannerDeleteAct.MoveToTrash -> BannerTap.KeepBanner
    else -> BannerTap.HandOver(write)
}

/**
 * The banner falls before anything is queued, so that nothing the queueing waits on can hold it
 * up. The queueing is in a `finally`, so a dismissal that throws does not lose the write: thrown
 * before its cancel, it leaves the banner up beside a write that goes all the same.
 */
suspend fun carryOutBannerTap(
    tap: BannerTap,
    dismiss: suspend () -> Unit,
    enqueue: suspend (BannerWrite) -> Unit,
) {
    if (tap !is BannerTap.HandOver) return
    try {
        dismiss()
    } finally {
        enqueue(tap.write)
    }
}

/** How many runs of [BannerWriteWorker] a write gets before its banner is put back. */
const val BANNER_WRITE_ATTEMPTS = 3

enum class BannerWriteOutcome { Done, Retry, GaveUp }

/**
 * One run of a queued banner write. [attempt] is WorkManager's `runAttemptCount`, 0 on the first
 * run and raised by WorkManager at every start, a start after it stopped the run included. A
 * failure on a run numbered [maxAttempts] - 1 or more gives up. A cancellation is WorkManager
 * stopping the run (network lost, quota): it is rethrown for WorkManager to run it again, neither
 * logged as a failure nor given up on. [giveUp] is the banner coming back.
 */
suspend fun attemptBannerWrite(
    attempt: Int,
    write: suspend () -> Unit,
    failed: (Throwable) -> Unit,
    giveUp: suspend () -> Unit,
    maxAttempts: Int = BANNER_WRITE_ATTEMPTS,
): BannerWriteOutcome {
    try {
        write()
        return BannerWriteOutcome.Done
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        failed(e)
    }
    if (attempt < maxAttempts - 1) return BannerWriteOutcome.Retry
    giveUp()
    return BannerWriteOutcome.GaveUp
}

/** A queued write that will not be carried out, and what becomes of its banner. */
enum class BannerUnwritten {
    /** Nothing to give back: the write has nothing left to act on, or is already done. */
    Drop,

    /** Silently, as after the last failed attempt: the message stays where it was. */
    GiveBannerBack,
}

/**
 * A queued write that found no credentials. An account gone from the store has no message left to
 * hold a banner for. An account still there whose secret did not read keeps its message in place,
 * and no retry makes that secret readable.
 */
fun bannerUnwrittenWithoutCredentials(accountKnown: Boolean): BannerUnwritten =
    if (accountKnown) BannerUnwritten.GiveBannerBack else BannerUnwritten.Drop

/** The account is part of the name, as in `Snoozes.workName`: email ids collide between accounts of
 *  one server (#31). The write is too, so a Mark as read queued first cannot keep out a Delete. */
fun bannerWriteWorkName(accountId: String, emailId: String, write: BannerWrite): String =
    "banner-${write.key}-$accountId-$emailId"
