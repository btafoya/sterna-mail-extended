package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, read as text: `onReceive` needs a broadcast and the application container, so no
 * JVM test can make its `catch` run. It pins the whole of what that `catch` does, one log line,
 * and with it everything the line does not name: no message id, no account, no typed text, and
 * no throwable handed to the logger, whose stack trace would print the exception's message.
 * What the label itself may say is executed in [NotificationActionFailureLabelTest].
 *
 * What it cannot hold: that the line is reached (its place in the `try` is not read, hole 3 of
 * CONTRIBUTING.md), nor what `$action` and the class name are bound to at run time (hole 1).
 */
class BannerActionFailureIsLoggedWiringTest {

    private val source: String by lazy {
        val path = "app/src/main/kotlin/app/sterna/push/NotificationActionReceiver.kt"
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, path) }.firstOrNull { it.isFile }?.readText()
            ?: error("cannot locate $path from ${File("").absolutePath}")
    }

    private val codeLines: List<String> by lazy {
        source.lines().map { it.trim() }
            .filterNot { it.isBlank() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
    }

    @Test fun `a banner action that throws says so in one line naming the action and the exception class only`() {
        val catches = codeLines.withIndex().filter { Regex("""\} catch \(\w+: Throwable\) \{""").matches(it.value) }
        assertEquals("the receiver has one catch of Throwable, onReceive's", 1, catches.size)
        val from = catches.single().index
        val to = codeLines.withIndex().first { it.index > from && it.value == "} finally {" }.index
        assertEquals(
            "a banner's Delete that throws must leave a trace, and that trace is the action and " +
                "the failure's label: a message id, an account, or the exception's own message " +
                "(which can quote an address) do not belong in the device log",
            listOf("""Log.w(TAG, "Notification action ${'$'}action failed: ${'$'}{notificationActionFailureLabel(e)}")"""),
            codeLines.subList(from + 1, to),
        )
        assertEquals(
            listOf("""private const val TAG = "NotificationAction""""),
            codeLines.filter { "const val TAG" in it },
        )
    }
}
