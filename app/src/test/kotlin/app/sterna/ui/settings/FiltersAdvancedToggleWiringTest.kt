package app.sterna.ui.settings

import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, and a last resort: nothing in this module composes, so the "Advanced" header of the
 * filters screen is never drawn here and no screen reader was listened to. What is pinned is that
 * the row a tap folds and unfolds says it is a button and whether it is open, in the words of the
 * `open` it draws its chevron from, while the chevron stays unnamed and adds nothing to read.
 *
 * Holes of `CONTRIBUTING.md` that apply: 1 (`open` is only a name: nothing here proves the caller
 * hands in the flag that `onToggle` flips), 3 (the body is pinned whole down to `if (open)
 * content()`, but a second caller drawing its own header is not seen), and 4 (what the row's node
 * ends up exposing is never observed: that the `semantics` block lands on the node `clickable`
 * makes is the library's, read and not run).
 */
class FiltersAdvancedToggleWiringTest {

    private val file = DeclarationSource.file("app/src/main/kotlin/app/sterna/ui/settings/FiltersScreen.kt")

    @Test fun `the Advanced header says it is a button and whether it is open`() {
        assertEquals(
            "without a role the header is read as plain text, and without a state nothing says " +
                "whether the section below it is shown",
            listOf(
                "private fun AdvancedSection(open: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {",
                "val state = stringResource(if (open) R.string.a11y_conversation_expanded else R.string.a11y_conversation_collapsed)",
                "Column(Modifier.fillMaxWidth()) {",
                "Row(",
                "modifier = Modifier",
                ".fillMaxWidth()",
                ".clickable(onClick = onToggle)",
                ".semantics {",
                "role = Role.Button",
                "stateDescription = state",
                "}",
                ".padding(horizontal = 16.dp, vertical = 12.dp),",
                "verticalAlignment = Alignment.CenterVertically,",
                ") {",
                "Text(",
                "stringResource(R.string.settings_filters_advanced),",
                "style = MaterialTheme.typography.labelLarge,",
                "color = MaterialTheme.colorScheme.primary,",
                "modifier = Modifier.weight(1f),",
                ")",
                "Icon(",
                "if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,",
                "contentDescription = null,",
                "tint = MaterialTheme.colorScheme.primary,",
                ")",
                "}",
                "if (open) content()",
                "}",
            ),
            DeclarationSource.functionBody(file, "private fun AdvancedSection("),
        )
    }

    @Test fun `role and state are the semantics properties`() {
        val imports = DeclarationSource.kotlinLines(file).filter { it.startsWith("import androidx.compose.ui.semantics.") }
        assertEquals(
            listOf(
                "import androidx.compose.ui.semantics.Role",
                "import androidx.compose.ui.semantics.contentDescription",
                "import androidx.compose.ui.semantics.role",
                "import androidx.compose.ui.semantics.semantics",
                "import androidx.compose.ui.semantics.stateDescription",
            ),
            imports,
        )
    }
}
