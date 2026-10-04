package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, the last resort: [MessageViewModel] is an `AndroidViewModel` reaching into the
 * application container and cannot be built in a JVM test. What is decided, and what a message
 * looks like once its read mark is taken back, is executed in [ReadMarkUndoTest]; this file only
 * pins that a failed write of the read mark reaches that decision, and that nothing is repainted
 * or re-armed without it.
 *
 * Of the four holes a source lint has, three apply:
 *  - an argument name says nothing about what it is bound to: `credentials.id` and `current.id`
 *    are pinned as text, and that they are the pair the write was sent for rests on the lines of
 *    `maybeMarkRead` pinned by `GroupSummaryObeysTheContentSettingWiringTest`;
 *  - a pinned line says nothing about execution order beyond the order the lines are written in,
 *    and nothing about the thread: `Dispatchers.Main.immediate` is pinned as a word, that the block
 *    really runs where the rest of the view model mutates its state is not seen here;
 *  - nothing here holds what the reader's composables collect: that the unread dot comes back on
 *    screen, and that a later settle writes again, is a claim only a device can hold.
 * The fourth, the expression around a call, is closed for the decision by pinning the whole line
 * that reads its answer.
 *
 * Nor does it see the window between this undo and the release of the `markingRead` claim: a
 * settle landing inside it paints the mark and writes nothing.
 */
class ReadMarkUndoWiringTest {

    @Test fun `a write of the read mark that threw takes the mark back, and only that one does`() {
        val body = bodyOf("maybeMarkRead")
        assertEquals(
            "the branch for a write that threw logs, then takes the painted mark back for the pair " +
                "the write was sent for. Without the call the page keeps saying read for a message " +
                "that is unread in the cache and on the server, and `anchorMarked` forbids any retry",
            listOf(
                "ReadMarkCleanup.KEEP_AND_LOG -> write.exceptionOrNull()?.let { failure ->",
                "android.util.Log.w(\"SternaMessage\", \"mark read failed: \${current.id}\", failure)",
                "showUnreadAgain(credentials.id, current.id)",
                "}",
                "ReadMarkCleanup.KEEP_AND_RETHROW -> write.getOrThrow()",
            ),
            linesFrom(body, "ReadMarkCleanup.KEEP_AND_LOG -> write.exceptionOrNull()?.let { failure ->", 5),
        )
        assertEquals(
            "…and that is the only call in the body. A count, because the window above cannot see a " +
                "second one: in the branch of a write that returned it would show unread a message " +
                "just marked read, and on a cancellation it would repaint a page being torn down",
            1,
            codeLinesNaming(body, "showUnreadAgain").size,
        )
        assertEquals(
            "…and nowhere else in the file either: this one call and the declaration",
            listOf(
                "showUnreadAgain(credentials.id, current.id)",
                "private suspend fun showUnreadAgain(writtenAccount: String, writtenId: String) =",
            ),
            codeLinesNaming(SOURCE.readText(), "showUnreadAgain"),
        )
    }

    @Test fun `the mark is taken back from the state as it stands, behind the decision`() {
        assertEquals(
            "the whole body. The decision's answer gates everything below it: the write runs on the " +
                "app's scope and can return after the page loaded another message, whose settle a " +
                "bare `anchorMarked = false` would re-arm. `anchorMarked = false` is what stops " +
                "`anchorDisplay` from repainting the mark and lets the next settle write again. " +
                "Header, body and `_state` are each transformed from what they hold now, never set " +
                "back to a message captured before the write: a star tapped in between must survive. " +
                "On the main dispatcher, where every other write to these fields happens",
            listOf(
                "{",
                "val id = readMarkToUndo(loadedId, credentials()?.id, writtenId, writtenAccount) ?: return@withContext",
                "this@MessageViewModel.anchorMarked = false",
                "updateMessage(id) { it.copy(header = it.header.withoutReadMark(), body = it.body?.withoutReadMark()) }",
                "val shown = (_state.value as? MessageState.Loaded)?.email?.takeIf { it.id == id } ?: return@withContext",
                "_state.value = MessageState.Loaded(shown.withoutReadMark())",
                "}",
            ),
            codeLinesNaming(bodyOf("showUnreadAgain"), ""),
        )
        assertEquals(
            "…and that block is the one handed to the main dispatcher: `loadedId` and `anchorMarked` " +
                "are plain fields of a view model written from the main thread, and the caller runs " +
                "on `Dispatchers.Default`",
            listOf("withContext(Dispatchers.Main.immediate) {"),
            codeLinesNaming(SOURCE.readText(), "Dispatchers.Main"),
        )
        assertEquals(
            "…and the settle stays the only place that paints the mark, with the reset in `load` the " +
                "only other write besides this one",
            listOf("anchorMarked = false", "anchorMarked = true", "this@MessageViewModel.anchorMarked = false"),
            codeLinesNaming(SOURCE.readText(), "anchorMarked =").filterNot { it.startsWith("private var") },
        )
    }

    /** [count] code lines of [body] from the one line equal to [anchor]. */
    private fun linesFrom(body: String, anchor: String, count: Int): List<String> {
        val lines = codeLinesNaming(body, "")
        val at = lines.indexOf(anchor)
        if (at < 0) error("no line '$anchor' in this body — did it get renamed or reflowed?")
        return lines.subList(at, minOf(at + count, lines.size))
    }

    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    /** The first block after `fun [name](`, braces included: the body, or the lambda an expression body opens. */
    private fun bodyOf(name: String): String {
        val source = SOURCE.readText()
        val fn = Regex("""\bfun\s+$name\s*\(""").find(source)
            ?: error("${SOURCE.name} has no function named '$name' — did it get renamed?")
        val open = source.indexOf('{', fn.range.last)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
            i++
        }
        error("Unbalanced braces in ${SOURCE.name}.$name")
    }

    private companion object {
        const val PATH = "app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt"

        val SOURCE: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, PATH) }
                .firstOrNull { it.isFile }
                ?: error("cannot locate $PATH from ${File("").absolutePath}")
        }
    }
}
