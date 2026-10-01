package app.sterna.ui.compose

import androidx.compose.runtime.saveable.Saver
import app.sterna.contacts.ContactSuggestion
import app.sterna.util.isValidEmail

/*
 * The composer's name book: display names of the addresses picked from the suggestion menu. The name
 * is shown, the address is sent. The recipient strings stay the only recipients there are; the book
 * is read by a chip's label and its accessibility only, never by what sends, saves or checks an
 * address, so no name can travel that the user did not pick.
 */

/** The book's key for [address]: the same mailbox whatever its case or surrounding spaces. */
internal fun recipientNameKey(address: String): String = address.trim().lowercase()

/**
 * What a chip shows. An invalid chip shows what was typed even if the book holds it: that text is
 * what has to be fixed, and a name over it would hide the typo.
 */
internal fun chipLabel(chip: String, names: Map<String, String>): String =
    if (isValidEmail(chip)) names[recipientNameKey(chip)] ?: chip else chip

/** TalkBack's reading of a named chip, name then address; null when the chip shows its address anyway. */
internal fun chipDescription(chip: String, names: Map<String, String>): String? {
    val shown = chipLabel(chip, names)
    return if (shown == chip) null else "$shown, ${chip.trim()}"
}

/** A pick without a name writes nothing, so an earlier name for the same address is kept. */
internal fun recipientNamesWithPick(names: Map<String, String>, contact: ContactSuggestion): Map<String, String> {
    val name = contact.name?.trim().orEmpty()
    return if (name.isEmpty()) names else names + (recipientNameKey(contact.email) to name)
}

/** A `Map` is not bundlable: saved as a flat key, name, key, name list. */
internal val recipientNamesSaver: Saver<Map<String, String>, ArrayList<String>> = Saver(
    save = { names -> names.flatMapTo(ArrayList()) { (key, name) -> listOf(key, name) } },
    restore = { flat -> flat.chunked(2).associate { (key, name) -> key to name } },
)
