package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, same instrument and caveat as [AccountStorePgpResetWiringTest]: `AccountStore`
 * cannot be built on the JVM, so the calls it makes to the photo files are read off the shipped
 * source, whole trimmed lines. What those calls do is executed by [AccountPhotosTest].
 */
class AccountPhotoStoreWiringTest {

    @Test fun `setPhoto writes, names and deletes through repoint, under the lock`() {
        assertEquals(
            "setPhoto changed shape. It must refuse an id the readable list does not hold (an " +
                "unreadable list reads empty, and a photo written then would never be named), then " +
                "hand the whole list, the linked memory and the new file to repoint with the " +
                "guarded saveAccounts, which deletes the old file once the new name is stored. " +
                "Without @Synchronized the orphan sweep can find the new file before it is named.",
            listOf(
                "@Synchronized",
                "fun setPhoto(id: String, png: ByteArray?): Boolean {",
                "val current = accounts()",
                "if (current.none { it.id == id }) return false",
                "photoFiles.repoint(current, linkedMemory(), id, png?.let(photoFiles::write), ::saveAccounts)",
                "return true",
                "}",
            ),
            block("fun setPhoto(", count = 7, before = 1),
        )
    }

    @Test fun `removeCascading deletes the photos the removal leaves unnamed, memory read before it is forgotten`() {
        assertEquals(
            "removeCascading no longer deletes the photos of what it removes, or reads the memory " +
                "after forgetting the removed shares. The photo of a removed account then stays on " +
                "the phone for good, and PRIVACY.md says removing an account removes it.",
            listOf(
                "val memoryBefore = linkedMemory()",
                "val before = accounts()",
                "val remaining = before.filterNot { it.id in ids }",
                "saveLinkedMemory(forgetRemovedLinked(linkedMemory(), before.filter { it.id in ids }))",
                "saveAccounts(remaining)",
                "photoFiles.delete(photosLeftBehind(before, memoryBefore, remaining, linkedMemory()))",
            ),
            block("val before = accounts()", count = 6, before = 1),
        )
    }

    @Test fun `the sweep refuses an unreadable list and keeps what the memory names`() {
        assertEquals(
            "sweepOrphanedPhotos changed shape. On an unreadable list accounts() reads empty and " +
                "every photo would look orphaned; without linkedMemory() the photo of a share the " +
                "server pruned for a while is gone when it comes back.",
            listOf(
                "@Synchronized",
                "override fun sweepOrphanedPhotos(): List<String> {",
                "val accounts = accounts()",
                "if (gate.blobUnreadable) return emptyList()",
                "return app.sterna.core.data.storage.OrphanedAccountPhotos.sweep(photoFiles, accounts, linkedMemory())",
                "}",
            ),
            block("override fun sweepOrphanedPhotos(", count = 6, before = 1),
        )
    }

    @Test fun `the photos live in the directory the backup rules exclude`() {
        assertEquals(
            listOf(
                "private val photoFiles = AccountPhotoFiles(java.io.File(context.applicationContext.filesDir, ACCOUNT_PHOTO_DIR))",
            ),
            block("private val photoFiles =", count = 1),
        )
    }

    /** [count] code lines of `AccountStore.kt` from the single one starting with [prefix], [before]
     *  lines earlier; comments and blanks dropped, and a prefix matching other than one line is
     *  reported, so no assertion passes by matching nothing. */
    private fun block(prefix: String, count: Int, before: Int = 0): List<String> {
        val path = "core/data/src/main/kotlin/app/sterna/core/data/account/AccountStore.kt"
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, path).isFile }
            ?: error("cannot locate the repo root from ${java.io.File("").absolutePath}")
        val code = java.io.File(root, path).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        val starts = code.indices.filter { code[it].startsWith(prefix) }
        val anchor = starts.singleOrNull() ?: return listOf("«${starts.size} code lines start with `$prefix`»")
        val start = maxOf(anchor - before, 0)
        return code.subList(start, minOf(start + count, code.size))
    }
}
