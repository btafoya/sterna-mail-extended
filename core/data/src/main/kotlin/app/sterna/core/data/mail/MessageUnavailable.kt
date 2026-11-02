package app.sterna.core.data.mail

import java.net.SocketTimeoutException

/** A message could not be brought back from the server: offline, destroyed server-side, or its
 *  folder renumbered (#159). The wording stays technical and English: a bug report quotes it. */
class MessageUnavailableException(
    message: String,
    cause: Throwable? = null,
    val serverHasNoSuchMessage: Boolean = false,
) : Exception(message, cause)

internal suspend fun <T> unavailableOnTimeout(emailId: String, read: suspend () -> T): T = try {
    read()
} catch (expired: SocketTimeoutException) {
    throw MessageUnavailableException("Source of $emailId did not arrive in time; the server went silent.", expired)
}
