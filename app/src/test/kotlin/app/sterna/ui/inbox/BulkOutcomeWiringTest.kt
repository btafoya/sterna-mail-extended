package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same disclaimer as [ConversationScopeWiringTest]: it reads
 * a file as text and proves nothing about what runs. `InboxViewModel` is an `AndroidViewModel` and
 * this module has neither Robolectric nor instrumented tests, so the decision itself is executed by
 * [BulkOutcomeTest] and only its PLUG is checked here.
 *
 * What it pins is what each outcome DOES, whole: swap two branches and this goes red — "Couldn't
 * complete the action" on a mostly-successful bulk is exactly the lie this lot exists to stop
 * telling — and so does a total failure that stops asking which of the two sentences it owes.
 */
class BulkOutcomeWiringTest {

    private fun bulkBatchedBody(): String {
        val source = INBOX_VIEW_MODEL.readText()
        val start = source.indexOf("private fun bulkBatched(")
        assertTrue("InboxViewModel has no bulkBatched() — renamed?", start >= 0)
        var depth = 0
        var i = source.indexOf('{', start)
        val bodyStart = i
        do {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        } while (depth > 0)
        return source.substring(bodyStart, i)
    }

    @Test fun theBulkToastGoesThroughTheOutcomeDecision() {
        val body = bulkBatchedBody()
        assertTrue(
            "bulkBatched must ask bulkOutcome() rather than re-deciding from failedKeys itself",
            "bulkOutcome(" in body,
        )
        // The counts it feeds it: the WHOLE selection handed in (plus whatever a delegating caller
        // already lost), and everything that did not go through — the batch's rejects, the keys
        // nothing resolved for, and the caller's losses. `emails.size` here was the report itself:
        // a selection whose rows were none of them cached reached nothing and said nothing.
        // Whole lines, never `in`: a substring rule accepts anything appended to what it matched.
        // BulkSelectionWiringTest pins the same two lines; this one is the copy that fails loudly
        // if the pairing below is ever reached through a different count.
        assertEquals(
            "bulkOutcome must be fed the whole selection (plus the caller's losses) and every " +
                "failure, unresolved keys included",
            listOf(
                "val failed = failedKeys.size + resolved.unresolved.size + failedBefore",
                "when (bulkOutcome(attempted = targetKeys.size + attemptedBefore, failed = failed)) {",
            ),
            body.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
                .filter { it.startsWith("val failed =") || it.startsWith("when (bulkOutcome(") },
        )
    }

    /**
     * WHOLE BRANCHES, not the first `R.string` each names. The total-failure branch no longer
     * reaches for one sentence: it asks the shared decision when a batch let a throwable escape,
     * and falls back to the generic sentence when none did (a server refusal, an over-quota, a
     * numbering the server would not confirm — none of them a transport failure). A rule reading
     * only the first resource in the branch would have kept passing with the decision deleted, or
     * with `online = false` tacked into it, which is the shape this fix comes undone in.
     *
     * PARTIAL is pinned unchanged on purpose: offline nothing succeeds by halves, so a partial
     * failure is never an outage and "you are offline" there would be a second lie.
     */
    @Test fun eachOutcomeReachesForItsOwnString() {
        assertEquals(
            "each outcome must reach for its own sentence, the total-failure branch pinned whole",
            mapOf(
                "NONE" to "Unit",
                "TOTAL" to "_message.value = app.getString(if (failure == null) " +
                    "R.string.status_action_failed else actionFailureMessage(failure, online = " +
                    "hasUsableNetwork(app)))",
                "PARTIAL" to "_message.value = getApplication<Application>()" +
                    ".getString(R.string.status_action_partly_failed)",
            ),
            outcomeBranches(),
        )
    }

    /**
     * The branches of `bulkBatched`'s `when (bulkOutcome(…))`, braces balanced so the last one
     * stops at the `when` and not at the end of the function, comment lines dropped, whitespace
     * squeezed: a wrapped branch reads as one string.
     */
    private fun outcomeBranches(): Map<String, String> {
        val body = bulkBatchedBody()
        val start = body.indexOf("when (bulkOutcome(")
        assertTrue("bulkBatched no longer decides through when (bulkOutcome(…)) — renamed?", start >= 0)
        var i = body.indexOf('{', start)
        val blockStart = i + 1
        var depth = 0
        do {
            when (body[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        } while (depth > 0)
        val block = body.substring(blockStart, i - 1).lines()
            .filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        return Regex("""BulkOutcome\.(\w+)\s*->((?:(?!BulkOutcome\.)[\s\S])*)""")
            .findAll(block)
            .associate { m -> m.groupValues[1] to m.groupValues[2].trim().replace(Regex("""\s+"""), " ") }
    }

    private companion object {
        const val PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        val INBOX_VIEW_MODEL: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, PATH).isFile }
                ?.let { File(it, PATH) }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads a " +
                        "source file as text and needs a working directory inside the checkout",
                )
        }
    }
}
