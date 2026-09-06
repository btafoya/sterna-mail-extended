package app.sterna.core.data.account

/**
 * The write behind the drawer's collapse chevron, kept out of [AccountStore] so it can be
 * EXECUTED: nothing in this module can build that store (it wants a `Context` and the Keystore,
 * and there is no Robolectric here), so a decision folded into it is reachable by a source lint
 * and by nothing else — and the part worth running is precisely the one a lint cannot run, the
 * guard that decides WHICH account a fold belongs to.
 */

/**
 * [accounts] with the collapse choice for [folderId] written on the ONE record [id] names, every
 * other one handed back untouched.
 *
 * [collapsed] is STORED, both ways: `false` writes `false`, it does not drop the key. The key
 * is the user's explicit choice and its absence means "nobody decided" — see
 * [StoredAccount.collapsedFolders]. Removing it on `false` would make unfolding a folder
 * indistinguishable from never having touched it, and the drawer's default would fold it again on
 * the next launch.
 *
 * The `it.id == id` guard is the other half. Without it, folding a folder on one account folds
 * whatever carries the same id on the neighbouring account — and on IMAP the ids are PATHS, so a
 * namesake folder ("Travail") is a routine collision (#92/#121). The whole list comes back (the
 * store saves it as one blob), in the order it came in.
 */
internal fun withCollapsedFolder(
    accounts: List<StoredAccount>,
    id: String,
    folderId: String,
    collapsed: Boolean,
): List<StoredAccount> =
    accounts.map {
        if (it.id == id) {
            it.copy(collapsedFolders = it.collapsedFolders + (folderId to collapsed))
        } else {
            it
        }
    }

/**
 * [accounts] with [id]'s fold registry RE-KEYED after a folder rename: the entry for [oldId], and
 * every entry under `oldId + delimiter`, moved onto [newId]. Every other record handed back
 * untouched.
 *
 * Why it has to exist: on IMAP the folder id IS its path, so `MailRepository.renameFolder`
 * CHANGES it. `AccountStore.replaceWatchedFolder` has re-keyed the watch flags for exactly that
 * reason since #16, and this registry did not follow. It was harmless while an absent key meant
 * "unfolded"; since the drawer's default folds a user folder with children, an absent key means
 * FOLDED — so the `false` the user wrote stayed behind under the old path and the folder they had
 * opened by hand closed itself the moment it was renamed.
 *
 * The value is CARRIED, never recomputed: re-keying is not re-deciding. A `false` must arrive as
 * a `false`, which is the entire point.
 *
 * The descendant test is `oldId + delimiter`, never `oldId`: `Travailleur` starts with `Travail`
 * and is nobody's child of it, and dragging it along would move a fold onto a folder nobody
 * renamed. Same shape, same delimiter, as [AccountStore.replaceWatchedFolder] — the two are called
 * side by side and a divergence between them is a registry that describes another tree.
 *
 * A JMAP id is stable across a rename, so this simply matches nothing there.
 */
internal fun withRenamedCollapsedFolder(
    accounts: List<StoredAccount>,
    id: String,
    oldId: String,
    newId: String,
    delimiter: String,
): List<StoredAccount> =
    accounts.map { account ->
        if (account.id == id) {
            account.copy(
                collapsedFolders = account.collapsedFolders.mapKeys { (folder, _) ->
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
