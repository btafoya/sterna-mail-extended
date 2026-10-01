package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, the last resort: the composer screen is not instantiable on the JVM. It pins, as whole
 * lines, every line of `ComposeScreen.kt` that touches the name book or a value read from it, so the
 * book can only reach a chip's label, its spoken description and the remove button's label, and
 * nothing that sends, saves, parses or checks an address. What those values are is executed in
 * `RecipientNamesTest`.
 *
 * Holes that apply: (1) the lines say which names are bound, not that `chipLabel` is the one from
 * `RecipientNames.kt`; (2) nothing here sees the expression wrapped around a pinned line; (3) a pinned
 * line says nothing about where it sits in the chip loop or in what order it runs; (4) nothing here
 * holds the map the screen actually composes with, nor measures the remove button once laid out.
 */
class RecipientNamesWiringTest {

    private val screen: List<String> by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
            ?: error("cannot locate the checkout from ${File("").absolutePath}")
        File(root, "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt").readLines()
            .map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") || it.isEmpty() }
    }

    private fun linesNaming(code: List<String>, name: String): List<String> =
        code.filter { Regex("""\b$name\b""").containsMatchIn(it) }

    private fun block(code: List<String>, start: String): List<String> {
        val at = code.indexOfFirst { it.startsWith(start) }
        check(at >= 0) { "no line starts with '$start' — did it get renamed?" }
        var depth = 0
        var opened = false
        for (i in at until code.size) {
            depth += code[i].count { it == '{' } - code[i].count { it == '}' }
            opened = opened || '{' in code[i]
            if (opened && depth == 0) return code.subList(at, i + 1)
        }
        error("'$start' never closes")
    }

    /** The signature, whose defaults hold braces of their own, then the body from its `) {`. */
    private val field: List<String> by lazy {
        val at = screen.indexOfFirst { it.startsWith("private fun RecipientChipsField(") }
        check(at >= 0) { "RecipientChipsField is gone — did it get renamed?" }
        val body = (at until screen.size).first { screen[it] == ") {" }
        screen.subList(at, body) + block(screen.subList(body, screen.size), ") {")
    }

    @Test fun `the screen's book is written by a pick and handed to the three fields, nowhere else`() {
        assertEquals(
            listOf(
                "var recipientNames by rememberSaveable(stateSaver = recipientNamesSaver) { mutableStateOf(emptyMap<String, String>()) }",
                "val namePicked: (ContactSuggestion) -> Unit = { recipientNames = recipientNamesWithPick(recipientNames, it) }",
                "names = recipientNames,",
                "names = recipientNames, onNamePicked = namePicked,",
                "names = recipientNames, onNamePicked = namePicked,",
            ),
            linesNaming(screen, "recipientNames"),
        )
        assertEquals(
            listOf(
                "val namePicked: (ContactSuggestion) -> Unit = { recipientNames = recipientNamesWithPick(recipientNames, it) }",
                "onNamePicked = namePicked,",
                "names = recipientNames, onNamePicked = namePicked,",
                "names = recipientNames, onNamePicked = namePicked,",
            ),
            linesNaming(screen, "namePicked"),
        )
    }

    @Test fun `inside a field the book only names what is shown and spoken`() {
        assertEquals(
            listOf(
                "names: Map<String, String>,",
                "chipLabel(chips.first(), names),",
                "val shown = chipLabel(chip, names)",
                "val spoken = chipDescription(chip, names)",
            ),
            linesNaming(field, "names"),
        )
        assertEquals(
            listOf(
                "val shown = chipLabel(chip, names)",
                "label = { Text(shown, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },",
                "val removeLabel = stringResource(R.string.compose_remove_recipient, shown)",
            ),
            linesNaming(field, "shown"),
        )
        assertEquals(
            listOf(
                "val spoken = chipDescription(chip, names)",
                "modifier = if (spoken == null) Modifier else Modifier.semantics { contentDescription = spoken },",
            ),
            linesNaming(field, "spoken"),
        )
        assertEquals(
            listOf("onNamePicked: (ContactSuggestion) -> Unit,", "onNamePicked(contact)"),
            linesNaming(field, "onNamePicked"),
        )
        assertEquals(1, screen.count { it == "chipLabel(chips.first(), names)," })
    }

    @Test fun `the remove button is at least 48dp wide, as tall as the chip, and says whom it removes`() {
        assertEquals(
            listOf(
                "trailingIcon = {",
                "val removeLabel = stringResource(R.string.compose_remove_recipient, shown)",
                "Box(",
                "contentAlignment = Alignment.Center,",
                "modifier = Modifier",
                ".widthIn(min = 48.dp)",
                ".height(InputChipDefaults.Height)",
                ".clickable(role = Role.Button) { onValueChange(rebuild(chips.filterIndexed { i, _ -> i != index }, input)) }",
                ".semantics { contentDescription = removeLabel },",
                ") {",
                "Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))",
                "}",
                "},",
            ),
            block(field, "trailingIcon = {"),
        )
        assertTrue(field.none { "R.string.compose_remove)" in it })
    }
}
