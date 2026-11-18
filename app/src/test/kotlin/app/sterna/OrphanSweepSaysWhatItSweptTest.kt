package app.sterna

import app.sterna.core.data.account.AccountPhotoFiles
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.storage.OrphanSweep
import app.sterna.core.data.storage.OrphanedAccountCache
import app.sterna.core.data.storage.OrphanedAccountPhotos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The orphan sweep empties a whole account's cache, its address-book copy included, and deletes the
 * account photo files nothing names any more: whenever it removes something, both callers log the
 * account ids and the photo count at info. The photo sweep is executed on a temporary directory. The call sites are read as source,
 * the last resort for an `Application` and a `ViewModel` the JVM cannot build; the line is executed.
 */
class OrphanSweepSaysWhatItSweptTest {

    @get:Rule val temp = TemporaryFolder()

    @Test fun `the line names the swept accounts and counts the photos, and a sweep of nothing says nothing`() {
        assertEquals(
            "Orphaned-cache sweep removed the cached data of account(s) acc-1, acc-2",
            OrphanedAccountCache.sweptLine(OrphanSweep(listOf("acc-1", "acc-2"))),
        )
        assertEquals(
            "Orphaned-cache sweep removed 2 account photo file(s) no account names",
            OrphanedAccountCache.sweptLine(OrphanSweep(emptyList(), listOf("p1.png", "p2.png"))),
        )
        assertEquals(
            "Orphaned-cache sweep removed the cached data of account(s) acc-1 and 1 account photo file(s) no account names",
            OrphanedAccountCache.sweptLine(OrphanSweep(listOf("acc-1"), listOf("p1.png"))),
        )
        assertNull(OrphanedAccountCache.sweptLine(OrphanSweep(emptyList())))
    }

    private fun account(id: String, photo: String?) =
        StoredAccount(id = id, server = "imap.example.test", username = "$id@example.test", photo = photo)

    @Test fun `the photo sweep deletes an orphan, keeps an account's and a remembered share's`() {
        val dir = temp.newFolder("account-photos")
        val files = AccountPhotoFiles(dir)
        val mine = files.write(byteArrayOf(1))
        val remembered = files.write(byteArrayOf(2))
        val orphan = files.write(byteArrayOf(3))

        val swept = OrphanedAccountPhotos.sweep(
            files,
            accounts = listOf(account("a", mine), account("b", null)),
            linkedMemory = mapOf("a" to listOf(account("pruned-share", remembered))),
        )

        assertEquals("the sweep reports what it deleted", listOf(orphan), swept)
        assertEquals(
            "an orphan photo stays for ever, or a photo still named is deleted. The remembered " +
                "share's is the one a server that prunes a share for a while would lose: it comes " +
                "back wearing the letter",
            setOf(mine, remembered),
            dir.list().orEmpty().toSet(),
        )
    }

    @Test fun `an empty account list sweeps no photo, as it sweeps no cache`() {
        val dir = temp.newFolder("account-photos")
        val files = AccountPhotoFiles(dir)
        val photo = files.write(byteArrayOf(1))

        assertEquals(emptyList<String>(), OrphanedAccountPhotos.sweep(files, emptyList(), emptyMap()))
        assertEquals(setOf(photo), dir.list().orEmpty().toSet())
    }

    private fun code(path: String): List<String> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
            ?: error("cannot locate the checkout from ${File("").absolutePath}")
        return File(root, path).readLines().map { it.trim() }
    }

    @Test fun `the start-up sweep logs what it swept, at info`() {
        val code = code("app/src/main/kotlin/app/sterna/SternaApplication.kt")
        val at = code.indexOf("runCatching { storageRepository.purgeOrphanedAccounts { accountStore.accounts().map { it.id } } }")
        assertEquals(
            "the sweep's result must reach the log, at info: the release build strips Log.d and Log.v",
            ".onSuccess { swept -> OrphanedAccountCache.sweptLine(swept)?.let { Log.i(\"Sterna\", it) } }",
            code.getOrNull(at + 1),
        )
    }

    @Test fun `the sign-out sweep logs what it swept, at info`() {
        val code = code("app/src/main/kotlin/app/sterna/ui/settings/AccountsViewModel.kt")
        val at = code.indexOf("storage.purgeOrphanedAccounts { store.accounts().map { it.id } }")
        assertEquals(
            listOf(
                "OrphanedAccountCache.sweptLine(",
                "storage.purgeOrphanedAccounts { store.accounts().map { it.id } }",
                ")?.let { android.util.Log.i(\"SternaAccounts\", it) }",
            ),
            code.subList(maxOf(0, at - 1), minOf(code.size, at + 2)),
        )
    }
}
