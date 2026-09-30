package app.sterna.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, not a behaviour test: `ComposeViewModel` is an AndroidViewModel a JVM test cannot
 * build, so the hand-over from `saveDraft` to [draftBodiesToSave] is pinned as whole trimmed code
 * lines. The decision itself is executed by [DraftBodiesToSaveTest].
 */
class QueuedForwardDraftWiringTest {

    @Test fun `saveDraft decides its bodies through draftBodiesToSave, with the queued row and the forward`() {
        val code = codeLines(COMPOSE_VIEW_MODEL.readText())

        assertEquals(
            "one call, handed whether a queued row is being edited and the carried forward; a " +
                "constant or a dropped argument saves the note alone, then the row is consumed",
            listOf("val bodies = draftBodiesToSave(body, reopenedFromOutbox = editingOutboxId != null, forwarded = forwarded)"),
            code.filter { "draftBodiesToSave(" in it },
        )
        assertEquals(
            "and both parts of the save come from it",
            listOf("credentials, recipients, subject, bodies.text,", "html = bodies.html,"),
            code.filter { it.startsWith("credentials, recipients, subject, bodies.") || it.startsWith("html = bodies.") },
        )
        assertEquals(
            "no draft part may be built beside it",
            emptyList<String>(),
            code.filter { "draftHtmlToSave(" in it },
        )
    }

    /** Source lines, trimmed, with comments and blanks removed. */
    private fun codeLines(source: String): List<String> {
        val out = StringBuilder()
        var i = 0
        var inString = false
        while (i < source.length) {
            val c = source[i]
            when {
                inString && c == '\\' -> { out.append(c).append(source.getOrElse(i + 1) { ' ' }); i += 2; continue }
                c == '"' -> { inString = !inString; out.append(c) }
                inString -> out.append(c)
                c == '/' && source.getOrNull(i + 1) == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                    out.append('\n')
                    continue
                }
                c == '/' && source.getOrNull(i + 1) == '*' -> {
                    val end = source.indexOf("*/", i + 2)
                    i = if (end < 0) source.length else end + 2
                    continue
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private companion object {
        private const val COMPOSE_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, COMPOSE_VIEW_MODEL_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }

        val COMPOSE_VIEW_MODEL: File by lazy { File(root, COMPOSE_VIEW_MODEL_PATH) }
    }
}
