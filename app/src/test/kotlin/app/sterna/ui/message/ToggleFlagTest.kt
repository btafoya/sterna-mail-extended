package app.sterna.ui.message

import app.sterna.core.jmap.model.Email
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The star the reader toggles (#232). The header reads `_messages`, the action bar reads `_state`:
 * both must take the same optimistic value. `MessageViewModel` is not instantiable on the JVM, so the
 * value is tested by running [withFlag] and the wiring by reading the body of `toggleFlag`.
 */
class ToggleFlagTest {

    @Test
    fun `starring sets the flagged keyword and keeps the others`() {
        val email = Email(id = "1", keywords = mapOf("\$seen" to true))

        val starred = email.withFlag(true)

        assertTrue(starred.isFlagged)
        assertEquals(mapOf("\$seen" to true, "\$flagged" to true), starred.keywords)
    }

    @Test
    fun `unstarring removes the flagged keyword instead of storing false`() {
        val email = Email(id = "1", keywords = mapOf("\$seen" to true, "\$flagged" to true))

        val unstarred = email.withFlag(false)

        assertFalse(unstarred.isFlagged)
        assertEquals(mapOf("\$seen" to true), unstarred.keywords)
    }

    @Test
    fun `toggleFlag gives the header of the open message the same value as the action bar`() {
        val body = toggleFlagBody()

        assertTrue(
            "toggleFlag no longer sets the action bar's value from `current.withFlag(flagged)`",
            "_state.value = MessageState.Loaded(current.withFlag(flagged))" in body,
        )
        val update = Regex("""updateMessage\(current\.id\)\s*\{([^}]*)\}""").find(body)
        assertTrue(
            "toggleFlag does not call updateMessage(current.id) { … }: the header keeps the old star",
            update != null,
        )
        assertTrue(
            "toggleFlag's updateMessage does not set the header from `withFlag(flagged)`",
            "header = it.header.withFlag(flagged)" in update!!.groupValues[1],
        )
    }

    /** The text of `fun toggleFlag()`, up to the next member at class level. */
    private fun toggleFlagBody(): String {
        val source = source.readText()
        val start = source.indexOf("fun toggleFlag()")
        assertTrue("MessageViewModel has no toggleFlag() any more", start > 0)
        val end = NEXT_MEMBER.find(source, start + 1)?.range?.first ?: source.length
        return source.substring(start, end)
    }

    private companion object {
        val NEXT_MEMBER = Regex("""\n {4}(private )?(suspend )?fun \w""")

        val source: File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt") }
                .firstOrNull { it.isFile }
                ?: error("cannot locate MessageViewModel.kt from ${File("").absolutePath}")
    }
}
