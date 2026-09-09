package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint over the UnifiedPush picker in `SternaApp.kt`, read as text. Nothing here runs:
 * `DistributorPickerDialog` is a `@Composable` reaching `Application.container`, and the gestures
 * it wires are lambdas inside an `AlertDialog`. The decision underneath is executed, in
 * [app.sterna.push.RelayPushTest]: a refusal that was stored answers `STAY` on every later pass,
 * one that was not answers `ASK` again, which is what makes the difference between these two
 * gestures the whole subject.
 *
 * What it does not cover: that the dialog is reachable on screen, that the system back gesture
 * really reaches `onDismissRequest` on this Compose version, and what the manager then writes
 * (`RelayWiringTest` pins the two bodies).
 */
class DistributorPickerGesturesLintTest {

    @Test fun `the three ways out of the picker are wired in this order`() {
        val found = dialogBody().filter { line -> EXITS.any { it in line } }
        assertEquals(
            "the picker's three exits are pinned whole and in order. The back gesture and a tap " +
                "outside both land on onDismissRequest, and neither is an answer: wire " +
                "rememberDistributorRefusal there and closing the dialog by hand writes the " +
                "device-wide refusal, so UnifiedPush is shut for every account until somebody " +
                "guesses at turning an account's sync off and back on. The Cancel button is the " +
                "answer, and it is the only exit that may name the write. Order is compared, not " +
                "presence: swap the two lambdas and every one of these lines is still in the " +
                "file, wired to the other gesture. Lines found:\n" + found.joinToString("\n"),
            listOf(
                "onDismissRequest = { manager.hideDistributorChoiceForNow() },",
                "androidx.compose.material3.TextButton(onClick = { manager.distributorChosen(pkg) }) {",
                "androidx.compose.material3.TextButton(onClick = { manager.rememberDistributorRefusal() }) {",
            ),
            found,
        )
    }

    @Test fun `each gesture is named on its declaration and its one wire`() {
        val found = mainSources().flatMap { file ->
            file.readLines().map { it.trim() }
                .filter { line -> GESTURES.any { it in line } && !line.startsWith("//") }
                .map { "${file.name}: $it" }
        }.sorted()
        assertEquals(
            "each of the two ways out may be named on exactly two lines of src/main: its " +
                "declaration, and the one gesture that calls it. A second call site for " +
                "rememberDistributorRefusal on any replayed level writes the refusal without " +
                "anybody answering, which is the regression this pair was split to end; a second " +
                "one for hideDistributorChoiceForNow closes the dialog on a path that has no " +
                "gesture behind it. Lines found:\n" + found.joinToString("\n"),
            listOf(
                "SternaApp.kt: androidx.compose.material3.TextButton(onClick = " +
                    "{ manager.rememberDistributorRefusal() }) {",
                "SternaApp.kt: onDismissRequest = { manager.hideDistributorChoiceForNow() },",
                "UnifiedPushManager.kt: fun hideDistributorChoiceForNow() {",
                "UnifiedPushManager.kt: fun rememberDistributorRefusal() {",
            ),
            found,
        )
    }

    // -- reading the source ----------------------------------------------------------------------

    /** Trimmed body of `DistributorPickerDialog`, comment-only lines dropped. */
    private fun dialogBody(): List<String> {
        val lines = File(root, APP_PATH).readLines().map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        val at = lines.indexOfFirst { it == OPENER }
        check(at >= 0) {
            "no '$OPENER' in SternaApp.kt — the picker was renamed or reshaped, and this lint " +
                "must be taught the new shape rather than left green over code it never read"
        }
        var depth = lines[at].count { it == '{' } - lines[at].count { it == '}' }
        val out = mutableListOf<String>()
        for (line in lines.drop(at + 1)) {
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth <= 0) return out
            out += line
        }
        error("'$OPENER' is never closed in SternaApp.kt")
    }

    private fun mainSources(): List<File> =
        File(root, MAIN).walkTopDown().filter { it.extension == "kt" }.toList()

    private companion object {
        const val APP_PATH = "app/src/main/kotlin/app/sterna/ui/SternaApp.kt"
        const val MAIN = "app/src/main/kotlin"
        const val OPENER = "private fun DistributorPickerDialog() {"

        /** The three exits, as the substrings that tell them apart. */
        val EXITS = listOf(
            "hideDistributorChoiceForNow",
            "distributorChosen",
            "rememberDistributorRefusal",
        )

        val GESTURES = listOf("hideDistributorChoiceForNow", "rememberDistributorRefusal")

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, APP_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "sources as text and needs a working directory inside the checkout",
                )
        }
    }
}
