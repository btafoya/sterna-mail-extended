package app.sterna.core.data

import kotlinx.coroutines.CancellationException

/**
 * [Result.getOrElse] for suspending work: `runCatching` treats [CancellationException] as an
 * ordinary failure, so a cancelled coroutine can still run a stale caller's failure branch
 * (cache fallback, teardown, error banner). This rethrows the cancellation instead (#99).
 *
 * Public rather than `internal`: call sites span `core:data` and `app`, different Gradle modules.
 * Inline with a non-crossinline lambda so a call site can still `return@launch`; same
 * `<R, T : R>` shape as [Result.getOrElse].
 */
inline fun <R, T : R> Result<T>.getOrElseUnlessCancelled(onFailure: (Throwable) -> R): R {
    val error = exceptionOrNull() ?: return getOrThrow()
    if (error is CancellationException) throw error
    return onFailure(error)
}

/**
 * Same rule when the [Result] itself is the value, not unwrapped: [getOrElseUnlessCancelled]
 * does not apply once a cancellation is stored in a batch and read elsewhere as an ordinary
 * failure (#129), so re-throw it here before it can look like data.
 */
fun <T> Result<T>.rethrowIfCancelled(): Result<T> {
    val error = exceptionOrNull()
    if (error is CancellationException) throw error
    return this
}
