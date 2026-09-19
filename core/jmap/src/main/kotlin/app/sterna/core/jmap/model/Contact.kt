package app.sterna.core.jmap.model

import kotlinx.serialization.Serializable

    /**
     * One card of the server's address book (RFC 9610), reduced to what address completion uses. It
     * is read from `JmapSession.contactsAccountId`, which never falls back on the mail account: a mail
     * account asked for `ContactCard` answers `accountNotSupportedByMethod`.
     */
data class ServerContact(
    val id: String,
    val name: String,
    val emails: List<String>,
)

    /**
     * The whole address book of the contacts account, and the `ContactCard` state to resume from: the
     * state of the first page, so `ContactCard/changes` replays what changed during the walk. The walk
     * pages by `position`, so a card destroyed meanwhile hides the one at the page boundary.
     */
data class ContactsSnapshot(
    val contacts: List<ServerContact>,
    val state: String?,
)

    /**
     * What changed in the address book since a known state.
     *
     * When [replacesAll] is true the server could not compute a delta (`cannotCalculateChanges`),
     * [changed] is then the whole address book and [destroyed] is empty: the caller drops every card
     * it holds that is not in [changed]. A server that repeats a state with `hasMoreChanges` ends
     * the walk there rather than looping on it.
     *
     * [destroyed] also holds a card announced created or updated that `ContactCard/get` then answers
     * `notFound`, so its old rows go. A card created then destroyed within the same delta is only
     * destroyed, never read.
     */
data class ContactChanges(
    val changed: List<ServerContact>,
    val destroyed: List<String>,
    val newState: String?,
    val replacesAll: Boolean,
)

@Serializable
internal data class ContactCardDto(
    val id: String,
    val name: ContactNameDto? = null,
    val emails: Map<String, ContactEmailDto>? = null,
) {
    fun toServerContact() = ServerContact(
        id = id,
        name = contactDisplayName(name),
        emails = emails.orEmpty().values.map { it.address.trim() }.filter { it.isNotEmpty() },
    )
}

@Serializable
internal data class ContactNameDto(
    val full: String? = null,
    val components: List<ContactNameComponentDto>? = null,
)

@Serializable
internal data class ContactNameComponentDto(
    val kind: String = "",
    val value: String = "",
)

@Serializable
internal data class ContactEmailDto(
    val address: String = "",
)

    /** `full` when the card has one, else its given names then its surnames, else "". */
internal fun contactDisplayName(name: ContactNameDto?): String {
    val full = name?.full?.trim().orEmpty()
    if (full.isNotEmpty()) return full
    val components = name?.components.orEmpty()
    fun values(kind: String) = components.filter { it.kind == kind }.map { it.value.trim() }
    return (values("given") + values("surname")).filter { it.isNotEmpty() }.joinToString(" ")
}
