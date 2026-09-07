package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, same instrument and disclaimer as [UploadSentCopyStoreWiringTest]: it reads
 * `AccountStore.kt` as text and proves nothing about a running store, which this module cannot
 * build (a `Context` and the Keystore, no Robolectric here).
 *
 * It exists because the executable half stops at this door. [ShowOnlySubscribedFoldersTest] runs
 * both decisions on real arguments — but a perfect `withShowOnlySubscribedFolders` is worth nothing
 * if the store calls it on `listOf(accounts().first())`, saves the list it was handed instead of
 * the mapped one, or answers the getter with a constant. All three compile, keep every behavioural
 * test green, and make the switch command nothing at all.
 *
 * Whole trimmed lines, as a CONTIGUOUS block, never a `contains`: a `contains` is blind to
 * anything APPENDED to a line, and a per-line check is blind to a statement slipped between two.
 * The block starts one line above the setter's signature, on `@Synchronized` — this is a
 * read-modify-write over the whole account list, and without it an edit made from another thread
 * between `accounts()` and `saveAccounts()` is silently overwritten.
 */
class ShowOnlySubscribedFoldersStoreWiringTest {

    @Test fun `the getter answers from the stored account, falling back to off`() {
        assertEquals(
            "AccountStore.showOnlySubscribedFolders no longer answers from the account the id " +
                "names. Answering a constant makes the whole setting inert while every other test " +
                "stays green; and an id it cannot resolve must read as false — hiding folders for " +
                "an account whose record was not found is how mail disappears from the folder list " +
                "with nobody having asked.",
            listOf("fun showOnlySubscribedFolders(id: String): Boolean = showOnlySubscribedFoldersOf(account(id))"),
            block("fun showOnlySubscribedFolders(", 1),
        )
    }

    @Test fun `the setter writes the mapped list back through saveAccounts, under the lock`() {
        assertEquals(
            "AccountStore.setShowOnlySubscribedFolders changed shape. It must hand the WHOLE " +
                "account list to withShowOnlySubscribedFolders with the id it was given, and store " +
                "the result through saveAccounts under @Synchronized. Passing anything else for " +
                "the id (or dropping the mapped list) writes one account's choice onto another's, " +
                "with every behavioural test still green.",
            listOf(
                "@Synchronized",
                "fun setShowOnlySubscribedFolders(id: String, enabled: Boolean) {",
                "saveAccounts(withShowOnlySubscribedFolders(accounts(), id, enabled))",
                "}",
            ),
            block("fun setShowOnlySubscribedFolders(", 3, before = 1),
        )
    }

    /**
     * [count] consecutive CODE lines of `AccountStore.kt` from the single one starting with
     * [prefix], [before] lines earlier when the annotation above it is part of the rule. Comments
     * and blank lines are dropped first, so no rule can be satisfied by prose; a prefix matching
     * anything other than exactly one line returns a message, so an assertion never passes by
     * matching nothing.
     */
    private fun block(prefix: String, count: Int, before: Int = 0): List<String> {
        val path = "core/data/src/main/kotlin/app/sterna/core/data/account/AccountStore.kt"
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, path).isFile }
            ?: error("cannot locate the repo root from ${java.io.File("").absolutePath}")
        val code = java.io.File(root, path).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        val starts = code.indices.filter { code[it].startsWith(prefix) }
        val start = starts.singleOrNull()
            ?: return listOf("«${starts.size} code lines start with `$prefix`»")
        return code.subList(maxOf(start - before, 0), minOf(start - before + count + before, code.size))
    }
}
