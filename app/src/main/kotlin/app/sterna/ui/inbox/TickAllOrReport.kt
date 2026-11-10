package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import kotlinx.coroutines.CancellationException

/**
 * One "Select all": [read] the keys, then [tick] them. The app has no `CoroutineExceptionHandler`,
 * so a read that throws out of `viewModelScope.launch` kills the process; a failed read is handed
 * to [report] instead and the ticks are left as they were. Producer: before Android 12, the read
 * of a few hundred folders under the unread view passes SQLite's 999 bound variables.
 * A `CancellationException` is rethrown: it is the scope ending, not a failed read.
 */
internal suspend fun tickAllOrReport(
    read: suspend () -> Set<EmailKey>,
    tick: (Set<EmailKey>) -> Unit,
    report: (Throwable) -> Unit,
) {
    val all = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        report(t)
        return
    }
    tick(all)
}
