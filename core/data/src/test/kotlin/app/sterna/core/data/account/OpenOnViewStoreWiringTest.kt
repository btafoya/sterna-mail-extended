package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, same instrument and disclaimer as [StoredViewStoreWiringTest]: it reads
 * `AccountStore.kt` as text and proves nothing about a running store, which this module cannot
 * build (a `Context` and the Keystore, no Robolectric here). What it can see is the one thing no
 * behavioural test in the repo can: the accessor pair the "open on this view" setting (#185) is
 * stored and read through. The value is opaque to `:core:data` like the drawer memory, but unlike
 * it, it carries neither account id nor folder path: nothing prunes it on sign-out and `PRIVACY.md`
 * has nothing to promise about it.
 */
class OpenOnViewStoreWiringTest {

    @Test fun `the getter reads the open-on key, and nothing else`() {
        assertEquals(
            "AccountStore.openOnView no longer reads KEY_OPEN_ON straight out of the preferences. " +
                "It is read in the InboxViewModel CONSTRUCTOR, next to storedView, before any " +
                "list exists, and nothing executes it: pointed at KEY_VIEW it answers the drawer " +
                "memory instead of the chosen view, so the setting silently becomes 'resume where " +
                "I left off' for everyone, and every other test in the repo stays green.",
            listOf("fun openOnView(): String? = prefs.getString(KEY_OPEN_ON, null)"),
            block("fun openOnView(", 1),
        )
    }

    @Test fun `the setter writes every choice it is handed`() {
        assertEquals(
            "AccountStore.setOpenOnView no longer writes unconditionally to KEY_OPEN_ON. It is " +
                "called on every change of the setting and must overwrite: a guard in front of " +
                "the write (`if (prefs.contains(KEY_OPEN_ON)) return`) freezes the app on the " +
                "first view ever chosen, the settings screen keeps drawing the new choice from " +
                "memory, and the next cold start opens on the old one — no test executes this " +
                "line to notice. The null is load-bearing too: it is 'never chosen'.",
            listOf("fun setOpenOnView(value: String?) = prefs.edit().putString(KEY_OPEN_ON, value).apply()"),
            block("fun setOpenOnView(", 1),
        )
    }

    @Test fun `the chosen view has a key of its own`() {
        assertEquals(
            "KEY_OPEN_ON changed value. It shares a preferences file with KEY_CURRENT " +
                "(\"current\"), KEY_VIEW (\"current_view\") and the account list: pointed at any " +
                "of them it overwrites data it does not own — the current account, lost across a " +
                "cold start, or the drawer memory. Changing it also forgets the choice of every " +
                "install that already made one, which is recoverable but is a decision.",
            listOf("const val KEY_OPEN_ON = \"open_on_view\""),
            block("const val KEY_OPEN_ON", 1),
        )
    }

    /**
     * [count] consecutive code lines of `AccountStore.kt` from the single one starting with
     * [prefix], trimmed. Comments and blank lines are dropped first, so no rule can be satisfied by
     * prose; a prefix matching anything other than exactly one line returns a message, so an
     * assertion can never pass by matching nothing.
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
