package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job

/**
 * The messages an Undo is still moving back, and the one place a gesture asks about them.
 *
 * Undo puts its rows back on screen at the tap and sends the restore after it. Until that restore
 * returns, a row is drawn and tickable, absent from the cache, and still in the bin at the server.
 * A gesture resolving its targets then finds no cached row and falls back to the drawn one, and on
 * JMAP its write and the restore's both succeed: the last to arrive decides where the message is,
 * and nothing reports a failure. So a gesture that writes a message's folder calls [settled] before
 * it reads the cache, never after. Marking read or flagged moves nothing and does not ask.
 *
 * A key is held by the job that restores it and for exactly as long as that job lives: a restore
 * that failed or was cancelled with its scope is over too, and the gesture then goes on whatever
 * the server holds. Nothing here can wait on a job that has ended.
 *
 * No cycle with [UndoAction.flight], which a restore joins before it sends: every gesture settles
 * before it makes its offer, so a gesture whose offer is being undone is already past its wait.
 */
internal class RestoresInFlight {

    private val restoring = ConcurrentHashMap<EmailKey, Job>()

    /** [restore] is the job sending these [keys] back. Safe to call with a job already over: the
     *  release runs at once, which is why the keys are registered before it is asked for. */
    fun hold(keys: Collection<EmailKey>, restore: Job) {
        val held = keys.toList()
        held.forEach { restoring[it] = restore }
        // By identity: a later restore of the same key must not be released by an earlier one ending.
        restore.invokeOnCompletion { held.forEach { restoring.remove(it, restore) } }
    }

    /** Returns once no restore is out for any of [keys], at once when none is. */
    suspend fun settled(keys: Collection<EmailKey>) {
        keys.mapNotNullTo(mutableSetOf()) { restoring[it] }.forEach { it.join() }
    }
}
