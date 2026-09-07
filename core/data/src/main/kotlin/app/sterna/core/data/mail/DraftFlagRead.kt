package app.sterna.core.data.mail

import app.sterna.core.data.account.MailProtocol
import kotlin.coroutines.cancellation.CancellationException

/**
 * Whether the server itself flags a reopened message as a draft, the fifth leg of
 * [reopenedDraftIsLossy]. Every unknown answers false, the side that spares a received mail.
 */
internal suspend fun draftFlagIsCarried(
    protocol: MailProtocol,
    jmapFlag: Boolean,
    imapFlag: suspend () -> Boolean?,
): Boolean {
    if (protocol != MailProtocol.IMAP) return jmapFlag
    return try {
        imapFlag() ?: false
    } catch (cancelled: CancellationException) {
        // Rethrown: AccountGoneException is one, and swallowing it strands the job it cancelled.
        throw cancelled
    } catch (failure: Throwable) {
        // The draft itself was read; only its flag was not, so no notice is owed to the screen.
        false
    }
}
