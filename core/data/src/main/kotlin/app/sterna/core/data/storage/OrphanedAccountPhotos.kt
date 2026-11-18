package app.sterna.core.data.storage

import app.sterna.core.data.account.AccountPhotoFiles
import app.sterna.core.data.account.StoredAccount

/** What one orphan sweep removed: the accounts whose cache went, and the photo files no account named. */
data class OrphanSweep(val accountIds: List<String>, val photos: List<String> = emptyList())

/** The account photo files no account names any more, the net under a crash between writing a
 *  photo and naming it, swept with the orphaned caches. */
object OrphanedAccountPhotos {

    /**
     * Deletes the photo files of [files] that neither [accounts] nor [linkedMemory] names, and returns
     * their names. A share pruned by the server keeps its photo in the memory until it is granted
     * again. An empty [accounts] sweeps nothing, as [OrphanedAccountCache.orphans] does: an
     * unreadable list reads empty.
     */
    fun sweep(
        files: AccountPhotoFiles,
        accounts: List<StoredAccount>,
        linkedMemory: Map<String, List<StoredAccount>>,
    ): List<String> {
        if (accounts.isEmpty()) return emptyList()
        val named = (accounts + linkedMemory.values.flatten()).mapNotNull { it.photo }.toSet()
        val orphans = files.names().filterNot { it in named }
        files.delete(orphans)
        return orphans
    }
}
