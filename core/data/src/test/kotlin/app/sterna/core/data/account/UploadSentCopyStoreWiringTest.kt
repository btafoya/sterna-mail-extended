package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, same instrument and disclaimer as [AccountStoreGateWiringTest]: it reads
 * `AccountStore.kt` as text and proves nothing about a running store. Nothing in this module can
 * build one (it wants a `Context` and the Keystore, and there is no Robolectric here).
 *
 * It exists because the executable half stops at this door. [UploadSentCopyDefaultTest] proves the
 * record decodes to true, `SentCopyUploadTest` runs the decision, `SentCopyCallSiteTest` pins the
 * delivery asking for it — and between the two sits this pair of one-line functions, which nothing
 * executes. `fun uploadSentCopy(id: String) = true` compiles, keeps all of the above green, and
 * makes the setting command nothing at all: the switch moves, the store even stores the value, and
 * every send still uploads its copy. A setter that stops writing is the mirror image, with the
 * choice lost at the next cold start instead.
 *
 * Whole trimmed lines, never a `contains`: what would go wrong here reads as an APPENDED fallback
 * (`?: true` turning into `?: true.also { … }`, a second condition on the setter's `map`).
 */
class UploadSentCopyStoreWiringTest {

    @Test fun `the getter reads the stored field and falls back to on`() {
        assertEquals(
            "AccountStore.uploadSentCopy no longer answers from the account's stored field with a " +
                "true fallback. Answering a constant makes the whole setting inert while every " +
                "other test stays green; falling back to false stops archiving sent mail for every " +
                "account whose record predates the field.",
            listOf("fun uploadSentCopy(id: String): Boolean = account(id)?.uploadSentCopy ?: true"),
            block("fun uploadSentCopy(", 1),
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
