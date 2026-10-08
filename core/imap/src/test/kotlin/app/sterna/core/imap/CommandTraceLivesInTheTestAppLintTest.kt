package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint over the whole tree: the command trace has one sink, one place that installs it and
 * one log tag, and all three live under `app/src/testApp/`, the source set `app/build.gradle.kts`
 * registers only under `-PtestApp`. What the sink receives is executed in
 * [CommandTraceOnTheWireTest]; this pins where a sink can come from.
 *
 * What it does not cover:
 * - That `app/src/testApp/` stays out of a build without the property. That is the gate in
 *   `app/build.gradle.kts`, which this does not read.
 * - That the installer compiles or runs: the unit tests never pass `-PtestApp`, so the file is
 *   text to them. Its first execution is an install of the test app.
 * - A reference assembled from pieces, or reached through reflection, escapes the symbol search.
 * - `src/test` and `src/androidTest` are not read: a test may name the tap, and one does.
 * - Of the four holes `CONTRIBUTING.md` lists, the third applies (a pinned line says nothing about
 *   the control structure around it: the assignment could sit behind a condition); the others do
 *   not, no argument and no composable being involved.
 */
class CommandTraceLivesInTheTestAppLintTest {

    /** Assembled, so that this file is not itself an occurrence under `core/`. */
    private val tag = "Imap" + "Trace"

    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("No settings.gradle.kts above ${System.getProperty("user.dir")}")

    private val notSources = setOf("build", ".git", ".gradle", ".claude", ".kotlin", "ai-work")
    private val testSourceSets = setOf("test", "androidTest")

    /** Every shipped or shippable source file, as a path relative to the repository root. */
    private val sources: Map<String, String> = root.walkTopDown()
        .onEnter { it.name !in notSources && !(it.name in testSourceSets && it.parentFile.name == "src") }
        .filter { it.isFile && it.extension in setOf("kt", "java", "xml", "kts", "pro") }
        .filter { "/src/" in it.relativeTo(root).invariantSeparatorsPath }
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }

    private fun holding(fragment: String): List<String> =
        sources.filterValues { fragment in it }.keys.sorted()

    @Test fun `the scan reaches the sources it is meant to judge`() {
        assertTrue(CLIENT in sources)
        assertTrue("app/src/main/AndroidManifest.xml" in sources)
        assertTrue(sources.keys.any { it.startsWith("core/data/src/main/") })
    }

    @Test fun `the log tag is written under the test app's sources and nowhere else`() {
        assertEquals(listOf(INSTALLER), holding(tag).filterNot { it == TEST_MANIFEST })
        assertTrue(
            "the installer must log under the tag the bench filters on",
            "private const val TAG = \"$tag\"" in sources.getValue(INSTALLER).lines().map { it.trim() },
        )
    }

    @Test fun `only core imap and the test app's installer name the tap or the sink`() {
        for (symbol in listOf("ImapCommandTap", "ImapCommandSink")) {
            assertEquals(
                "$symbol named outside core/imap and the installer: a sink built anywhere else is " +
                    "a trace the published app could carry",
                listOf(INSTALLER),
                holding(symbol).filterNot { it.startsWith("core/imap/src/main/") },
            )
        }
    }

    @Test fun `the tap is assigned once, by the installer`() {
        val assignment = Regex("""ImapCommandTap\s*\.\s*installed\s*=[^=]""")
        assertEquals(listOf(INSTALLER), sources.filterValues { assignment.containsMatchIn(it) }.keys.sorted())
        assertEquals(
            listOf("ImapCommandTap.installed = LogcatSink"),
            sources.getValue(INSTALLER).lines().map { it.trim() }.filter { "ImapCommandTap" in it && !it.startsWith("import ") && !it.startsWith("*") },
        )
    }

    @Test fun `the installer is declared by the test app's manifest, not the published one`() {
        val declaration = "android:name=\"app.sterna.bench.${tag}Installer\""
        assertEquals(listOf(TEST_MANIFEST), holding(declaration))
    }

    private companion object {
        const val CLIENT = "core/imap/src/main/kotlin/app/sterna/core/imap/ImapClient.kt"
        const val INSTALLER = "app/src/testApp/kotlin/app/sterna/bench/" + "Imap" + "TraceInstaller.kt"
        const val TEST_MANIFEST = "app/src/testApp/AndroidManifest.xml"
    }
}
