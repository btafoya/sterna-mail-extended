package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, same instrument and disclaimer as [AccountStoreGateWiringTest]: it reads
 */
class UploadSentCopyStoreWiringTest {

    @Test fun `the getter reads the stored field and falls back to on`() {
        assertEquals(
            "AccountStore.uploadSentCopy no longer answers from the account's stored field with a " +
                "true fallback. Answering a constant makes the whole setting inert while every " +
                "other test stays green; falling back to false stops archiving sent mail for every " +
                "account whose record predates the field.",
            listOf("override fun uploadSentCopy(id: String): Boolean = account(id)?.uploadSentCopy ?: true"),
            block("override fun uploadSentCopy(", 1),
        )
    }

    @Test fun `the setter writes the account back through saveAccounts`() {
        assertEquals(
            "AccountStore.setUploadSentCopy changed shape. It must copy the one account and hand " +
                "the whole list to saveAccounts under @Synchronized — that is what puts the choice " +
                "on disk (through the blob gate) instead of in memory until the next launch.",
            listOf(
                "@Synchronized",
                "fun setUploadSentCopy(id: String, enabled: Boolean) {",
                "saveAccounts(accounts().map { if (it.id == id) it.copy(uploadSentCopy = enabled) else it })",
                "}",
            ),
            block("fun setUploadSentCopy(", 3, before = 1),
        )
    }

    /**
     * [count] consecutive code lines of `AccountStore.kt` from the single one starting with
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
