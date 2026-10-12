package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test: it reads `MessageScreen.kt`, `ReadingPane.kt` and `SternaApp.kt`
 * as text. The route itself is executed by `ComposeEditAsNewRouteTest`; what is pinned here is that
 * the reader's Edit as new entry (#254) reaches that route, from both places the reader is mounted,
 * and never travels as a reply.
 *
 * Not covered: that each of the two wirings sits in the call it belongs to, the reading pane's and
 * the full-screen reader's. The compiler holds that, the callback has no default at any hop.
 */
class EditAsNewMenuWiringTest {

    @Test fun `the entry sits right under Forward as attachment and calls its own callback`() {
        val screen = codeLines(MESSAGE_SCREEN)
        val forwardAttachment = screen.indexOf(
            "onClick = { menuOpen = false; onReply(\"forwardAttachment\", replyTargetId, accountId) },",
        )
        assertTrue("MessageScreen.kt no longer carries the Forward as attachment entry", forwardAttachment >= 0)
        assertEquals(
            "the Edit as new entry must follow Forward as attachment, whole, and call onEditAsNew: " +
                "through onReply the id travels as `replyTo` with a mode, and a mode the composer " +
                "does not know opens a quoted, threaded reply",
            listOf(
                ")",
                "if (mayEditAsNew(loaded.email, EditAsNewReader(bodyShown = printable, crypto = crypto))) {",
                "DropdownMenuItem(",
                "text = { Text(stringResource(R.string.message_edit_as_new)) },",
                "leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },",
                "onClick = { menuOpen = false; onEditAsNew(replyTargetId, accountId) },",
                ")",
                "}",
            ),
            screen.subList(forwardAttachment + 1, forwardAttachment + 9),
        )
    }

    /**
     * The entry is absent, not disabled, on a message [app.sterna.ui.compose.mayEditAsNew] refuses,
     * and the three things it is asked with are the reader's own: the message on screen, whether its
     * body has arrived, and the encryption state, observed and not sampled.
     */
    @Test fun `the entry is offered on what the reader holds, the encryption state included`() {
        val screen = codeLines(MESSAGE_SCREEN)
        val actions = screen.indexOf("private fun MessageActions(")
        val entry = screen.indexOf("if (mayEditAsNew(loaded.email, EditAsNewReader(bodyShown = printable, crypto = crypto))) {")
        assertTrue("MessageScreen.kt no longer declares MessageActions", actions >= 0)
        assertTrue("the Edit as new entry is no longer conditioned by mayEditAsNew", entry > actions)
        val before = screen.subList(actions, entry)
        assertEquals(
            listOf("val crypto by viewModel.crypto.collectAsStateWithLifecycle()"),
            before.filter { it.startsWith("val crypto ") },
        )
        assertEquals(
            "`printable` must stay the fact that the open message has its body",
            listOf("val printable = messages.firstOrNull()?.body != null"),
            before.filter { it.startsWith("val printable ") },
        )
        assertEquals(
            listOf("val loaded = state as? MessageState.Loaded ?: return"),
            before.filter { it.startsWith("val loaded ") },
        )
        assertEquals("mayEditAsNew is asked once in MessageScreen.kt", 1, screen.count { "mayEditAsNew(" in it })
    }

    @Test fun `the callback is invoked by that entry and by nothing else`() {
        assertEquals(
            listOf("onClick = { menuOpen = false; onEditAsNew(replyTargetId, accountId) },"),
            codeLines(MESSAGE_SCREEN).filter { "onEditAsNew(" in it },
        )
    }

    @Test fun `no reply is ever opened under a mode of that name`() {
        val replies = (codeLines(MESSAGE_SCREEN) + codeLines(READING_PANE) + codeLines(STERNA_APP))
            .filter { "onReply(" in it || "replyTo=" in it || "mode=" in it }
        assertEquals(
            "Edit as new must never ride onReply, `replyTo` or `mode`. Lines found:",
            emptyList<String>(),
            replies.filter { "editasnew" in it.lowercase() },
        )
    }

    @Test fun `the reading pane hands the callback down`() {
        assertEquals(
            listOf("onEditAsNew: (emailId: String, accountId: String?) -> Unit,", "onEditAsNew = onEditAsNew,"),
            codeLines(READING_PANE).filter { "onEditAsNew" in it },
        )
    }

    /**
     * Two wirings, the reading pane's and the full-screen reader's, and the same three lines in each.
     * One of them left on a stub, or sent through the reply route, is an entry that does nothing or
     * answers the message on one of the two layouts only.
     */
    @Test fun `both mounts of the reader navigate by the Edit as new route, with the message's account`() {
        val app = codeLines(STERNA_APP)
        val wirings = app.indices.filter { "onEditAsNew" in app[it] }
        assertEquals(
            "SternaApp.kt must wire onEditAsNew exactly twice. Lines found:",
            listOf("onEditAsNew = { emailId, accountId ->", "onEditAsNew = { emailId, accountId ->"),
            wirings.map { app[it] },
        )
        wirings.forEach { at ->
            assertEquals(
                "each wiring must navigate, once per tap, to composeEditAsNewRoute with the id and " +
                    "the account it was handed",
                listOf(
                    "onEditAsNew = { emailId, accountId ->",
                    "entry.navigateOnce {",
                    "nav.navigate(composeEditAsNewRoute(emailId, accountId.orEmpty()) { Uri.encode(it) })",
                    "}",
                    "},",
                ),
                app.subList(at, at + 5),
            )
        }
        assertEquals(
            "the route is built twice in SternaApp.kt, once per wiring, and nowhere else",
            2,
            app.count { "composeEditAsNewRoute(" in it },
        )
    }

    // -- reading the source -----------------------------------------------------------------------

    private fun codeLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val code = line.trim()
        if (code.isEmpty() || code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null else code
    }

    companion object {
        private const val MESSAGE_SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, MESSAGE_SCREEN_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }

        private val MESSAGE_SCREEN: File by lazy { File(root, MESSAGE_SCREEN_PATH) }
        private val READING_PANE: File by lazy { File(root, "app/src/main/kotlin/app/sterna/ui/inbox/ReadingPane.kt") }
        private val STERNA_APP: File by lazy { File(root, "app/src/main/kotlin/app/sterna/ui/SternaApp.kt") }
    }
}
