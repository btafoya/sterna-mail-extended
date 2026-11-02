package app.sterna.push

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Per key, at most one pass running and one waiting. Any number of requests landing while a pass
 * runs buy exactly one more pass after it: that pass reads the mailbox as it stands then, so it
 * answers every one of them. The pass that waits is the latest one requested.
 *
 * A pass that throws or is cancelled frees its key and drops the one waiting behind it.
 *
 * `PushService` keys it by login: our own deletes and moves wake the IDLE too, and each wake would
 * otherwise start a pass racing the others on the same notification baseline (#278).
 */
class OnePassAtATime(private val scope: CoroutineScope) {
    private val lock = Any()
    private val running = HashSet<String>()
    private val waiting = HashMap<String, suspend () -> Unit>()

    fun request(key: String, pass: suspend () -> Unit) {
        synchronized(lock) {
            if (key in running) {
                waiting[key] = pass
                return
            }
            running += key
        }
        val job = scope.launch {
            var next: (suspend () -> Unit)? = pass
            while (next != null) {
                next()
                next = takeWaiting(key)
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) synchronized(lock) {
                running -= key
                waiting -= key
            }
        }
    }

    /** Null also frees [key], in the same critical section, so no request falls between the two. */
    private fun takeWaiting(key: String): (suspend () -> Unit)? = synchronized(lock) {
        waiting.remove(key) ?: run {
            running -= key
            null
        }
    }
}
