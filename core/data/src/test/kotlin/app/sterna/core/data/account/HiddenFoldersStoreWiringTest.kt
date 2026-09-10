package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, and a last resort, same instrument and disclaimer as
 * [CollapsedFoldersStoreWiringTest]: what the registry DECIDES is executed next door
 * ([HiddenFoldersTest]); what nothing in this module can run is the store calling it at all, and
 * the folder class calling the store. Neither a `Context` nor Room is available here.
 */
class HiddenFoldersStoreWiringTest {

    @Test fun `the getter answers from the stored account, falling back to an empty registry`() {
        assertEquals(
            "AccountStore.hiddenFolders no longer answers from the account the id names. An id it " +
                "cannot resolve must read as an EMPTY registry, which is every folder shown, and " +
                "answering a constant makes the whole registry inert with every other test green.",
            listOf("fun hiddenFolders(id: String): Set<String> = account(id)?.hiddenFolders ?: emptySet()"),
            block("fun hiddenFolders("),
        )
    }

    @Test fun `the setter writes the mapped list back through saveAccounts, under the lock`() {
        assertEquals(
            "AccountStore.setFolderHidden changed shape. It must hand the WHOLE account list to " +
                "withHiddenFolder with the id AND the folder id it was given, and store the result " +
                "through saveAccounts under @Synchronized: passing anything else for the id hides " +
                "a folder on another account (#92/#121), and dropping @Synchronized loses whatever " +
                "another thread wrote to the account blob in the meantime.",
            listOf(
                "@Synchronized",
                "fun setFolderHidden(id: String, folderId: String, hidden: Boolean) {",
                "saveAccounts(withHiddenFolder(accounts(), id, folderId, hidden))",
                "}",
            ),
            block("fun setFolderHidden(", count = 3, before = 1),
        )
    }

    @Test fun `the rename re-key and the delete purge delegate to the pure functions, under the lock`() {
        assertEquals(
            "AccountStore.replaceHiddenFolder is gone or changed shape. Swap oldId and newId and " +
                "the hidden folder moves onto a path that no longer exists, silently; the " +
                "arguments and their order are what only this can see.",
            listOf(
                "@Synchronized",
                "override fun replaceHiddenFolder(id: String, oldId: String, newId: String, delimiter: String) {",
                "saveAccounts(withRenamedHiddenFolder(accounts(), id, oldId, newId, delimiter))",
                "}",
            ),
            block("override fun replaceHiddenFolder(", count = 3, before = 1),
        )
        assertEquals(
            "AccountStore.forgetHiddenFolders is gone or changed shape. It must hand the ids it " +
                "was given, unaltered: recomputing descendants here would answer a second time a " +
                "question the delete already answered with the server's own delimiter.",
            listOf(
                "@Synchronized",
                "override fun forgetHiddenFolders(id: String, folderIds: Collection<String>) {",
                "saveAccounts(withoutHiddenFolder(accounts(), id, folderIds))",
                "}",
            ),
            block("override fun forgetHiddenFolders(", count = 3, before = 1),
        )
    }

    @Test fun `the folder rename re-keys the hide registry too, with the same delimiter`() {
        assertEquals(
            "MailFolders.renameFolder no longer re-keys the three registries with the delimiter " +
                "it resolved for this account. Drop the third line and a hidden folder comes back " +
                "under its new path; hand it '/' instead of `delim` and nothing moves at all on a " +
                "dot-delimited server.",
            listOf(
                "accountStore.replaceWatchedFolder(credentials.id, mailboxId, newPath, delim)",
                "accountStore.replaceCollapsedFolder(credentials.id, mailboxId, newPath, delim)",
                "accountStore.replaceHiddenFolder(credentials.id, mailboxId, newPath, delim)",
            ),
            block("accountStore.replaceWatchedFolder(credentials.id", count = 3, path = MAIL_FOLDERS),
        )
    }

    @Test fun `the folder delete purges the hide registry with the ids it destroyed`() {
        assertEquals(
            "MailFolders.deleteFolder no longer purges the hide registry, or purges something " +
                "other than `targets` — the paths it actually destroyed, descendants included, " +
                "resolved with the server's own delimiter. A key left behind on a destroyed path " +
                "makes a folder later created at that path be born hidden, which is invisible on " +
                "screen and so undiagnosable; passing mailboxId alone leaves every child's key.",
            listOf("accountStore.forgetHiddenFolders(credentials.id, targets)"),
            block("accountStore.forgetHiddenFolders(", path = MAIL_FOLDERS),
        )
    }

    /**
     * [count] consecutive code lines of [path] from the single one starting with [prefix], [before]
     * lines earlier when the annotation above it is part of the rule. A prefix matching anything
     * other than exactly one line returns a message, so an assertion never passes on nothing.
     */
    private fun block(prefix: String, count: Int = 1, before: Int = 0, path: String = ACCOUNT_STORE): List<String> {
        val code = codeLines(path)
        val starts = code.indices.filter { code[it].startsWith(prefix) }
        val start = starts.singleOrNull()
            ?: return listOf("«${starts.size} code lines start with `$prefix`»")
        return code.subList(maxOf(start - before, 0), minOf(start - before + count + before, code.size))
    }

    /** [path]'s lines, trimmed, comments and blanks dropped so no rule is satisfied by prose. */
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
