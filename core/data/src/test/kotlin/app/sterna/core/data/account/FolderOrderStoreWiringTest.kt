package app.sterna.core.data.account

import app.sterna.core.data.settings.SettingsBackup
import app.sterna.core.data.settings.SettingsBackupCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The folder order from [AccountStore] down to the pure functions it delegates to. What the
 * functions decide is executed here on real lists; that the store calls them, with these arguments
 * in this order, is read off the source, the last resort of every `*StoreWiringTest`: this module
 * has neither a `Context` nor the Keystore to build a store with.
 */
class FolderOrderStoreWiringTest {

    private fun account(id: String, order: List<String> = emptyList()) = StoredAccount(
        id = id,
        server = "imap.example.test",
        username = "$id@example.test",
        folderOrder = order,
    )

    // ── executed ────────────────────────────────────────────────────────────────────────────────

    @Test fun `a move is decided on the order the record holds, on that record alone`() {
        val after = withFolderMoved(
            listOf(account("a", listOf("Travail", "Perso", "Loisirs")), account("b", listOf("Travail", "Perso"))),
            id = "a",
            siblings = listOf("Travail", "Perso", "Loisirs"),
            folderId = "Loisirs",
            up = true,
        )
        assertEquals(
            "moving 'Loisirs' up on account a did not write [Travail, Loisirs, Perso] there and " +
                "leave b alone: the move is not decided on the order the record holds, or it " +
                "reached the namesake folders of another account.",
            listOf(listOf("Travail", "Loisirs", "Perso"), listOf("Travail", "Perso")),
            after.map { it.folderOrder },
        )
    }

    @Test fun `two moves in a row both land, the second from where the first left it`() {
        val siblings = listOf("A", "B", "C")
        val once = withFolderMoved(listOf(account("a")), "a", siblings, "C", up = true)
        val twice = withFolderMoved(once, "a", siblings, "C", up = true)
        assertEquals(
            "two taps on 'Monter C' did not bring C to the top: the second move did not start " +
                "from the order the first one wrote.",
            listOf("C", "A", "B"),
            twice.single().folderOrder,
        )
    }

    @Test fun `a backup carries the order out through the store's snapshot and back through its import`() {
        val stored = account("a", listOf("Perso", "Travail"))
        val snapshot = backupSnapshotOf(listOf(stored))
        val decoded = SettingsBackupCodec.decode(SettingsBackupCodec.encode(SettingsBackup(accounts = snapshot)))
        val restored = decoded?.accounts?.map { importedCopy(it, "fresh") }
        assertEquals(
            "the chosen folder order did not survive export then import through the snapshot " +
                "and the copy AccountStore makes: one of them drops the field.",
            listOf(listOf("Perso", "Travail")),
            restored?.map { it.folderOrder },
        )
        assertEquals(
            "the imported copy must take the fresh id and stay inert until signed in.",
            listOf("fresh" to true),
            restored?.map { it.id to it.importPending },
        )
    }

    @Test fun `a backup written before the order existed imports with none, and does not throw`() {
        val older = SettingsBackupCodec.decode(
            """{"version":1,"accounts":[{"id":"a1","server":"imap.example.test",""" +
                """"username":"demo@example.test","protocol":"IMAP","hiddenFolders":["Travail"]}]}""",
        )
        assertEquals(
            "a backup without 'folderOrder' must still import, with no order and the rest intact.",
            listOf(emptyList<String>() to setOf("Travail")),
            older?.accounts?.map { importedCopy(it, "fresh") }?.map { it.folderOrder to it.hiddenFolders },
        )
    }

    @Test fun `a sort rewrites the fratries handed on that record alone, other parents kept`() {
        val after = withFoldersSorted(
            listOf(
                account("a", listOf("Travail/Z", "zebra", "Travail/A", "alpha", "Gone")),
                account("b", listOf("zebra", "alpha")),
            ),
            id = "a",
            fratries = listOf(listOf("alpha", "Banque", "zebra")),
        )
        assertEquals(
            "sorting the top level of account a did not write [alpha, Banque, zebra] there with " +
                "Travail's children and the stale 'Gone' left in place, and b alone: the sort " +
                "dropped another parent's ranks, or reached the namesake folders of another account.",
            listOf(listOf("Travail/Z", "Travail/A", "Gone", "alpha", "Banque", "zebra"), listOf("zebra", "alpha")),
            after.map { it.folderOrder },
        )
    }

    @Test fun `a sort that changes nothing hands back the very list it was given`() {
        val before = listOf(account("a", listOf("Gone", "alpha", "zebra")), account("b"))
        assertSame(
            "a sort of an order already A to Z answered a new list: the store is handed a copy " +
                "to save for a tap that changed nothing.",
            before,
            withFoldersSorted(before, "a", listOf(listOf("alpha", "zebra"))),
        )
    }

    // ── read off the source ─────────────────────────────────────────────────────────────────────

    @Test fun `the move reads and writes under one lock, through the pure function`() {
        assertEquals(
            "AccountStore.moveFolder changed shape. It must hand withFolderMoved the list it just " +
                "read, under @Synchronized: a list computed by the caller on an earlier read, or a " +
                "write outside the lock, loses the first of two quick taps.",
            listOf(
                "@Synchronized",
                "fun moveFolder(id: String, siblings: List<String>, folderId: String, up: Boolean) {",
                "saveAccounts(withFolderMoved(accounts(), id, siblings, folderId, up))",
                "}",
            ),
            block("fun moveFolder(", count = 3, before = 1),
        )
    }

    @Test fun `the sort reads and writes under one lock, through the pure function`() {
        assertEquals(
            "AccountStore.sortFolders changed shape. It must hand withFoldersSorted the list it " +
                "just read, under @Synchronized, and the fratries it was given: a finished order " +
                "computed by the screen on an earlier read loses a move made just before the tap.",
            listOf(
                "@Synchronized",
                "fun sortFolders(id: String, fratries: List<List<String>>) {",
                "saveAccounts(withFoldersSorted(accounts(), id, fratries))",
                "}",
            ),
            block("fun sortFolders(", count = 3, before = 1),
        )
    }

    @Test fun `the rename re-key and the delete purge delegate to the pure functions, under the lock`() {
        assertEquals(
            "AccountStore.replaceFolderOrder is gone or changed shape. Swap oldId and newId and a " +
                "renamed folder loses its place and drops to the end of its parent, silently.",
            listOf(
                "@Synchronized",
                "override fun replaceFolderOrder(id: String, oldId: String, newId: String, delimiter: String) {",
                "saveAccounts(withRenamedFolderOrder(accounts(), id, oldId, newId, delimiter))",
                "}",
            ),
            block("override fun replaceFolderOrder(", count = 3, before = 1),
        )
        assertEquals(
            "AccountStore.forgetFolderOrder is gone or changed shape. It must drop exactly the ids " +
                "it was handed, the paths the delete destroyed.",
            listOf(
                "@Synchronized",
                "override fun forgetFolderOrder(id: String, folderIds: Collection<String>) {",
                "saveAccounts(withoutFolderOrder(accounts(), id, folderIds))",
                "}",
            ),
            block("override fun forgetFolderOrder(", count = 3, before = 1),
        )
    }

    @Test fun `the folder rename and delete keep the order registry in step, with their own ids`() {
        assertEquals(
            "MailFolders.renameFolder no longer re-keys the folder order from the old path to the " +
                "new one with the account's delimiter.",
            listOf("accountStore.replaceFolderOrder(credentials.id, mailboxId, newPath, delim)"),
            block("accountStore.replaceFolderOrder(", path = MAIL_FOLDERS),
        )
        assertEquals(
            "MailFolders.deleteFolder no longer drops `targets`, the paths it destroyed, from the " +
                "folder order: a folder later created at one of them would inherit its place.",
            listOf("accountStore.forgetFolderOrder(credentials.id, targets)"),
            block("accountStore.forgetFolderOrder(", path = MAIL_FOLDERS),
        )
    }

    @Test fun `the backup and the import go through the functions executed above`() {
        assertEquals(
            "AccountStore.accountsForBackup no longer answers backupSnapshotOf(accounts()): the " +
                "round trip executed above no longer tests what the export writes.",
            listOf("fun accountsForBackup(): List<StoredAccount> = backupSnapshotOf(accounts())"),
            block("fun accountsForBackup("),
        )
        assertEquals(
            "AccountStore.importAccounts no longer adds importedCopy under a fresh id: the round " +
                "trip executed above no longer tests what the import stores.",
            listOf("added += importedCopy(a, UUID.randomUUID().toString())"),
            block("added += "),
        )
    }

    /** [count] consecutive code lines of [path] from the single one starting with [prefix], [before]
     *  lines earlier when the annotation above it is part of the rule. Anything other than exactly
     *  one match returns a message, so an assertion never passes on nothing. */
    private fun block(prefix: String, count: Int = 1, before: Int = 0, path: String = ACCOUNT_STORE): List<String> {
        val code = codeLines(path)
        val starts = code.indices.filter { code[it].startsWith(prefix) }
        val start = starts.singleOrNull()
            ?: return listOf("«${starts.size} code lines start with `$prefix`»")
        return code.subList(maxOf(start - before, 0), minOf(start - before + count + before, code.size))
    }

    private fun codeLines(path: String): List<String> {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, path).isFile }
            ?: error("cannot locate the repo root from ${java.io.File("").absolutePath}")
        return java.io.File(root, path).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
    }

    private companion object {
        const val ACCOUNT_STORE = "core/data/src/main/kotlin/app/sterna/core/data/account/AccountStore.kt"
        const val MAIL_FOLDERS = "core/data/src/main/kotlin/app/sterna/core/data/mail/MailFolders.kt"
    }
}
