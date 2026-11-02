package app.sterna.ui.components

import app.sterna.core.jmap.model.EmailAddress
import app.sterna.ui.compose.recipientNameKey

/**
 * The address a list row may show after the sender's name: only for a sender who has both a name
 * and an address, since a nameless sender's address is already what the name slot shows.
 * A name equal to the address (trimmed, any case) counts as no name; one merely containing it does not.
 * A row of a sending folder shows "To: …" or the sender who is yourself, never an added address.
 */
internal fun listAddressBesideName(sender: EmailAddress?, rowShowsRecipients: Boolean): String? =
    if (rowShowsRecipients) null else sender?.email?.takeIf {
        val name = sender.name
        it.isNotBlank() && !name.isNullOrBlank() && !name.trim().equals(it.trim(), ignoreCase = true)
    }

/**
 * Whether the sender's address goes on the line: only when the name, the [gapPx] after it and the
 * address all fit whole in the [availablePx] the name slot is given. Otherwise the address is left
 * out entire, never cut, and the name keeps the whole slot.
 */
internal fun senderLineShowsAddress(availablePx: Int, nameWidthPx: Int, addressWidthPx: Int, gapPx: Int): Boolean =
    nameWidthPx.toLong() + gapPx + addressWidthPx <= availablePx

/**
 * The sender as the user saved them in the phone's contacts (#280): the book's name over the
 * `From:` name, the address kept as sent. Not in the book, the very same object. Everything the
 * row or header derives from a sender reads this result, so [listAddressBesideName] judges the
 * name actually shown.
 */
internal fun withAddressBookName(sender: EmailAddress?, names: Map<String, String>): EmailAddress? {
    if (sender == null || names.isEmpty()) return sender
    val saved = names[recipientNameKey(sender.email)] ?: return sender
    return sender.copy(name = saved)
}
