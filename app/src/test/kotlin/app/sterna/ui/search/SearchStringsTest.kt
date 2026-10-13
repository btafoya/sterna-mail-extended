package app.sterna.ui.search

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Resource lint, same instrument and disclaimer as `FilterChipStringsTest`: the files are read as
 * text, nothing here resolves a string the way a device does. It pins that the one search surface's
 * words exist in every language, and that the words of the screen it replaced are gone rather than
 * left for translators to keep up.
 */
class SearchStringsTest {

    @Test
    fun `every language carries the search's words and none of the retired screen's`() {
        val offenders = mutableListOf<String>()
        val files = localeFiles()
        assertEquals("values/ plus ten languages", 11, files.size)
        for (file in files) {
            val locale = file.parentFile.name
            val values = NAMED.findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }
            PRESENT.filter { values[it].isNullOrBlank() }.forEach { offenders += "$locale: missing $it" }
            RETIRED.filter { it in values }.forEach { offenders += "$locale: still carries $it" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the English words say what the sheet and the chips say`() {
        val english = NAMED.findAll(File(res, "values/strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(
            listOf("To or Cc", "Since", "Until", "Filters", "Apply", "Clear all", "Search", "This account", "All accounts"),
            listOf(
                "search_to_or_cc", "search_since", "search_until", "search_filters", "search_apply", "search_clear_all",
                "inbox_search", "search_scope_this_account", "search_scope_all_accounts",
            ).map { english[it] },
        )
    }

    private fun localeFiles(): List<File> = (res.listFiles() ?: emptyArray())
        .filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) }
        .map { File(it, "strings.xml") }
        .filter { it.isFile }
        .sortedBy { it.parentFile.name }

    private companion object {
        val NAMED = Regex("<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)

        val PRESENT = listOf(
            "search_to_or_cc",
            "search_since",
            "search_until",
            "search_filters",
            "search_apply",
            "search_clear_all",
            "search_needs_server",
            "search_from",
            "search_subject",
            "search_date_any",
            "search_criteria_pair",
            "search_criteria_pair_date",
            // The bar's hint, which names no folder: no search was ever limited to one.
            "inbox_search",
            "search_scope_this_account",
            "search_scope_all_accounts",
        )

        val RETIRED = listOf(
            "search_title",
            "search_field_label",
            "search_idle_hint",
            "search_failed",
            "search_no_results",
            "search_no_results_generic",
            "search_advanced_toggle",
            "search_recipient",
            "search_after",
            "search_before",
            "search_button",
            "inbox_search_in",
        )

        val res: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "app/src/main/res") }
                .firstOrNull { File(it, "values/strings.xml").isFile }
                ?: error("cannot locate app/src/main/res from ${File("").absolutePath}")
        }
    }
}
