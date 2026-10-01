package app.sterna.core.data.account

/** The writes and the move behind the user's folder order, kept out of [AccountStore] for a JVM
 *  test. Stored on the account, never in `MailboxEntity.sortOrder`: every sync rewrites that. */

/** [accounts] with [id]'s folder order re-keyed in place after a rename ([oldId] and its descendants
 *  moved onto [newId]). Descendant test is the full prefix plus the delimiter, never bare, or
 *  `Travailleur` would be dragged along with `Travail`. */
internal fun withRenamedFolderOrder(
    accounts: List<StoredAccount>,
    id: String,
    oldId: String,
    newId: String,
    delimiter: String,
): List<StoredAccount> =
    accounts.map { account ->
        if (account.id == id) {
            account.copy(
                folderOrder = account.folderOrder.map { folder ->
                    when {
                        folder == oldId -> newId
                        folder.startsWith(oldId + delimiter) -> newId + folder.removePrefix(oldId)
                        else -> folder
                    }
                }.distinct(),
            )
        } else {
            account
        }
    }

/** [accounts] with [folderIds] dropped from [id]'s folder order, the ids a delete has just destroyed.
 *  A key left behind names a path a new folder can be created at, and it would inherit the place. */
internal fun withoutFolderOrder(
    accounts: List<StoredAccount>,
    id: String,
    folderIds: Collection<String>,
): List<StoredAccount> =
    accounts.map {
        if (it.id == id) it.copy(folderOrder = it.folderOrder - folderIds.toSet()) else it
    }

/** [siblings] (one parent's children, in listing order) as the user ordered them: ids in [order]
 *  first, by their rank there, then the others in the order they came, since `sortedBy` is stable. */
fun siblingsInFolderOrder(order: List<String>, siblings: List<String>): List<String> {
    val rank = order.withIndex().associate { (index, folder) -> folder to index }
    return siblings.sortedBy { rank[it] ?: Int.MAX_VALUE }
}

/**
 * The folder order after [folderId] moves one place up or down among [siblings], or [order] itself
 * when it cannot move (first going up, last going down, not among the siblings).
 *
 * The whole fratrie is written back, not the moved id alone: a sibling only the listing placed has
 * no rank, so it could not be told to stay above or below the one that moved. Other parents' ids
 * keep their entries; ranks are only ever compared within one parent.
 */
fun folderOrderAfterMove(order: List<String>, siblings: List<String>, folderId: String, up: Boolean): List<String> {
    val shown = siblingsInFolderOrder(order, siblings)
    val from = shown.indexOf(folderId)
    val to = if (up) from - 1 else from + 1
    if (from < 0 || to !in shown.indices) return order
    val moved = shown.toMutableList().apply { add(to, removeAt(from)) }
    val fratrie = siblings.toSet()
    return order.filterNot { it in fratrie } + moved
}

/** [accounts] with [folderId] moved one place among [siblings] in [id]'s folder order, decided on
 *  the order that record holds in [accounts] and nowhere else. `AccountStore.moveFolder` hands the
 *  list it read under its lock: a finished order computed by the caller on an earlier read would
 *  lose the first of two quick taps, the second one's list never having seen it. `it.id == id`
 *  matters because on IMAP folder ids are paths: without it a move reorders a namesake on another
 *  account. */
internal fun withFolderMoved(
    accounts: List<StoredAccount>,
    id: String,
    siblings: List<String>,
    folderId: String,
    up: Boolean,
): List<StoredAccount> =
    accounts.map {
        if (it.id == id) it.copy(folderOrder = folderOrderAfterMove(it.folderOrder, siblings, folderId, up)) else it
    }

/**
 * The folder order after [folderId] is dropped at [toIndex] among [siblings], as drawn: the order
 * the same number of single moves of [folderOrderAfterMove] would write, in one write. [order] itself
 * when nothing moves (dropped where it stood, not among the siblings, an index out of the fratrie).
 */
fun folderOrderAfterDrop(order: List<String>, siblings: List<String>, folderId: String, toIndex: Int): List<String> {
    val shown = siblingsInFolderOrder(order, siblings)
    val from = shown.indexOf(folderId)
    if (from < 0 || toIndex !in shown.indices || toIndex == from) return order
    val moved = shown.toMutableList().apply { add(toIndex, removeAt(from)) }
    val fratrie = siblings.toSet()
    return order.filterNot { it in fratrie } + moved
}

/** [accounts] with [folderId] dropped at [toIndex] among [siblings] in [id]'s folder order, decided
 *  on the record [accounts] holds, for the reasons given on [withFolderMoved]. */
internal fun withFolderDropped(
    accounts: List<StoredAccount>,
    id: String,
    siblings: List<String>,
    folderId: String,
    toIndex: Int,
): List<StoredAccount> =
    accounts.map {
        if (it.id == id) it.copy(folderOrder = folderOrderAfterDrop(it.folderOrder, siblings, folderId, toIndex)) else it
    }
