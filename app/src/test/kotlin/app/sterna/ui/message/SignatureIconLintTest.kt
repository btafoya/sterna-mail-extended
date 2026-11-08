package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, beside [SignatureLookTest] which runs the table: `SignatureIcon.vector()` is a private
 * extension of the screen and returns an `ImageVector`, which no JVM test here can draw or compare.
 * What this pins is the vector each seal is drawn with: a seal that is not a verdict of trust, `MAYBE`,
 * is an outline, so it cannot be read at a glance as the filled seal of a confirmed sender.
 * Whole trimmed lines, never `contains`: a fragment rule is blind to anything appended to a line.
 *
 * The same goes for `SignatureTone.color()`, which reads `MaterialTheme` and so cannot run here: its four
 * branches are pinned in order as the whole body of that one declaration, so a tone repainted in another
 * colour, or a branch moved out of it, goes red.
 *
 * Holes it leaves open: the icon lines say nothing about which `when` they sit in, and neither rule says
 * every caller goes through `vector()` and `color()` (hole 3); nothing here holds the vector or the colour
 * actually drawn, nor what the theme's `onSurfaceVariant` looks like (hole 4).
 */
class SignatureIconLintTest {

    @Test fun `only the uncertain seal is drawn as an outline`() {
        assertEquals(
            "SignatureIcon must map VERIFIED and BAD to filled vectors and MAYBE to the outlined " +
                "GppMaybe. Lines found:",
            listOf(
                "SignatureIcon.VERIFIED -> Icons.Filled.VerifiedUser",
                "SignatureIcon.MAYBE -> Icons.Outlined.GppMaybe",
                "SignatureIcon.BAD -> Icons.Filled.GppBad",
            ),
            codeLines(read(SCREEN)).filter { it.startsWith("SignatureIcon.") },
        )
    }

    @Test fun `each tone is painted in its own theme colour`() {
        val lines = codeLines(read(SCREEN))
        val header = "private fun SignatureTone.color(): Color = when (this) {"
        assertEquals("`$header` must be declared once. Lines found:", listOf(header), lines.filter { it == header })
        val start = lines.indexOf(header)
        val end = (start until lines.size).first { lines[it] == "}" }
        assertEquals(
            "SignatureTone.color() must paint PRIMARY, TERTIARY, MUTED and ERROR in the theme's primary, " +
                "tertiary, onSurfaceVariant and error, in that order and nothing else. Body found:",
            listOf(
                "SignatureTone.PRIMARY -> MaterialTheme.colorScheme.primary",
                "SignatureTone.TERTIARY -> MaterialTheme.colorScheme.tertiary",
                "SignatureTone.MUTED -> MaterialTheme.colorScheme.onSurfaceVariant",
                "SignatureTone.ERROR -> MaterialTheme.colorScheme.error",
            ),
            lines.subList(start + 1, end),
        )
    }

    private fun codeLines(text: String): List<String> = text.lines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private fun read(relative: String): String {
        val fromModule = relative.removePrefix("app/")
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it.readText() }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it.readText() }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${File("").absolutePath}")
    }

    private companion object {
        const val SCREEN = "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt"
    }
}
