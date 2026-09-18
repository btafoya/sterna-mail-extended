package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, the last resort: `StorageRepository` needs an Android `Context` and `DataFactory` a
 * Room database, neither built on the JVM. What the wiring does once in place is executed by
 * [PurgeWithoutUidPlusResumesTest] and `PendingExpungeDaoSqlTest`.
 */
class PendingExpungeWiringTest {

    private fun code(text: String): List<String> =
        text.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") || it.isEmpty() }

    private fun bodyOf(file: File, functionName: String): List<String> {
        val source = file.readText()
        val at = source.indexOf("fun $functionName(")
        assertTrue("no fun $functionName( in ${file.name}, renamed?", at >= 0)
        var i = source.indexOf('{', at)
        var depth = 0
        val open = i
        do {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        } while (depth > 0)
        return code(source.substring(open, i))
    }

    @Test fun `signing out purges the account's pending expunges`() {
        assertEquals(
            listOf("runCatching { pendingExpungeDao.deleteForAccount(accountId) }"),
            bodyOf(STORAGE, "purgeAccount").filter { "pendingExpunge" in it },
        )
    }

    @Test fun `the service and the renumbering path share one store`() {
        val factory = code(FACTORY.readText())
        assertEquals(
            listOf("val pendingExpunges = MailboxPendingExpungeStore(database.pendingExpungeDao())"),
            factory.filter { it.startsWith("val pendingExpunges") },
        )
        assertEquals(
            "handed once to MailboxUidValidityStore and once to ImapMailService, both positionally last",
            listOf("pendingExpunges,", "pendingExpunges,"),
            factory.filter { it == "pendingExpunges," },
        )
    }

    private companion object {
        val STORAGE: File by lazy { repoFile("core/data/src/main/kotlin/app/sterna/core/data/storage/StorageRepository.kt") }
        val FACTORY: File by lazy { repoFile("core/data/src/main/kotlin/app/sterna/core/data/DataFactory.kt") }

        fun repoFile(path: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, path) }
                .firstOrNull { it.isFile }
                ?: error("$path not found above ${File("").absoluteFile}")
    }
}
