package app.sterna.core.data.account

/** The writes behind the folder-management screen's hide switch (#207), kept out of [AccountStore]
 *  for a JVM test. */

/** [accounts] with [folderId] hidden or shown again on the one record [id] names. A set, not a
 * map: absent means visible, there is no third answer to keep. `it.id == id` matters because on
 *  IMAP folder ids are paths, without it a hide reaches a namesake on another account (#92/#121). */
internal fun withHiddenFolder(
    accounts: List<StoredAccount>,
    id: String,
    folderId: String,
    hidden: Boolean,
): List<StoredAccount> =
    accounts.map {
        if (it.id == id) {
            it.copy(hiddenFolders = if (hidden) it.hiddenFolders + folderId else it.hiddenFolders - folderId)
        } else {
            it
        }
    }

/** [accounts] with [id]'s hide registry re-keyed after a rename ([oldId] and its descendants moved
 * onto [newId], as [AccountStore.replaceWatchedFolder] does). Descendant test is the full prefix
 *  plus the delimiter, never bare, or `Travailleur` would be dragged along with `Travail`. */
internal fun withRenamedHiddenFolder(
    accounts: List<StoredAccount>,
    id: String,
    oldId: String,
    newId: String,
    delimiter: String,
): List<StoredAccount> =
    accounts.map { account ->
        if (account.id == id) {
            account.copy(
                hiddenFolders = account.hiddenFolders.map { folder ->
                    when {
                        folder == oldId -> newId
                        folder.startsWith(oldId + delimiter) -> newId + folder.removePrefix(oldId)
                        else -> folder
                    }
                }.toSet(),
            )
        } else {
            account
        }
    }

/** [accounts] with [folderIds] dropped from [id]'s hide registry, the ids a folder delete has just
 * destroyed, descendants included, as the caller computed them. A key left behind names a path a
 *  new folder can be created at, and that folder would be born hidden. */
internal fun withoutHiddenFolder(
    accounts: List<StoredAccount>,
    id: String,
    folderIds: Collection<String>,
): List<StoredAccount> =
    accounts.map {
        if (it.id == id) it.copy(hiddenFolders = it.hiddenFolders - folderIds.toSet()) else it
    }

/** One account's hide registry with the Inbox id it may never hide, read off ONE record: two
 * lookups can answer about two different accounts mid-switch (#92/#121). One value, because a
 *  screen that folds five flows into its state has no sixth slot in a typed `combine`. */
data class FolderHiding(val accountId: String?, val ids: Set<String>, val inboxId: String?) {
    /** This registry when the list on screen belongs to [listedAccountId], an empty one otherwise:
     * a `combine` emits on either input, so an account switch pairs the new registry with the
     *  previous list, whose ids are colliding paths on two IMAP accounts of one server (#92/#121). */
    fun forList(listedAccountId: String?): FolderHiding =
        if (accountId == listedAccountId) this else FolderHiding(listedAccountId, emptySet(), null)
}

/** What [accountId]'s own record hides. An id resolving to no record hides nothing: hiding on an
 *  unidentified account takes folders away with no screen able to bring them back. */
fun folderHidingOf(accountId: String?, accounts: List<StoredAccount>): FolderHiding {
    val account = accounts.firstOrNull { it.id == accountId }
    return FolderHiding(accountId, account?.hiddenFolders ?: emptySet(), account?.inboxId)
}
