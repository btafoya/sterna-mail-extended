package app.sterna.ui.message

import kotlinx.coroutines.CancellationException

/**
 * What may be done about the banner of a message the reader just settled on, decided on nothing but
 * what the write of the read mark handed back. Its own type and not a boolean: "leave the banner
 * alone" has two reasons, and only one of them is a failure worth writing down.
 */
enum class ReadMarkCleanup {
    /** The write returned, so either the mark is in the cache, or `setRead` found no such message on
     *  the server and pruned the row. In both cases the banner names mail that is read or gone. */
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
     *  closed rather than of marks refused, and useless for the one thing that log is read for. */
    KEEP_AND_RETHROW,
}

/**
 * The decision above, taken on the [write] of the read mark: `setRead` returns or it throws, which is
 * the whole of what can be known about whether anything was persisted.
 */
fun readMarkCleanup(write: Result<Unit>): ReadMarkCleanup = when (write.exceptionOrNull()) {
    null -> ReadMarkCleanup.DISMISS
    is CancellationException -> ReadMarkCleanup.KEEP_AND_RETHROW
    else -> ReadMarkCleanup.KEEP_AND_LOG
}
