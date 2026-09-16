package app.sterna.ui.message

import kotlinx.coroutines.CancellationException

/**
 * What may be done about the banner of a message the reader just settled on, decided on nothing but
 * what the write of the read mark handed back. Its own type and not a boolean: "leave the banner
 * alone" has two reasons, and only one of them is a failure worth writing down.
 */
enum class ReadMarkCleanup {
    /** The write returned. That is the whole of the evidence, and it is weaker than it reads: a
     *  returned write does not prove the mark reached the cache, and the pruning of a row the
     *  server no longer has happens on JMAP alone, IMAP having no equivalent. The banner goes
     *  because the write was not refused, not because anything was proved persisted. */
    DISMISS,

    /** The write threw: the cache is written after the round trip, so a refused or unreachable server
     * leaves nothing behind anywhere. The banner left standing is what tells the reader, and it is the
     * only surviving trace that this mail arrived: the next sync brings its unread dot back with no
     * banner under it. The log line beside it is for diagnosis and nothing else. No sentence on
     * screen: this is decided on a settle and not on a gesture, so a flight read through would raise
     *  one refusal per message, none of them asked for. */
    KEEP_AND_LOG,

    /** The write was cancelled. Nothing was written either, so the banner stays for the same reason,
     * but a cancellation is not a failure: the throwable belongs back in the machinery that raised
     * it, not in a log. Not folded into [KEEP_AND_LOG], whose lines would then be a record of pages
     *  closed rather than of marks refused, and useless for the one thing that log is read for.
     *
     *  What makes the caller's rethrow safe rather than a crash: only a `CancellationException`
     *  can reach this branch, and kotlinx short-circuits one before `handleJobException`, so it
     *  never meets the uncaught-exception handler. Widen this branch to any other throwable and
     *  the rethrow on a scope with no `CoroutineExceptionHandler` takes the process down. */
    KEEP_AND_RETHROW,
}

/**
 * The decision above, taken on the [write] of the read mark: `setRead` returns or it throws, and
 * nothing finer is on offer because `EmailDao.setSeen` returns `Unit`. Room hands back the count
 * of rows a `@Query` UPDATE touched as soon as one is declared to return `Int`, so this is a
 * choice and not a limit.
 */
fun readMarkCleanup(write: Result<Unit>): ReadMarkCleanup = when (write.exceptionOrNull()) {
    null -> ReadMarkCleanup.DISMISS
    is CancellationException -> ReadMarkCleanup.KEEP_AND_RETHROW
    else -> ReadMarkCleanup.KEEP_AND_LOG
}
