package app.sterna.ui.compose

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * One file of a pick that could not be attached. [online] is read when that file failed, not when
 * the pick ends: the connection may have come back meanwhile, and `composedAttachFailure` would then
 * call a transport failure a plain one.
 */
internal data class AttachFailure(val error: Throwable, val online: Boolean)

/** What the status line says once a multi-file pick has run; `null` from [attachAllWord] means nothing. */
internal sealed interface AttachAllWord {
    /** One file of the pick failed: it keeps the sentence a single pick would have had. */
    data class OneFailed(val failure: AttachFailure) : AttachAllWord

    /** Several failed: a count, because one file's reason would be wrongly read as every file's. */
    data class SomeFailed(val failures: List<AttachFailure>, val total: Int) : AttachAllWord
}

internal fun attachAllWord(failures: List<AttachFailure>, total: Int): AttachAllWord? = when (failures.size) {
    0 -> null
    1 -> AttachAllWord.OneFailed(failures.single())
    else -> AttachAllWord.SomeFailed(failures, total)
}

/**
 * Attaches [items] one after the other, in their order, inside the caller's single coroutine.
 *
 * Not one coroutine per file: the smallest would land first and the rows would not follow the pick,
 * every file's bytes would sit in memory together, and the single status line would be cleared by
 * whichever finished first, a late success erasing an earlier failure.
 *
 * A failing item does not stop the next ones; a [CancellationException] does, and is never counted
 * as a failure. [online] is asked at each failure. [status] shows [attaching] from the first item to
 * the last; then, if anything failed, the word [say] renders. An empty list touches nothing.
 */
internal suspend fun <T> attachInSequence(
    items: List<T>,
    status: MutableStateFlow<String?>,
    attaching: String,
    online: () -> Boolean,
    attachOne: suspend (T) -> Unit,
    say: (AttachAllWord) -> String,
) {
    if (items.isEmpty()) return
    status.value = attaching
    try {
        val failures = mutableListOf<AttachFailure>()
        for (item in items) {
            try {
                attachOne(item)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                failures += AttachFailure(t, online())
            }
        }
        attachAllWord(failures, items.size)?.let { status.value = say(it) }
    } finally {
        // Cleared only while it still says "Attaching…", whether the pick ended or was cancelled: a
        // line another path wrote meanwhile (a photo that failed, vanished files) is left standing.
        if (status.value == attaching) status.value = null
    }
}
