package app.sterna.core.data.mail

import app.sterna.core.data.db.EmailEntity
import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.CancellationException

/** What the server answered for one IMAP id the cache does not hold (#159, #255). */
sealed interface ImapLookup {
    data class Found(val entity: EmailEntity) : ImapLookup

    /** SELECT stated the very numbering recorded for the folder, and `UID FETCH` returned nothing.
     *  The one answer that sets `MessageUnavailableException.serverHasNoSuchMessage`, which licenses
     *  forgetting the id: a failure (offline, timeout, refused SELECT) never does. */
    data object Gone : ImapLookup

    /** Nothing came back, but nothing vouches that the id's UID belongs to the folder's current
     *  numbering, or the id was not one this connection may ask about. */
    data object Unproven : ImapLookup
}

/**
 * The envelope `openEmailImap` shows: [cached], else what [lookUp] brings back. On
 * [ImapLookup.Gone] the id is handed to [forget] before raising, the open being the only proof a
 * search hit touched before the server search removed it gets (#255).
 */
internal suspend fun imapEnvelope(
    accountId: String,
    emailId: String,
    cached: Email?,
    lookUp: suspend () -> ImapLookup,
    forget: suspend (accountId: String, emailId: String) -> Unit,
): Email = try {
    envelopeOf(emailId, cached, lookUp)
} catch (unavailable: MessageUnavailableException) {
    if (unavailable.serverHasNoSuchMessage) {
        try {
            forget(accountId, emailId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // The reader is owed the unavailable message, not a database error; the row left
            // behind is dropped by the next server search that contradicts it.
        }
    }
    throw unavailable
}

private suspend fun envelopeOf(emailId: String, cached: Email?, lookUp: suspend () -> ImapLookup): Email {
    if (cached != null) return cached
    val lookup = try {
        lookUp()
    } catch (cancelled: CancellationException) {
        // Rethrown as-is, which is why this is a `try` and not a `runCatching`:
        // AccountGoneException IS a CancellationException, and must keep its job upstream.
        throw cancelled
    } catch (failure: Throwable) {
        throw MessageUnavailableException("Could not fetch $emailId from the server.", failure)
    }
    if (lookup is ImapLookup.Found) return lookup.entity.toEmail()
    throw MessageUnavailableException(
        "Message $emailId is not in the cache and the server did not return it.",
        serverHasNoSuchMessage = lookup == ImapLookup.Gone,
    )
}
