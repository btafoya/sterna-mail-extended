package app.sterna.ui.components

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import app.sterna.R
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The head of a message row shows a check once the row is selected. The face and its colours are
 * executed; the composable and the row are not instantiable here, so the two lints at the bottom
 * pin, line for line, that the row hands `selected` over and that the composable obeys the face.
 */
class RowMonogramTest {

    @Test fun `a selected row shows the check`() {
        assertEquals(RowMonogramFace.CHECK, rowMonogramFace(selected = true))
    }

    @Test fun `an unselected row shows the initial`() {
        assertEquals(RowMonogramFace.INITIAL, rowMonogramFace(selected = false))
    }

    @Test fun `an unselected head is announced as Select`() {
        assertEquals(R.string.a11y_select, rowMonogramLabel(selected = false))
    }

    @Test fun `a selected head is announced as Deselect`() {
        assertEquals(R.string.a11y_deselect, rowMonogramLabel(selected = true))
    }

    @Test fun `the check is drawn in primary and onPrimary`() {
        val primary = Color(0xFF004444)
        val onPrimary = Color(0xFFEEFFFF)
        val scheme = lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            secondaryContainer = Color(0xFF002200),
            primaryContainer = Color(0xFF550055),
        )
        assertEquals(ToneRamp(primary, onPrimary), checkedMonogramColors(scheme))
    }

    @Test fun `the row hands its selection and its tap to the head, and to nothing else`() {
        val row = codeLines(EMAIL_LIST_ITEM)
        assertEquals(
            "EmailListItem.kt must call 'RowMonogram(' exactly once (inside the LocalListMonogram " +
                "guard, pinned by ListMonogramWiringTest) and never 'Monogram(' directly: a bare " +
                "Monogram never shows the check, and one outside the guard ignores the setting.",
            listOf("RowMonogram("),
            row.filter { Regex("""\bMonogram\(|RowMonogram\(""").containsMatchIn(it) },
        )
        val at = row.indexOf("RowMonogram(")
        val call = row.drop(at).takeWhile { it != ")" }
        assertEquals(
            "the head must receive the row's own selection and the initials callback, whole lines",
            listOf("selected = selected,", "onClick = onMonogramClick,"),
            call.filter { it.startsWith("selected =") || it.startsWith("onClick =") },
        )
    }

    @Test fun `the head draws the check under the CHECK face and the monogram otherwise`() {
        val head = codeLines(ROW_MONOGRAM)
        val at = head.indexOf("when (rowMonogramFace(selected)) {")
        check(at >= 0) { "RowMonogram.kt no longer branches on rowMonogramFace(selected)" }
        val expected = listOf(
            "when (rowMonogramFace(selected)) {",
            "RowMonogramFace.INITIAL -> Monogram(seed = seed, label = label, modifier = tap)",
            "RowMonogramFace.CHECK -> {",
            "val colors = checkedMonogramColors(MaterialTheme.colorScheme)",
            "Box(",
            "modifier = tap",
            ".size(MonogramSize)",
            ".clip(CircleShape)",
            ".background(colors.container),",
            "contentAlignment = Alignment.Center,",
            ") {",
            "Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onContainer)",
        )
        assertEquals(expected, head.subList(at, minOf(at + expected.size, head.size)))
    }

    @Test fun `the clickable head is announced as Select or Deselect, in every language`() {
        val files = File(root, "app/src/main/res").listFiles { f -> f.name.startsWith("values") }!!
            .map { File(it, "strings.xml") }
            .filter { it.isFile && it.readText().contains("name=\"a11y_favourite\"") }
        assertEquals("the ten languages carrying the star's label", 10, files.size)
        val missing = files.associate { file ->
            file.parentFile.name to listOf("a11y_select", "a11y_deselect")
                .filterNot { file.readText().contains("<string name=\"$it\">") }
        }.filterValues { it.isNotEmpty() }
        assertEquals("the head's labels are missing from", emptyMap<String, List<String>>(), missing)
    }

    @Test fun `the tap carries the state label, a button role and the 48 dp target`() {
        val head = codeLines(ROW_MONOGRAM)
        val at = head.indexOf("val tap = onClick?.let {")
        check(at >= 0) { "RowMonogram.kt no longer builds its tap from onClick" }
        val expected = listOf(
            "val tap = onClick?.let {",
            "Modifier",
            ".touchTargetOverflow(MonogramTouchOverflow)",
            ".clip(CircleShape)",
            ".clickable(onClick = it)",
            ".clearAndSetSemantics {",
            "contentDescription = tapLabel",
            "role = Role.Button",
            "}",
            ".padding(MonogramTouchOverflow)",
            "} ?: Modifier",
        )
        assertEquals(expected, head.subList(at, minOf(at + expected.size, head.size)))
        assertEquals(
            "the label must follow the row's own selection",
            1,
            head.count { it == "val tapLabel = stringResource(rowMonogramLabel(selected))" },
        )
    }

    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/kotlin/app/sterna/ui/components/RowMonogram.kt").isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }
        val EMAIL_LIST_ITEM: File by lazy { File(root, "app/src/main/kotlin/app/sterna/ui/components/EmailListItem.kt") }
        val ROW_MONOGRAM: File by lazy { File(root, "app/src/main/kotlin/app/sterna/ui/components/RowMonogram.kt") }
    }
}
