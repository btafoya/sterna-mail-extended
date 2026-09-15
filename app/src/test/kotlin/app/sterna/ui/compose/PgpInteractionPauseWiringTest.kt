package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test. It reads `ComposeScreen.kt` and `ComposeViewModel.kt` as text
 * and compares whole trimmed lines: raw for the effect, so a comment or a blank slipped between its
 * two calls turns it red, and code lines only elsewhere. It proves nothing about what runs: neither
 * that the effect is cancelled by the recomposition the consumption triggers, nor that the
 * provider's screen opens. A launch under another name than `pgpLauncher(`, an alias or `.invoke`,
 * escapes the second test. `PgpInteractionPauseTest` executes the decision `composeStateAfterPgpLaunch`.
 */
class PgpInteractionPauseWiringTest {

    @Test fun `the compose screen consumes the pause, then launches it, with nothing in between`() {
        // Without the consumption, the pause outlives the screen and a rotation under the
        // provider's screen launches it again on the new one. Writing Idle recomposes and cancels this effect:
        // anything that suspends between the two lines lets that cancellation land before the launch.
        assertEquals(
            "the composer's PGP effect is no longer exactly consumePgpInteraction followed by the launch.",
            listOf(
                "LaunchedEffect(state) {",
                "(state as? ComposeState.PgpInteraction)?.let {",
                "viewModel.consumePgpInteraction(it)",
                "pgpLauncher(it.pendingIntent)",
            ),
            block(raw(SCREEN), SCREEN, "(state as? ComposeState.PgpInteraction)?.let", before = 1, count = 3),
        )
    }

    @Test fun `the compose screen launches the provider from that one place only`() {
        assertEquals(
            "pgpLauncher is called somewhere else in ComposeScreen, where nothing consumes the pause first.",
            listOf("pgpLauncher(it.pendingIntent)"),
            code(SCREEN).filter { it.contains("pgpLauncher(") },
        )
    }

    @Test fun `the view model consumes the pause through the pure decision`() {
        // One line and nothing else: `pendingSendArgs` must survive the consumption, it is what
        // `retryPgpSend` resumes from when the provider answers.
        assertEquals(
            "consumePgpInteraction no longer applies composeStateAfterPgpLaunch to the state in one update.",
            listOf(
                "fun consumePgpInteraction(pause: ComposeState.PgpInteraction) {",
                "_state.update { composeStateAfterPgpLaunch(it, pause) }",
                "}",
            ),
            block(code(VIEW_MODEL), VIEW_MODEL, "fun consumePgpInteraction(", before = 0, count = 3),
        )
    }

    /** Every line of [path], trimmed, blanks and comments kept. */
    private fun raw(path: String): List<String> = File(root, path).readLines().map { it.trim() }

    /** Code lines of [path], trimmed; comment lines and blanks dropped. */
    private fun code(path: String): List<String> = raw(path)
        .filter {
            it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*")
        }

    /** [before] of [lines] above the single one starting with [prefix], then [count] from it. */
    private fun block(lines: List<String>, path: String, prefix: String, before: Int, count: Int): List<String> {
        val starts = lines.indices.filter { lines[it].startsWith(prefix) }
        val start = starts.singleOrNull()
            ?: return listOf("«${starts.size} lines of $path start with `$prefix`»")
        return lines.subList(maxOf(start - before, 0), minOf(start + count, lines.size))
    }

    private companion object {
        const val VIEW_MODEL = "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"
        const val SCREEN = "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, VIEW_MODEL).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }
    }
}
