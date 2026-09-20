package app.sterna.core.data.account

/** The write behind the per-account choice of which folder carries each special role, kept out of
 *  [AccountStore] for a JVM test. */

/** The roles a user may pin a folder to, and the order a folder pinned to two of them settles in:
 * a cache row carries one role. `inbox` is not among them, nor `all`, which names no folder the app
 * routes a message to. This list is the whole guard on the role a choice names; the folder a choice
 *  points at is guarded separately, by `honouredSpecialFolders`. */
val SPECIAL_FOLDER_ROLES = listOf("archive", "sent", "drafts", "trash", "junk")

/** [accounts] with [role] pinned to [folderId] on the one record [id] names, or back to the
 * automatic role when [folderId] is null. `it.id == id` matters because on IMAP folder ids are
 * paths, without it a choice reaches a namesake on another account (#92/#121).
 *
 * A folder carries one pinned role: taking it for [role] releases the other role it was pinned to.
 * A record naming one folder twice is a setting the screen reads back and nothing obeys, a cache
 * row holding one role: `specialRole` settles that folder on the first of [SPECIAL_FOLDER_ROLES],
 * the second role is left with no holder at all, and a delete aimed at it creates a bin beside the
 * folder the user designated. Handing a role back to the
 *  automatic rule releases nothing: it names no folder to release it from. */
internal fun withSpecialFolder(
    accounts: List<StoredAccount>,
    id: String,
    role: String,
    folderId: String?,
): List<StoredAccount> =
    accounts.map {
        if (it.id == id) {
            val folders = it.specialFolders
            it.copy(
                specialFolders = if (folderId == null) {
                    folders - role
                } else {
                    folders.filterNot { (key, folder) -> key != role && folder == folderId } + (role to folderId)
                },
            )
        } else {
            it
        }
    }

/** [accounts] with [id]'s choices re-keyed after a rename ([oldId] and everything under it moved
 * onto [newId], as [AccountStore.replaceWatchedFolder] does). A choice is a VALUE of this map, so
 * every value is re-encoded and no key is touched; a record written before [withSpecialFolder]
 * released may still name one folder under two roles, and both follow the rename. Descendant
 * test is the full prefix plus the delimiter, never bare, or `Travailleur` would be dragged along
 *  with `Travail` (which an empty [delimiter] does anyway, as it does for the three registries). */
internal fun withRenamedSpecialFolder(
    accounts: List<StoredAccount>,
    id: String,
    oldId: String,
    newId: String,
    delimiter: String,
): List<StoredAccount> =
    accounts.map { account ->
        if (account.id == id) {
            account.copy(
                specialFolders = account.specialFolders.mapValues { (_, folder) ->
                    when {
                        folder == oldId -> newId
                        folder.startsWith(oldId + delimiter) -> newId + folder.removePrefix(oldId)
                        else -> folder
                    }
                },
            )
        } else {
            account
        }
    }

/** [accounts] with every role naming one of [folderIds] dropped from [id]'s choices, the ids a
 * folder delete has just destroyed, descendants included, as the caller computed them.
 *
 * Forgotten here and kept when a folder merely stops being listed, which is the opposite decision
 * for the opposite fact: an unlisted folder may be back next listing, a destroyed path is one a new
 * folder can be created at, and a key left on it would make that folder be born the bin: deletes
 *  landing in it, out of every search, and "empty the trash" destroying what was filed there. */
internal fun withoutSpecialFolders(
    accounts: List<StoredAccount>,
    id: String,
    folderIds: Collection<String>,
): List<StoredAccount> =
    accounts.map { account ->
        if (account.id == id) {
            val destroyed = folderIds.toSet()
            account.copy(specialFolders = account.specialFolders.filterValues { it !in destroyed })
        } else {
            account
        }
    }
