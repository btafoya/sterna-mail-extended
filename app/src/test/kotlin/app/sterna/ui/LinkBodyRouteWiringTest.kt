package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test. The route is built and the body handed over by functions
 * [ComposeLinkRouteTest] and `LinkBodyHandOffTest` execute; what joins them runs in composables and
 * a ViewModel this module cannot execute, so it is read here as text.
 *
 * What it cannot see, of the four holes in CONTRIBUTING.md:
 *  1. `bodyToken` named in a pinned line says nothing about the value bound to it elsewhere.
 *  2. Nothing is checked around a pinned call: `composeLinkRoute(m, bodyToken)` wrapped in a
 *     condition that skips it reads the same.
 *  3. Pinned lines say nothing of order or position: a deposit placed after the navigation, or the
 *     claim moved into a branch that never runs, passes.
 *  4. Nothing here holds what the composer actually receives.
 */
class LinkBodyRouteWiringTest {

    @Test fun `the compose destination declares no body argument`() {
        val offenders = codeLines(STERNA_APP).filter {
            BODY_ARGUMENT.containsMatchIn(it) || BODY_PLACEHOLDER.containsMatchIn(it)
        }
        assertEquals(
            "$STERNA_APP must not declare a `body` navigation argument: a body in the route is what " +
                "a ~100 kB share turns into a crash, and it is parcelled with the back stack. Found:\n" +
                offenders.joinToString("\n") { "    $it" },
            emptyList<String>(), offenders,
        )
    }

    @Test fun `no source that builds a compose route puts a body into it`() {
        val offenders = routeBuilders().flatMap { file ->
            codeLines(file).filter { BODY_ENCODED.containsMatchIn(it) || BODY_QUERY.containsMatchIn(it) }
                .map { "${file.substringAfterLast('/')}: $it" }
        }
        assertEquals(
            "a compose route must carry the body's token, never the body. Found:\n" +
                offenders.joinToString("\n") { "    $it" },
            emptyList<String>(), offenders,
        )
    }

    @Test fun `the host parks the body and navigates on its token`() {
        assertPinned(STERNA_APP, "val bodyToken = app.container.linkBodies.put(m.body)")
        assertPinned(STERNA_APP, "nav.navigate(composeLinkRoute(m, bodyToken) { Uri.encode(it) })")
    }

    @Test fun `the compose destination reads the token back and hands it to the composer`() {
        assertPinned(
            STERNA_APP,
            "\"&to={to}&cc={cc}&bcc={bcc}&subject={subject}&bodyToken={bodyToken}&draftId={draftId}&outboxId={outboxId}\",",
        )
        assertPinned(
            STERNA_APP,
            "navArgument(\"bodyToken\") { type = NavType.StringType; nullable = true; defaultValue = null },",
        )
        assertPinned(STERNA_APP, "bodyToken = entry.arguments?.getString(\"bodyToken\"),")
    }

    @Test fun `the composer takes its body through its view model, for prepare and for the caret`() {
        assertPinned(
            COMPOSE_SCREEN, "val body = viewModel.claimLinkBody(bodyToken)",
            "prepare() must be handed the body claimed by the route's token.",
        )
        assertPinned(
            COMPOSE_SCREEN, "linkBodyLength = viewModel.claimLinkBody(bodyToken)?.takeIf { it.isNotBlank() }?.length ?: 0,",
            "the caret after a link's body (#83) must be measured on the claimed body, which the " +
                "view model still answers after a rotation, when the hand-off is already empty.",
        )
    }

    private fun routeBuilders(): List<String> = File(root, "app/src/main/kotlin").walkTopDown()
        .filter { it.isFile && it.extension == "kt" && "\"compose?" in it.readText() }
        .map { it.relativeTo(root).path }
        .toList()

    private fun assertPinned(path: String, pinned: String, why: String = "") {
        val hits = codeLines(path).count { it == pinned }
        assertEquals(
            "$why\nExpected exactly one code line of $path whose trimmed text is:\n    $pinned\nfound $hits.",
            1, hits,
        )
    }

    /** [path]'s code as trimmed whole lines, comments cut, so prose never satisfies a rule. */
    private fun codeLines(path: String): List<String> = File(root, path).readLines().mapNotNull { line ->
        val code = line.trim()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(code).trim().takeIf { it.isNotBlank() }
    }

    /** [line] up to its first `//` outside a double-quoted string; `\` escapes the next character. */
    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private companion object {
        const val STERNA_APP = "app/src/main/kotlin/app/sterna/ui/SternaApp.kt"
        const val COMPOSE_SCREEN = "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"

        val BODY_ARGUMENT = Regex("""navArgument\(\s*"body"\s*\)""")
        val BODY_PLACEHOLDER = Regex("""\{body\}""")

        /** `Uri.encode(m.body)`, `encode(link.body)`: any encoder handed something named `body`. */
        val BODY_ENCODED = Regex("""\bencode\(\s*(\w+\.)*body\b""")
        val BODY_QUERY = Regex("""[?&]body=""")

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, STERNA_APP).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }
    }
}
