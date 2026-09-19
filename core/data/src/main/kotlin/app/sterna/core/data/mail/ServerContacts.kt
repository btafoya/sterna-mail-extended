package app.sterna.core.data.mail

import app.sterna.core.data.db.ServerContactDao
import app.sterna.core.data.db.ServerContactEntity
import app.sterna.core.jmap.model.ContactChanges
import app.sterna.core.jmap.model.ContactsSnapshot
import app.sterna.core.jmap.model.ServerContact

/**
 * What one pass did with an account's copy of the server address book, for the caller to log: from
 * the outside, a pass that left no copy looks the same as one that stored the book.
 */
sealed interface ServerContactsSync {
    data object Imap : ServerContactsSync

    /** A shared or delegated sub-account, which never gets its login's book. */
    data object Linked : ServerContactsSync

    /** The session names no contacts account. [capabilityListed] tells a server without the
     *  capability from one that lists it but ties it to no account. */
    data class NoAddressBook(val capabilityListed: Boolean) : ServerContactsSync

    /** [cards] read, and for a delta [destroyed] removed; [stateKept] false means the next pass
     *  rereads the whole book. */
    data class Refreshed(
        val whole: Boolean,
        val cards: Int,
        val destroyed: Int,
        val addresses: Int,
        val stateKept: Boolean,
    ) : ServerContactsSync
}

/**
 * Brings [accountId]'s copy of the server address book up to date: the whole book when no state is
 * stored, the delta since it otherwise. A null answer (no contacts capability) writes nothing and
 * returns null, and a read that throws leaves the copy as it was.
 */
internal suspend fun refreshServerContacts(
    accountId: String,
    dao: ServerContactDao,
    readAll: suspend () -> ContactsSnapshot?,
    readChanges: suspend (sinceState: String) -> ContactChanges?,
): ServerContactsSync.Refreshed? {
    val since = dao.state(accountId)
    if (since == null) {
        val book = readAll() ?: return null
        val bookRows = serverContactRows(accountId, book.contacts)
        dao.replaceAll(accountId, bookRows, book.state)
        return ServerContactsSync.Refreshed(
            whole = true,
            cards = book.contacts.size,
            destroyed = 0,
            addresses = bookRows.size,
            stateKept = book.state != null,
        )
    }
    val changes = readChanges(since) ?: return null
    val rows = serverContactRows(accountId, changes.changed)
    if (changes.replacesAll) {
        dao.replaceAll(accountId, rows, changes.newState)
    } else {
        dao.applyChanges(accountId, changes.changed.map { it.id }, changes.destroyed, rows, changes.newState)
    }
    return ServerContactsSync.Refreshed(
        whole = changes.replacesAll,
        cards = changes.changed.size,
        destroyed = changes.destroyed.size,
        addresses = rows.size,
        stateKept = changes.newState != null,
    )
}

internal fun serverContactRows(accountId: String, contacts: List<ServerContact>): List<ServerContactEntity> =
    contacts.flatMap { card ->
        card.emails.distinct().map { ServerContactEntity(accountId, card.id, card.name, it) }
    }
