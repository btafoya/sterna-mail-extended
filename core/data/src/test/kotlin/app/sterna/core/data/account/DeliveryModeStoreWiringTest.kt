package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, same instrument and disclaimer as [UploadSentCopyStoreWiringTest]: it reads
 * `AccountStore.kt` as text and proves nothing about a running store, which this module cannot
 * build (a `Context` and the Keystore, no Robolectric here). What it can see is the one thing no
 * behavioural test in the repo can: the accessor pair the per-account delivery setting (#190) is
 * stored and read through.
 */
class DeliveryModeStoreWiringTest {

    @Test fun `the getter reads the stored field and answers null for an unknown id`() {
        assertEquals(
            "AccountStore.deliveryMode no longer answers from the account's stored field. " +
                "Answering a constant makes the whole per-account setting (#190) inert while " +
                "every other test in the repo stays green: the radio is written to disk and " +
                "never read back, so each account falls onto the global mode again, the " +
                "connection drops for a second on the next arm and comes back, and the screen " +
                "draws the old value again the next time it is opened. The null is load-bearing " +
                "too: it is 'this account never chose', which resolveDeliveryMode seeds with the " +
                "global mode — hand back a DeliveryMode for an unknown id and the seed can never " +
                "apply.",
            listOf("fun deliveryMode(id: String): DeliveryMode? = account(id)?.deliveryMode"),
            block("fun deliveryMode(", 1),
        )
    }

    @Test fun `the setter writes the account back through saveAccounts, under the lock`() {
        assertEquals(
            "AccountStore.setDeliveryMode changed shape. It must copy the ONE account the id " +
                "names and hand the WHOLE list to saveAccounts under @Synchronized — that is " +
                "what puts the choice on disk instead of in memory until the next launch. " +
                "Copying without the id test writes one account's choice onto every other one; " +
                "dropping @Synchronized loses whatever another thread wrote to the account blob " +
                "in the meantime, an identity, a watch flag, a whole linked account, with every " +
                "behavioural test still green.",
            listOf(
                "@Synchronized",
                "fun setDeliveryMode(id: String, mode: DeliveryMode) {",
                "saveAccounts(accounts().map { if (it.id == id) it.copy(deliveryMode = mode) else it })",
                "}",
            ),
            block("fun setDeliveryMode(", 3, before = 1),
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
