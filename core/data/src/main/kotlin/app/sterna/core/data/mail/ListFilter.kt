package app.sterna.core.data.mail

/** Which rows a message list keeps within its folders. Each criterion set narrows the list, and they
 *  combine with AND. Applied in local SQL only: the server is never asked for a filtered page. */
data class ListFilter(
    val unreadOnly: Boolean = false,
    val flaggedOnly: Boolean = false,
    val withAttachment: Boolean = false,
) {
    companion object {
        val NONE = ListFilter()
    }
}
