package app.sterna.core.data.account

import java.io.File
import java.util.UUID

/**
 * The directory under `filesDir` holding the account photos. `backup_rules.xml` and
 * `data_extraction_rules.xml` exclude it by this literal: renaming it here lets it into a backup.
 */
const val ACCOUNT_PHOTO_DIR = "account-photos"

/**
 * The account photo files, addressed by the bare names [StoredAccount.photo] holds: never a path
 * and never the account id, null for the letter. Plain `File` work on one directory, so a JVM test
 * can run it on a temporary one.
 */
class AccountPhotoFiles(private val dir: File) {

    /** The file [name] stands for, or null when it is no bare file name: a record edited to hold a
     *  path must not reach outside [dir]. */
    fun fileOf(name: String?): File? = name?.takeIf(::isBareName)?.let { File(dir, it) }

    /** Every name in the directory, finished photos or not. */
    fun names(): List<String> = dir.list()?.toList().orEmpty()

    /** Writes [png] under a name never used before, and returns it: a new name per photo is what
     *  redraws a screen keyed on it. Written before any record names it, so a crash in between
     *  leaves a file no account names, which the sweep collects. */
    fun write(png: ByteArray): String {
        dir.mkdirs()
        val name = "${UUID.randomUUID()}.png"
        File(dir, name).writeBytes(png)
        return name
    }

    fun delete(names: Collection<String>) {
        names.forEach { fileOf(it)?.delete() }
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun isBareName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name
}

/** The photo names [accounts] point at. */
internal fun photoNamesOf(accounts: Collection<StoredAccount>): Set<String> =
    accounts.mapNotNullTo(mutableSetOf()) { it.photo }

/**
 * The photos a change of the account list and of the linked memory leaves unnamed: named before,
 * by a record or a remembered share, and by neither after. A photo still named somewhere is kept,
 * so two records sharing one file, a share remembered after its pruning, keep it.
 */
internal fun photosLeftBehind(
    before: List<StoredAccount>,
    memoryBefore: Map<String, List<StoredAccount>>,
    after: List<StoredAccount>,
    memoryAfter: Map<String, List<StoredAccount>>,
): Set<String> =
    photoNamesOf(before + memoryBefore.values.flatten()) - photoNamesOf(after + memoryAfter.values.flatten())

/**
 * Points account [id] of [before] at [photo], null for the letter, stores the list through [save],
 * then deletes the file the account pointed at until now. `saveAccounts` writes through
 * `apply()`, so a process killed before the preferences reach the disk can still come back naming
 * the deleted file: the avatar then falls back to the letter and the sweep collects the new file.
 */
internal fun AccountPhotoFiles.repoint(
    before: List<StoredAccount>,
    memory: Map<String, List<StoredAccount>>,
    id: String,
    photo: String?,
    save: (List<StoredAccount>) -> Unit,
) {
    val after = before.map { if (it.id == id) it.copy(photo = photo) else it }
    save(after)
    delete(photosLeftBehind(before, memory, after, memory))
}
