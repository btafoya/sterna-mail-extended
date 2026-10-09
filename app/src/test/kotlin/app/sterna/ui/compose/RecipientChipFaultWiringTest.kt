package app.sterna.ui.compose

import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * Source lint, and a last resort: nothing in this module composes, so no recipient chip is ever
 * drawn here and no screen reader was listened to. What is pinned is that the fault deciding a
 * chip's error colours is the one whose words are set as the `error` of the chip's own node, and
 * that `error` is the semantics property. Which fault a chip has is executed in
 * `RecipientChipFaultTest`.
 *
 * Holes of `CONTRIBUTING.md` that apply: 1 (`chip` and `missingKey` are only names: nothing here
 * proves the lookup handed in is the one the encryption mode feeds), 3 (the lines are found inside
 * the one `InputChip(` call, but a branch drawn around that call is not seen), and 4 (what the
 * chip's node ends up exposing is never observed: that a screen reader speaks the `error` of a
 * node that is not a text field is the platform's, read and not run).
 */
class RecipientChipFaultWiringTest {

    private val lines: List<String> by lazy {
        DeclarationSource.codeLines(
            DeclarationSource.file("app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"),
        )
    }

    private val chip: List<String> by lazy {
        assertEquals("the recipient chip is the screen's only `InputChip(`", 1, lines.count { it == "InputChip(" })
        val call = mutableListOf<String>()
        var depth = 0
        for (line in lines.drop(lines.indexOf("InputChip("))) {
            call += line
            depth += line.count { it == '(' } - line.count { it == ')' }
            if (depth == 0) break
        }
        call
    }

    @Test fun `a chip's fault is decided once, and its words are read from that fault`() {
        assertEquals(
            listOf(
                "val fault = recipientChipFault(chip, missingKey)",
                "val faultSpoken = if (fault == null) null else stringResource(fault.message)",
                "if (faultSpoken != null) error(faultSpoken)",
                "colors = if (fault == null) {",
            ),
            lines.filter { Regex("""\bfault(Spoken)?\b""").containsMatchIn(it) },
        )
        assertEquals(
            "the two declarations sit right above the chip they describe",
            lines.indexOf("InputChip("),
            lines.indexOf("val faultSpoken = if (fault == null) null else stringResource(fault.message)") + 3,
        )
    }

    @Test fun `the chip's own node says the fault as its error`() {
        assertTrue(
            "the chip's modifier must set the error next to the chip's name, pinned whole:\n" +
                chip.joinToString("\n"),
            Collections.indexOfSubList(
                chip,
                listOf(
                    "modifier = Modifier.semantics {",
                    "if (spoken != null) contentDescription = spoken",
                    "if (faultSpoken != null) error(faultSpoken)",
                    "},",
                ),
            ) >= 0,
        )
    }

    @Test fun `error is the semantics property, not the standard library's throw`() {
        assertEquals(
            "without this import `error(faultSpoken)` still compiles, as `kotlin.error`, and a " +
                "flagged chip throws when its semantics are read",
            1,
            lines.count { it == "import androidx.compose.ui.semantics.error" },
        )
    }

    @Test fun `the error colours follow the same fault`() {
        assertTrue(
            "the chip's colours are pinned whole:\n" + chip.joinToString("\n"),
            Collections.indexOfSubList(
                chip,
                listOf(
                    "colors = if (fault == null) {",
                    "InputChipDefaults.inputChipColors()",
                    "} else {",
                    "InputChipDefaults.inputChipColors(",
                    "containerColor = MaterialTheme.colorScheme.errorContainer,",
                    "labelColor = MaterialTheme.colorScheme.onErrorContainer,",
                    "trailingIconColor = MaterialTheme.colorScheme.onErrorContainer,",
                    ")",
                    "},",
                ),
            ) >= 0,
        )
    }
}
