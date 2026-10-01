package app.sterna.push

import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.data.account.accountAddresses
import app.sterna.core.jmap.model.Email
import app.sterna.ui.showsRecipients
import app.sterna.ui.compose.receivingAddress
import app.sterna.ui.inbox.isSelfAuthored
import app.sterna.ui.message.deliveredToInHeader

/**
 * The address a new-mail notification names in place of the account (#243): the reader's arrival
 * line (#180), composed from the same three pieces so the banner never names an address the open
 * message would not. [login] is searched by [receivingAddress] as the reader searches it, but never
 * handed to [deliveredToInHeader]: the login is not an identity, and counting it would name the one
 * address of a single-address account on every banner. Whether the message is one's own is the
 * reader's [showsRecipients], so a message written from one address to another, landing in the
 * inbox, still names where it arrived. [folderName] is the poster's: null for the inbox.
 */
internal fun arrivalAddress(email: Email, identities: List<StoredIdentity>, login: String?, folderName: String?): String? {
    val received = receivingAddress(email, accountAddresses(identities, login))
    // A watched folder outside the inbox is never Sent nor Drafts, so a null role there decides alike.
    val role = if (folderName == null) "inbox" else null
    val ownMessage = showsRecipients(role, unified = false, selfAuthored = isSelfAuthored(email.from, identities))
    return deliveredToInHeader(received, identities.map { it.email }, ownMessage)
}
