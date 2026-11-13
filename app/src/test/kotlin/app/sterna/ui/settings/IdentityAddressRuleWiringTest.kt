package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, same instrument and same disclaimer as [IdentityCreationWiringTest]. The rule itself
 * is executed in `EmailFormatTest`; this only pins that the identity rows of the account screen judge
 * their address by it (#294).
 */
class IdentityAddressRuleWiringTest {

    @Test fun `an identity address is judged by the app's shared rule`() {
        val lines = codeLines(SETTINGS_SCREEN)
        assertEquals(
            "the identity row of the account screen no longer flags its address with `isValidEmail`. " +
                "Any other rule disagrees with the composer, the connection walk and the filters: " +
                "the platform's `Patterns.EMAIL_ADDRESS` wants a dotted domain, so an identity on " +
                "`leo@yggmail` is drawn in red as malformed while the app sends from it (#294).",
            listOf("val emailInvalid = trimmedEmail.isNotBlank() && !isValidEmail(trimmedEmail)"),
            lines.filter { it.startsWith("val emailInvalid =") },
        )
        assertEquals(
            "SettingsScreen.kt judges an address by the platform's `Patterns.EMAIL_ADDRESS` again; " +
                "the app has one address rule, `isValidEmail`, and a domain of a single label " +
                "passes it (#294).",
            emptyList<String>(),
            lines.filter { "Patterns.EMAIL_ADDRESS" in it },
        )
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule is satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        private const val SETTINGS_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt"

        val SETTINGS_SCREEN: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, SETTINGS_SCREEN_PATH) }
                .firstOrNull { it.isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }
    }
}
