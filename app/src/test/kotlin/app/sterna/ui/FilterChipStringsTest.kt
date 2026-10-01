package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Resource lint, same instrument and disclaimer as [SelectionBannerPluralsTest]: the files are read
 * as text, nothing here resolves a string the way a device does. It pins that the bar's funnel menu
 * and the advanced search share one word per filter in every language, that the funnel's lit state
 * is spoken in every language, and that the words they replaced are gone rather than left to drift.
 */
class FilterChipStringsTest {

    @Test
    fun `every language carries the shared filter words, the funnel's state, and none of the words they replaced`() {
        val offenders = mutableListOf<String>()
        val files = localeFiles()
        assertEquals("values/ plus nine languages", 10, files.size)
        for (file in files) {
            val locale = file.parentFile.name
            val names = NAME.findAll(file.readText()).map { it.groupValues[1] }.toSet()
            SHARED.filterNot { it in names }.forEach { offenders += "$locale: missing $it" }
            RETIRED.filter { it in names }.forEach { offenders += "$locale: still carries $it" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the filtered empty state names the filters, the button clears them`() {
        val offenders = mutableListOf<String>()
        for (file in localeFiles()) {
            val body = valueOf(file, "empty_filtered_body")
            if (body == null || body.split("%1\$s").size != 2) {
                offenders += "${file.parentFile.name}: empty_filtered_body \"$body\" must hold %1\$s exactly once"
            }
        }
        assertEquals(emptyList<String>(), offenders)
        val english = File(res, "values/strings.xml")
        assertEquals(
            listOf("Unread", "Starred", "Has attachment", "No messages match: %1\$s", "Clear filters"),
            listOf("filter_unread", "filter_starred", "filter_attachment", "empty_filtered_body", "empty_unread_action")
                .map { valueOf(english, it) },
        )
        assertEquals(
            "The funnel's lit state is read aloud by this string: a key restored empty or under " +
                "another key's words would say nothing true.",
            "Filter on",
            valueOf(english, "inbox_filter_active"),
        )
    }

    private fun valueOf(file: File, key: String): String? =
        Regex("<string\\s+name=\"$key\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(file.readText())?.groupValues?.get(1)

    private fun localeFiles(): List<File> = (res.listFiles() ?: emptyArray())
        .filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) }
        .map { File(it, "strings.xml") }
        .filter { it.isFile }
        .sortedBy { it.parentFile.name }

    private companion object {
        val NAME = Regex("<string\\s+name=\"([^\"]+)\"")

        val SHARED = listOf(
            "filter_unread",
            "filter_starred",
            "filter_attachment",
            "filter_list_separator",
            "inbox_filter_active",
        )

        val RETIRED = listOf(
            "search_flagged",
            "search_has_attachment",
            "inbox_filter_flagged",
            "inbox_filter_attachment",
            "inbox_unread_only",
            "inbox_unread_only_other_filters",
        )

        val res: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "app/src/main/res") }
                .firstOrNull { File(it, "values/strings.xml").isFile }
                ?: error("cannot locate app/src/main/res from ${File("").absolutePath}")
        }
    }
}
