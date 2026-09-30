package app.sterna.contacts

/**
 * One row of the recipient autocomplete menu: an address, the name to show above it, and, only
 */
data class ContactSuggestion(
    val email: String,
    val name: String?,
    /** Contact thumbnail URI, or null when the row has no photo (then a monogram is drawn). */
    val photoUri: String? = null,
)
