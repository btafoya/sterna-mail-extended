package app.sterna.ui.inbox

/** What a swipe's server write left behind, once the finger has taken the row off the list. */
internal enum class SwipeWrite {
    /** The message moved, to the folder the mover named. */
    LANDED,

    /** The write moved nothing: the message is gone server-side (the mover pruned its row), or it
     *  was already in the folder asked for. */
    NOTHING_MOVED,

    /** The server refused the write, most often because there was no network to carry it. */
    REFUSED,
    ;

    /** Does the row the finger took off the list go back where it was? Everything but a landing:
     *  a write that moved nothing left the message where the reader saw it, folder counters
     *  included. The one message that really is gone, the one the server answered `notFound` for,
     *  is no longer held by then, so asking for it back puts nothing back. */
    val putsRowBack: Boolean get() = this != LANDED

    /** Is there anything to undo? Only a move that landed, never a gesture that did not happen. */
    val offersUndo: Boolean get() = this == LANDED
}

/** Read [SwipeWrite] off what the mover returned: a destination, `null` for a move that moved
 *  nothing at all, or a throwable. */
internal fun swipeWrite(written: Result<String?>): SwipeWrite = when {
    written.isFailure -> SwipeWrite.REFUSED
    written.getOrNull() == null -> SwipeWrite.NOTHING_MOVED
    else -> SwipeWrite.LANDED
}
