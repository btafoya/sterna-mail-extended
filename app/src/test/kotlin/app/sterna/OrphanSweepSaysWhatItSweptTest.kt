package app.sterna

import app.sterna.core.data.storage.OrphanedAccountCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The orphan sweep empties a whole account's cache, its address-book copy included: whenever it
 * removes something, both callers log the account ids at info. The call sites are read as source,
 * the last resort for an `Application` and a `ViewModel` the JVM cannot build; the line is executed.
 */
class OrphanSweepSaysWhatItSweptTest {

    @Test fun `the line names the swept accounts, and a sweep of nothing says nothing`() {
        assertEquals(
            "Orphaned-cache sweep removed the cached data of account(s) acc-1, acc-2",
            OrphanedAccountCache.sweptLine(listOf("acc-1", "acc-2")),
        )
        assertNull(OrphanedAccountCache.sweptLine(emptyList()))
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
