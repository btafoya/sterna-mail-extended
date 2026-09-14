package app.sterna.core.data.filter

import kotlinx.serialization.Serializable

/**
 * An auto-reply kept in the `sterna` script, named as JMAP `VacationResponse` names it. [addresses]
 * live here, not in the account's identities, so the `vacation` block regenerates from the script
 * alone: read from identities, a change of alias would make our own script look hand-edited.
 */
@Serializable
data class SieveVacation(
    val isEnabled: Boolean = false,
    val fromDate: String? = null,
    val toDate: String? = null,
    val subject: String? = null,
    val textBody: String? = null,
    val addresses: List<String> = emptyList(),
    /**
     * The account's Trash path as `trashFilePath` named it when the auto-reply was saved. Mail an
     * active rule files there gets no reply; null means unknown, and nobody is excluded. Kept here
     * for the same reason as [addresses].
     */
    val trashFolder: String? = null,
)

sealed interface VacationMarker {
    data object Absent : VacationMarker
    data class Found(val vacation: SieveVacation) : VacationMarker

    /** Never folded into [Absent]: a save would then rewrite the script without its auto-reply. */
    data object Unreadable : VacationMarker
}
