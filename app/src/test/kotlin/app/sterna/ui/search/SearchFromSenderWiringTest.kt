package app.sterna.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test: `SternaApp`'s navigation graph cannot run on the JVM. It pins
 * that "search this sender" lands in the list's own search bar with From set, on the inbox entry's
 * ViewModel, and that no route to a second search screen survives. What `openSearchFrom` does is
 * pinned by `SearchHistoryWiringTest`; the criteria it asks are run by `BarSearchTest`.
 */
class SearchFromSenderWiringTest {

    @Test fun `search this sender opens the list's search with From set, then shows the list`() {
        val code = codeLines(STERNA_APP)
        val at = code.indexOf("composable(\"bysender\") { entry ->")
        assertTrue("the bysender destination is gone or renamed", at >= 0)
        val block = code.subList(at, minOf(at + 16, code.size))
        assertTrue(
            "the sender screen must reach the INBOX entry's ViewModel, the one the list's bar reads:\n" +
                block.joinToString("\n"),
            "val inboxEntry = remember(entry) { nav.getBackStackEntry(\"inbox\") }" in block &&
                "val inboxViewModel: InboxViewModel = viewModel(inboxEntry)" in block,
        )
        val handOff = block.indexOf("inboxViewModel.openSearchFrom(from)")
        assertEquals(
            "the address must become the bar's From before the list is shown:\n" + block.joinToString("\n"),
            listOf("inboxViewModel.openSearchFrom(from)", "nav.popBackStack(\"inbox\", inclusive = false)"),
            if (handOff >= 0) block.subList(handOff, handOff + 2) else emptyList<String>(),
        )
    }

    @Test fun `no route leads to a second search screen`() {
        assertEquals(
            emptyList<String>(),
            codeLines(STERNA_APP).filter { "\"search?" in it || "route = \"search" in it || "SearchScreen(" in it },
        )
    }

    private fun codeLines(file: File): List<String> = file.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }

    private companion object {
        val STERNA_APP: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "app/src/main/kotlin/app/sterna/ui/SternaApp.kt") }
                .firstOrNull { it.isFile }
                ?: error("cannot locate SternaApp.kt from ${File("").absolutePath}")
        }
    }
}
