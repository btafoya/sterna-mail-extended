package app.sterna.push

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Carry out [act] with the row held in flight while the server write is out, so that no sync page
 * read before the move writes it back. Null from [begin] is nothing held: the write still goes.
 */
suspend fun <F : Any> deleteHoldingItsRow(
    act: BannerDeleteAct,
    begin: suspend () -> F?,
    delete: suspend () -> Unit,
    end: suspend (flight: F, restore: Boolean) -> Unit,
): Boolean {
    if (act == BannerDeleteAct.DoNothing) return false
    val flight = begin()
    var landed = false
    try {
        delete()
        landed = true
    } finally {
        // A flight left open keeps its key in the repository for the life of the process.
        withContext(NonCancellable) { flight?.let { end(it, !landed) } }
    }
    return true
}
