package app.sterna.core.data.mail

/**
 * What [MailRepository.backfillUnread] reads back from one page of the server's unread ids: [absent]
 * has no cached row and is inserted, [misfiled] maps an id to the folder its cached row sits under,
 * to be filed back under a folder of the scope.
 */
internal data class UnreadBackfillPlan(val absent: List<String>, val misfiled: Map<String, String>)

/**
 * Sorts [pageIds], one page of the unread mail the server files in the scope, by what the cache holds
 * ([cachedFolders], id to folder).
 *
 * A row has one folder, the one the last folder sync to list the message wrote: a message the server
 * files in two folders is cached under whichever was synced last, and outside [inScope] the views
 * reading the scope lose it. Only that row is [UnreadBackfillPlan.misfiled]. One cached under a
 * folder of [inScope] is listed already and stays put, even where another folder would rank first.
 *
 * [heldLocally] names an id a local gesture has just written or is still moving. The server may not
 * have caught up with it, so neither list takes it: filing a row the user has just sent to the Trash
 * back under the folder the server still reports would undo the gesture on screen.
 */
internal fun unreadBackfillPlan(
    pageIds: List<String>,
    cachedFolders: Map<String, String>,
    inScope: Set<String>,
    heldLocally: (String) -> Boolean,
): UnreadBackfillPlan {
    val free = pageIds.filterNot(heldLocally)
    return UnreadBackfillPlan(
        absent = free.filterNot { it in cachedFolders },
        misfiled = free.mapNotNull { id -> cachedFolders[id]?.takeIf { it !in inScope }?.let { id to it } }.toMap(),
    )
}
