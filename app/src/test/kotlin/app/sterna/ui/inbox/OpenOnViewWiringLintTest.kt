package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test: it reads `SelectionMemory.kt` as text, because the one place
 * the setting meets the memory takes an `AccountStore`, which no JVM test can build.
 * [OpenOnViewTest] executes the rule itself; this only says the rule is wired in, and only that.
 * Of the four holes `CONTRIBUTING.md` lists, three apply: nothing here says what `it` is bound to
 * at runtime (1), nor when either reader calls `restoredSelection` (3), nor whether a view the
 * rule answers is what the list ends up showing (4). The second is covered, and only because the
 * whole declaration is compared rather than a substring: a `?:` bolted onto the end is a
 * difference, not an addition this passes over.
 */
class OpenOnViewWiringLintTest {

    /**
     * The setting takes the memory's place, never a place above it. It composes on
     * `restoreSelection`'s result, which keeps `SelectionMemoryWiringLintTest`'s pinned call and
     * its three arguments intact, and it reads the bound a second time rather than hoisting it
     * into a local, which that same rule would read as a second call site.
     */
    @Test
    fun `the setting is composed onto the restored view, in the adapter that reads the stored view`() {
        assertEquals(
            "restoredSelection() must hand the stored setting, the restored view and the unified " +
                "view's bound to openOnSelection(), and answer what it says. Dropped, the setting " +
                "is written by the settings screen and read by nobody; composed before " +
                "restoreSelection() instead of after it, the account guard stops covering it. " +
                "Compared as the whole declaration, comments out and whitespace collapsed.",
            EXPECTED_DECLARATION,
            declarationOf("internal fun restoredSelection"),
        )
    }

    /**
     * A second call site would be a second copy of the setting's meaning, and the copy that drifts
     * takes one of the two readers with it.
     */
    @Test
    fun `the rule is called once in the production sources, and nowhere else`() {
        val calls = productionSources().flatMap { file ->
            codeLines(file)
                .filter { it.contains("openOnSelection(") && !it.contains("fun openOnSelection(") }
                .map { "${file.name}: $it" }
        }
        assertEquals(
            "openOnSelection() is the rule InboxViewModel and RootViewModel both inherit through " +
                "restoredSelection(). Called a second time anywhere, the two answers are free to " +
                "differ, and the push arm and the list would open on two different views. Calls " +
                "found were:\n${calls.joinToString("\n")}",
            1,
            calls.size,
        )
    }

    /** [name]'s declaration, from its own line down to the next top-level `internal` one. */
    private fun declarationOf(name: String): String {
        val lines = codeLines(SELECTION_MEMORY)
        val start = lines.indexOfFirst { it.startsWith(name) }
        check(start >= 0) { "no declaration starting with '$name' in ${SELECTION_MEMORY.name}" }
        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { it.startsWith("internal ") }
        return (listOf(lines[start]) + if (end < 0) rest else rest.take(end)).joinToString(" ")
    }

    /**
     * [file]'s code lines: comment lines and blanks dropped, each trimmed. `SelectionMemory.kt`
     * holds no trailing `//` and no string literal opening a comment, so a line test is enough
     * here, unlike the scanner `SelectionMemoryWiringLintTest` needs for a whole module.
     */
    private fun codeLines(file: File): List<String> = file.readLines()
        .map { it.trim() }
        .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("/*") || it.startsWith("*") }

    private fun productionSources(): List<File> = MODULE_SOURCE_DIRS
        .map { File(root, it) }
        .onEach {
            check(it.isDirectory) {
                "source directory missing: $it — this lint would then scan less than the module"
            }
        }
        .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    companion object {
        private val MODULE_SOURCE_DIRS = listOf(
            "app/src/main/kotlin",
            "app/src/benchShared/kotlin",
            "app/src/testApp/kotlin",
        )

        /** The whole adapter, memory first, setting composed on top, both on one line. */
        private const val EXPECTED_DECLARATION =
            "internal fun restoredSelection(store: AccountStore): Sel? = restoreSelection( " +
                "store.storedView(), store.currentId(), " +
                "AllInboxesView.existsFor(store.accounts()), " +
                ").let { openOnSelection(store.openOnView(), it, " +
                "AllInboxesView.existsFor(store.accounts())) }"

        private const val SELECTION_MEMORY_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/SelectionMemory.kt"

        /** Repo root, walked up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SELECTION_MEMORY_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        private val SELECTION_MEMORY: File by lazy { File(root, SELECTION_MEMORY_PATH) }
    }
}
