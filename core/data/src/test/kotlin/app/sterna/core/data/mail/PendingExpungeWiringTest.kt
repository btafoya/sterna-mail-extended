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

    /** The statement itself, scoped to one account, is executed by `OrphanedAccountSweepSqlTest`. */
    @Test fun `signing out purges the account's download-ahead skips`() {
        assertEquals(
            listOf("runCatching { emailBodyDao.deleteDownloadAheadSkipsForAccount(accountId) }"),
            bodyOf(STORAGE, "purgeAccount").filter { "DownloadAheadSkips" in it },
        )
    }

    @Test fun `the service and the renumbering path share one store`() {
        val factory = code(FACTORY.readText())
        assertEquals(
            listOf("val pendingExpunges = MailboxPendingExpungeStore(database.pendingExpungeDao())"),
            factory.filter { it.startsWith("val pendingExpunges") },
        )
        assertEquals(
            "handed once to MailboxUidValidityStore and once to ImapMailService",
            listOf("pendingExpunges,", "pendingExpunges,"),
            factory.filter { it == "pendingExpunges," },
        )
    }

    /** The store of parts (#157): one instance, built under `filesDir`, handed to the three users
     *  that would otherwise compile without it, each argument a default of null or a positional
     *  slot. What each does with it is executed in `AttachmentStoreTest` and `DownloadAheadTest`. */
    @Test fun `the store of parts reaches the renumbering, the repository and the cache buttons`() {
        val factory = code(FACTORY.readText())
        assertEquals(
            listOf("val attachmentStore = AttachmentStore(java.io.File(appContext.filesDir, ATTACHMENT_STORE_DIR))"),
            factory.filter { it.startsWith("val attachmentStore") },
        )
        val at = factory.indexOf("val uidValidity = MailboxUidValidityStore(")
        assertEquals(
            "the renumbering's store, whose `invalidate` empties the folder's parts",
            listOf(
                "database.mailboxUidValidityDao(), database.emailBodyDao(), database.purgeSnapshotDao(),",
                "database.emailFtsDao(),",
                "pendingExpunges,",
                "attachmentStore,",
                ")",
            ),
            factory.subList(at + 1, at + 6),
        )
        assertEquals(
            "named once for MailRepository, whose parameter defaults to null, and once for StorageRepository",
            listOf("attachmentStore = attachmentStore,", "attachmentStore = attachmentStore,"),
            factory.filter { it.startsWith("attachmentStore =") },
        )
    }

    @Test fun `the orphaned-account sweep inventories the store of parts and empties an orphan's`() {
        assertEquals(
            listOf(
                "mailboxDao.accountIds() + emailFtsDao.accountIds() + emailBodyDao.accountIds() + attachmentStore.accountIds() +",
                "runCatching { attachmentStore.clearAccount(accountId) }",
            ),
            bodyOf(STORAGE, "purgeOrphanedAccounts").filter { "attachmentStore." in it },
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
