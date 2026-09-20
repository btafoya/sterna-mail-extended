package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, and a last resort, same instrument and disclaimer as
 * [HiddenFoldersStoreWiringTest]: what the choice DECIDES is executed next door
 * ([SpecialFoldersTest] and `ChosenSpecialFolderTest`); what nothing in this module can run is the
 * store calling it at all, and the choice reaching the credentials every listing is opened from.
 * `AccountStore` opens shared preferences through a `Context` while being constructed.
 */
class SpecialFoldersStoreWiringTest {

    @Test fun `the setter writes the mapped list back through saveAccounts, under the lock`() {
        assertEquals(
            "AccountStore.setSpecialFolder changed shape. It must hand the WHOLE account list to " +
                "withSpecialFolder with the id, the role AND the folder it was given, and store " +
                "the result through saveAccounts under @Synchronized: swap role and folder and " +
                "the registry is keyed by folder, dropping @Synchronized loses whatever another " +
                "thread wrote to the account blob in the meantime.",
            listOf(
                "@Synchronized",
                "override fun setSpecialFolder(id: String, role: String, folderId: String?) {",
                "saveAccounts(withSpecialFolder(accounts(), id, role, folderId))",
                "}",
            ),
            block("override fun setSpecialFolder(", count = 3, before = 1),
        )
    }

    /**
     * The one crossing that makes the choice do anything at all: `credentials()` is what every
     * IMAP listing is opened from, and the listing is where a role is decided. Without this line
     * the setting is stored, the screen shows it, and no folder ever changes role.
     */
    @Test fun `the choice is carried on the credentials, read off the record's own row`() {
        assertEquals(
            "AccountStore.credentials no longer hands the account's chosen folders to " +
                "AccountCredentials, or reads them off the wrong record. `login.specialFolders` " +
                "is the defect: two delegated mailboxes under one login have their own folder " +
                "trees and a path is meaningless on the other (#31).",
            listOf("specialFolders = account.specialFolders,"),
            codeLines(ACCOUNT_STORE).filter { it.contains("specialFolders = ") },
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
    }
}
