package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, same instrument and disclaimer as [StoredViewStoreWiringTest]: it reads
 * `AccountStore.kt` as text and proves nothing about a running store. Nothing in this module can
 * build one (it wants a `Context` and the Keystore, and there is no Robolectric here).
 *
 * These three lines are the only production code of #178's freshness register that no test
 * executes — the decision is run by `RefreshFreshnessTest` in :app, the wiring is pinned by
 * `RefreshFreshnessWiringLintTest` — and each of these compiles with the whole suite green:
 *  - the getter pointed at another key: every read answers something the decoder throws away, the
 *    register is always empty, and the guard silently never fires (the feature does not exist,
 *    and nothing is broken enough to notice);
 *  - the setter pointed at another key: it OVERWRITES data it does not own. `KEY_VIEW` is the
 *    drawer memory and `KEY_CURRENT` is the current account — the second is account loss across a
 *    cold start;
 *  - the key changing value, which is the same collision written one line higher.
 *
 * Whole trimmed lines, never a `contains`: what goes wrong here reads as a swapped constant or an
 * appended guard, and a fragment match is blind to a line that only got longer.
 */
class RefreshFreshnessStoreWiringTest {

    @Test fun `the getter reads the freshness key, and nothing else`() {
        assertEquals(
            "AccountStore.refreshFreshness no longer reads KEY_FRESHNESS straight out of the " +
                "preferences. It is called from the InboxViewModel CONSTRUCTOR (the cold start is " +
                "#178's own case) and nothing executes it: pointed at another key it answers a " +
                "string decodeFreshness drops, the register reads empty for ever, and every refresh " +
                "the guard was meant to skip fires again with the suite green.",
            listOf("fun refreshFreshness(): String? = prefs.getString(KEY_FRESHNESS, null)"),
            block("fun refreshFreshness(", 1),
        )
    }

    @Test fun `the setter writes every register it is handed`() {
        assertEquals(
            "AccountStore.setRefreshFreshness no longer writes unconditionally to KEY_FRESHNESS. " +
                "It is called after every successful refresh and must overwrite: a guard in front " +
                "of the write freezes the register on its first value, so folders stay 'fresh' long " +
                "after they went stale, and a wrong key overwrites data this register does not own.",
            listOf(
                "fun setRefreshFreshness(value: String?) = " +
                    "prefs.edit().putString(KEY_FRESHNESS, value).apply()",
            ),
            block("fun setRefreshFreshness(", 1),
        )
    }

    @Test fun `the register has a key of its own`() {
        assertEquals(
            "KEY_FRESHNESS changed value. It shares a preferences file with KEY_CURRENT " +
                "(\"current\"), KEY_VIEW (\"current_view\") and the account list: pointed at any of " +
                "them it destroys data it does not own — the current account, lost across a cold " +
                "start, or the drawer memory. Changing it also forgets the register of every " +
                "install that has one, which is harmless (one extra refresh) but is a decision.",
            listOf("const val KEY_FRESHNESS = \"refresh_freshness\""),
            block("const val KEY_FRESHNESS", 1),
        )
    }

    /**
     * [count] consecutive CODE lines of `AccountStore.kt` from the single one starting with
     * [prefix], trimmed and rejoined so a wrapped declaration reads as one line. Comments and
     * blank lines are dropped first, so no rule can be satisfied by prose; a prefix matching
     * anything other than exactly one line returns a message, so an assertion can never pass by
     * matching nothing.
     */
    private fun block(prefix: String, count: Int): List<String> {
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
        return code.subList(start, minOf(start + count, code.size))
    }
}
