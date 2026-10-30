package app.sterna.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Which JMAP labels a message shows (#274), and how many of them a list row keeps, run on the
 * decisions themselves.
 */
class LabelsTest {

    @Test fun `a label keyword is shown by its id, the prefix taken off`() {
        assertEquals(listOf("red"), labelsOf(mapOf("\$label:red" to true)))
    }

    @Test fun `a label the server holds false is not shown`() {
        assertEquals(listOf("kept"), labelsOf(mapOf("\$label:gone" to false, "\$label:kept" to true)))
    }

    @Test fun `keywords that are not labels are not shown`() {
        val keywords = mapOf(
            "\$seen" to true,
            "\$junk" to true,
            "\$flagged" to true,
            "\$label" to true,
            "label:bare" to true,
            "\$label:real" to true,
        )
        assertEquals(listOf("real"), labelsOf(keywords))
    }

    @Test fun `labels keep the server's order, not an alphabetical one`() {
        val keywords = linkedMapOf(
            "\$label:zeta" to true,
            "\$seen" to true,
            "\$label:alpha" to true,
            "\$label:mid" to true,
        )
        assertEquals(listOf("zeta", "alpha", "mid"), labelsOf(keywords))
    }

    @Test fun `an id keeps its case, its spaces and its levels`() {
        val keywords = linkedMapOf(
            "\$label:Read Later" to true,
            "\$label:work/clients" to true,
            "\$label:URGENT" to true,
        )
        assertEquals(listOf("Read Later", "work/clients", "URGENT"), labelsOf(keywords))
    }

    @Test fun `a bare label keyword draws no empty chip`() {
        val keywords = linkedMapOf(
            "\$label:" to true,
            "\$label: " to true,
            "\$label:kept" to true,
        )
        assertEquals(listOf("kept"), labelsOf(keywords))
    }

    @Test fun `no keyword, no label`() {
        assertEquals(emptyList<String>(), labelsOf(emptyMap()))
    }

    @Test fun `a row shows up to two labels and counts none left`() {
        assertEquals(RowLabels(emptyList(), 0), rowLabels(emptyList()))
        assertEquals(RowLabels(listOf("a"), 0), rowLabels(listOf("a")))
        assertEquals(RowLabels(listOf("a", "b"), 0), rowLabels(listOf("a", "b")))
    }

    @Test fun `a row past two labels keeps the first two and counts the rest`() {
        assertEquals(RowLabels(listOf("a", "b"), 1), rowLabels(listOf("a", "b", "c")))
        assertEquals(RowLabels(listOf("z", "y"), 3), rowLabels(listOf("z", "y", "x", "w", "v")))
    }

    @Test fun `every language names the hidden labels by their count`() {
        val res = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/res") }
            .first { File(it, "values/strings.xml").isFile }
        val files = (res.listFiles() ?: emptyArray())
            .filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) }
            .map { File(it, "strings.xml") }
            .filter { it.isFile }
        assertEquals("values/ plus ten languages", 11, files.size)
        val offenders = files.mapNotNull { file ->
            val items = itemsOf(file, "a11y_more_labels")
            if (items.isEmpty() || "other" !in items || items.values.any { "%1\$d" !in it }) {
                "${file.parentFile?.name}: $items"
            } else {
                null
            }
        }
        assertEquals(emptyList<String>(), offenders)
        assertEquals(
            mapOf("one" to "%1\$d more label", "other" to "%1\$d more labels"),
            itemsOf(File(res, "values/strings.xml"), "a11y_more_labels"),
        )
        for (slavic in listOf("values-ru", "values-pl")) {
            assertEquals(
                "$slavic needs every form its language counts with",
                listOf("few", "many", "one", "other"),
                itemsOf(File(res, "$slavic/strings.xml"), "a11y_more_labels").keys.sorted(),
            )
        }
    }

    private fun itemsOf(file: File, key: String): Map<String, String> {
        val block = Regex("<plurals\\s+name=\"$key\"[^>]*>(.*?)</plurals>", RegexOption.DOT_MATCHES_ALL)
            .find(file.readText())?.groupValues?.get(1) ?: return emptyMap()
        return Regex("<item\\s+quantity=\"(\\w+)\">(.*?)</item>")
            .findAll(block)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }
}
