package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test: it reads `SettingsComponents.kt` as text and proves nothing
 * about the accessibility tree. This module has no Robolectric, no `compose-ui-test` and no
 * `androidTest`, so the semantics of `SettingSwitch` cannot be executed here; what is pinned is the
 * shape that makes TalkBack read the row as ONE switch named by its title and subtitle.
 */
class SettingSwitchAccessibilityWiringTest {

    @Test fun `the whole row is the toggle, placed before the padding`() {
        val expected = listOf(
            "Row(",
            "modifier = Modifier",
            ".fillMaxWidth()",
            ".toggleable(",
            "value = checked,",
            "enabled = enabled,",
            "role = Role.Switch,",
            "onValueChange = onCheckedChange,",
            ")",
            ".padding(horizontal = 16.dp, vertical = 12.dp),",
        )
        val lines = body()
        val found = lines.take(expected.size)
        val mismatches = expected.indices.mapNotNull { i ->
            if (found.getOrNull(i) == expected[i]) null
            else "line ${i + 1} of the row: expected '${expected[i]}' but found '${found.getOrNull(i)}'"
        }
        assertEquals(
            "SettingSwitch's Row must carry the toggle itself — value, enabled, Role.Switch and the " +
                "caller's callback, the arguments included — and BEFORE the padding, so the whole " +
                "line (padding included) is the touch target. Without it TalkBack stops on the " +
                "title, the subtitle and an unnamed 'Switch, off', and tapping the title does " +
                "nothing. Mismatches:\n" + mismatches.joinToString("\n"),
            emptyList<String>(),
            mismatches,
        )
    }

    @Test fun `the switch only draws, the row owns the gesture, and it keeps its 48 dp`() {
        val lines = body()
        val at = lines.indexOfFirst { it == "Switch(" }
        assertEquals(
            "SettingSwitch must call its Switch exactly once, as a multi-line 'Switch(' block",
            1,
            lines.count { it.startsWith("Switch(") },
        )
        assertEquals(
            "the Switch inside SettingSwitch is pinned WHOLE, line by line. A non-null " +
                "onCheckedChange makes it a second clickable node: TalkBack reads it apart from the " +
                "row, unnamed, and one tap toggles twice. minimumInteractiveComponentSize holds the " +
                "row height it had while the Switch was interactive: a Switch without a callback " +
                "drops its 48 dp and every settings row shrinks.",
            EXPECTED_SWITCH,
            lines.subList(at, minOf(at + EXPECTED_SWITCH.size, lines.size)),
        )
    }

    /** The insertion screen: nothing else in the row may take a gesture from the merged node. */
    @Test fun `nothing else in the row is clickable`() {
        val offenders = body().filter { line -> GESTURES.any { it in line } }
        assertEquals(
            "the only gesture in SettingSwitch is the row's toggleable, and the Switch's callback " +
                "is null; found:\n" + offenders.joinToString("\n"),
            listOf(".toggleable(", "onCheckedChange = null,"),
            offenders,
        )
    }

    private fun body(): List<String> {
        val lines = codeLines()
        val at = lines.indexOfFirst { it == "fun SettingSwitch(" }
        check(at >= 0) {
            "no 'fun SettingSwitch(' in SettingsComponents.kt — the composable was renamed or " +
                "reshaped, and this lint must be taught the new shape rather than left green"
        }
        val open = at + lines.drop(at).indexOfFirst { it == ") {" }
        check(open > at) { "no signature end ') {' after 'fun SettingSwitch(' " }
        var depth = 1
        val result = mutableListOf<String>()
        for (line in lines.drop(open + 1)) {
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth <= 0) return result
            result += line
        }
        error("'fun SettingSwitch(' is never closed in SettingsComponents.kt")
    }

    private fun codeLines(): List<String> =
        SETTINGS_COMPONENTS.readLines().map { it.trim() }.filterNot {
            it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
        }

    private companion object {
        val EXPECTED_SWITCH = listOf(
            "Switch(",
            "checked = checked,",
            "onCheckedChange = null,",
            "modifier = Modifier.minimumInteractiveComponentSize(),",
            "enabled = enabled,",
            ")",
        )
        val GESTURES = listOf(
            "clickable", "toggleable", "selectable", "pointerInput", "onClick", "onCheckedChange =",
        )

        private const val SETTINGS_COMPONENTS_PATH =
            "app/src/main/kotlin/app/sterna/ui/settings/SettingsComponents.kt"

        val SETTINGS_COMPONENTS: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, SETTINGS_COMPONENTS_PATH) }
                .firstOrNull { it.isFile }
                ?: error("cannot locate $SETTINGS_COMPONENTS_PATH from ${File("").absolutePath}")
        }
    }
}
