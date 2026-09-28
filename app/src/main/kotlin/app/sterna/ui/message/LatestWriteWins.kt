package app.sterna.ui.message

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Writes for the same key end on the value of the last [submit], whatever order the launched writes
 * are dispatched in: the wanted value is recorded before the launch, and each write sends the value
 * wanted when it takes the lock, not the one it was launched with. Writes are serialised, all keys
 * together, so a write may run long after [submit]: it must capture at the call what it would read
 * from the caller, such as the account of the page shown.
 */
open class LatestWriteWins<K : Any, V : Any> {
    private val mutex = Mutex()
    private val wanted = HashMap<K, V>()

    fun submit(scope: CoroutineScope, key: K, value: V, write: suspend (V) -> Unit): Job {
        synchronized(wanted) { wanted[key] = value }
        return scope.launch {
            mutex.withLock {
                val latest = synchronized(wanted) { wanted[key] } ?: return@withLock
                try {
                    write(latest)
                } finally {
                    synchronized(wanted) { if (wanted[key] == latest) wanted.remove(key) }
                }
            }
        }
    }
}

/** Held by the process: a reader view model survives neither a rotation nor a reopening, and its writes may still be in flight. */
internal object ReaderStarWrites : LatestWriteWins<Pair<String, String>, Boolean>()
