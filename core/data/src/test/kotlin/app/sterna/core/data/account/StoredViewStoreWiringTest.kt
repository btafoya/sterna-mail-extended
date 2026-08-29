package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ⚠ SOURCE LINT, same instrument and disclaimer as [UploadSentCopyStoreWiringTest]: it reads
 * `AccountStore.kt` as text and proves nothing about a running store. Nothing in this module can
 * build one (it wants a `Context` and the Keystore, and there is no Robolectric here).
 *
 * It exists because these three lines are the ONLY production code of the drawer-memory change
 * that no test executes. The decision itself is run — `SelectionMemoryTest` calls
 * `encodeSelection`/`restoreSelection` on the JVM — and the wiring is pinned by
 * `SelectionMemoryWiringLintTest`; in between sit a getter, a setter and a key, and every one of
 * these compiles with the whole suite green:
 *  - `fun setStoredView(value: String?) { if (prefs.contains(KEY_VIEW)) return; … }` — the memory
 *    freezes on the FIRST view ever stored and every later one is dropped, so the app reopens on a
 *    folder the reader left weeks ago;
 *  - `fun storedView(): String? = prefs.getString(KEY_CURRENT, null)` — the read answers the
 *    ACCOUNT id, `restoreSelection` sees a one-part string, and the feature silently does not
 *    exist;
 *  - `const val KEY_VIEW = "current"` — the same collision written one line higher, which also
 *    overwrites the stored current account and loses the account across a cold start.
 *
 * Whole trimmed lines, never a `contains`: what goes wrong here reads as an APPENDED guard or a
 * swapped constant, and a fragment match is blind to a line that only got longer.
 */
class StoredViewStoreWiringTest {

    @Test fun `the getter reads the view key, and nothing else`() {
        assertEquals(
            "AccountStore.storedView no longer reads KEY_VIEW straight out of the preferences. " +
                "It is called from the InboxViewModel CONSTRUCTOR to seed the first list, and " +
                "nothing executes it: reading another key (KEY_CURRENT is one character away in " +
                "the same companion) hands restoreSelection a string it cannot parse, the drawer " +
                "memory quietly stops existing, and every other test in the repo stays green.",
            listOf("fun storedView(): String? = prefs.getString(KEY_VIEW, null)"),
            block("fun storedView(", 1),
        )
    }

    @Test fun `the setter writes every view it is handed`() {
        assertEquals(
            "AccountStore.setStoredView no longer writes unconditionally. This setter is called on " +
                "EVERY selection change and must overwrite: a guard added in front of the write " +
                "(`if (prefs.contains(KEY_VIEW)) return`) freezes the memory on the first view ever " +
                "stored, so the app keeps reopening on a folder the reader has long left — and no " +
                "test executes this line to notice.",
            listOf("fun setStoredView(value: String?) = prefs.edit().putString(KEY_VIEW, value).apply()"),
            block("fun setStoredView(", 1),
        )
    }

    @Test fun `the view has a key of its own`() {
        assertEquals(
            "KEY_VIEW changed value. It shares a preferences file with KEY_CURRENT (\"current\") " +
                "and the account list: pointed at either, the drawer memory overwrites data it does " +
                "not own — the stored current account, lost across a cold start. Changing it also " +
                "silently forgets the view of every install that already has one, which is " +
                "harmless but is a decision, not a rename.",
            listOf("const val KEY_VIEW = \"current_view\""),
            block("const val KEY_VIEW", 1),
        )
    }

    /**
     * [count] consecutive CODE lines of `AccountStore.kt` from the single one starting with
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
