package app.sterna.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test: it reads `ConnectScreen.kt` as text. Nothing in this module can
 * run a composable, so what is pinned is the shape every password eye must have for TalkBack to say
 * what the button does instead of a bare "button".
 */
class PasswordEyeNamedWiringTest {

    @Test fun `every password eye on the connect screen is named after what it will do`() {
        val lines = codeLines()
        val eyes = lines.indices.filter { lines[it].contains(EYE_ICON) }
        assertTrue(
            "expected at least the two password fields of ConnectScreen.kt (credentials pane and " +
                "import sign-in) to show an eye icon, found ${eyes.size}: if the icon changed, this " +
                "lint must be taught the new one rather than left green over nothing",
            eyes.size >= 2,
        )
        val mismatches = eyes.flatMap { at ->
            val condition = CONDITION.find(lines[at])?.groupValues?.get(1)
                ?: return@flatMap listOf(
                    "eye at code line ${at + 1} is not 'if (<shown>) Icons.Filled.VisibilityOff else " +
                        "Icons.Filled.Visibility,': '${lines[at]}'",
                )
            val expected = listOf(
                "contentDescription = stringResource(",
                "if ($condition) R.string.connect_password_hide else R.string.connect_password_show,",
                "),",
            )
            expected.indices.mapNotNull { i ->
                val actual = lines.getOrNull(at + 1 + i)
                if (actual == expected[i]) null
                else "eye driven by '$condition': expected '${expected[i]}' but found '$actual'"
            }
        }
        assertEquals(
            "every eye icon in ConnectScreen.kt must be named with connect_password_hide / " +
                "connect_password_show, chosen by the SAME condition that picks the icon. With " +
                "'contentDescription = null' TalkBack announces an unnamed 'button' on a password " +
                "field; with the condition swapped it announces the opposite of what the tap does. " +
                "Mismatches:\n" + mismatches.joinToString("\n"),
            emptyList<String>(),
            mismatches,
        )
    }

    private fun codeLines(): List<String> =
        CONNECT_SCREEN.readLines().map { it.trim() }.filterNot {
            it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") ||
                it.startsWith("import ")
        }

    private companion object {
        const val EYE_ICON = "Icons.Filled.Visibility"
        val CONDITION = Regex(
            """^if \((\w+)\) Icons\.Filled\.VisibilityOff else Icons\.Filled\.Visibility,$""",
        )

        private const val CONNECT_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/connect/ConnectScreen.kt"

        val CONNECT_SCREEN: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, CONNECT_SCREEN_PATH) }
                .firstOrNull { it.isFile }
                ?: error("cannot locate $CONNECT_SCREEN_PATH from ${File("").absolutePath}")
        }
    }
}
