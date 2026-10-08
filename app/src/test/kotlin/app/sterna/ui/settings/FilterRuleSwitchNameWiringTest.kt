package app.sterna.ui.settings

import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, and a last resort: nothing in this module composes, so the accessibility tree of
 * `RuleRow` is never built here and no screen reader was listened to. What is pinned is that the
 * switch of a rule is described by the `val` the row's title draws.
 *
 * Holes of `CONTRIBUTING.md` that apply: 1 (`name` is only a name: one `val name` is counted in the
 * body, but a lambda parameter called `name` around the switch would rebind it and stay green),
 * 3 (the first `Switch(` is the one read, wherever it stands: a second one added below it, or a
 * branch drawn around it, is not seen), and 4 (the description the node ends up exposing, merged
 * or not with the clickable row, is never observed).
 */
class FilterRuleSwitchNameWiringTest {

    @Test fun `the rule's name is computed once, untitled fallback included`() {
        assertEquals(
            "RuleRow must hold its name in one val, shared by the title and the switch",
            listOf("val name = rule.name.ifBlank { stringResource(R.string.settings_filters_untitled) }"),
            body().filter { it.startsWith("val name") },
        )
    }

    @Test fun `the title draws that name`() {
        assertEquals(
            listOf("Text(name, style = MaterialTheme.typography.bodyLarge)"),
            body().filter { it.startsWith("Text(name") },
        )
    }

    @Test fun `the switch is described by that same name`() {
        val lines = body()
        val at = lines.indexOfFirst { it.startsWith("Switch(") }
        assertEquals(
            "the Switch of RuleRow is pinned whole, line by line: without the content description " +
                "its node has no name of its own, only a role and a state",
            EXPECTED_SWITCH,
            if (at < 0) emptyList() else lines.subList(at, minOf(at + EXPECTED_SWITCH.size, lines.size)),
        )
    }

    private fun body(): List<String> = DeclarationSource.functionBody(
        DeclarationSource.file("app/src/main/kotlin/app/sterna/ui/settings/FiltersScreen.kt"),
        "private fun RuleRow(",
    )

    private companion object {
        val EXPECTED_SWITCH = listOf(
            "Switch(",
            "checked = rule.enabled,",
            "onCheckedChange = onToggle,",
            "modifier = Modifier.semantics { contentDescription = name },",
            "enabled = switchEnabled,",
            ")",
        )
    }
}
