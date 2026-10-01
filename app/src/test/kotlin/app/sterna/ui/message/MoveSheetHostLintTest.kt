package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, beside [MoveSheetOpenTest] which executes the holder. The reader is composed by two
 * hosts, the `message/…` route and the reading pane, and a rotation across the two-pane threshold
 * swaps one for the other; no JVM test can compose either. What this pins is that the reader's move
 * sheet and its filter live in the one holder both hosts hand over, never in the reader's own
 * composition, which dies with the host. Whole trimmed lines, never `contains`.
 */
class MoveSheetHostLintTest {

    @Test fun `the reader keeps no local state for the move sheet`() {
        val lines = codeLines(read(SCREEN))
        assertEquals(
            "MessageScreen must not hold the move sheet's open flag or its filter in its own " +
                "composition (`remember`/`rememberSaveable`): the route-to-pane swap on a rotation " +
                "drops it. Lines found:",
            emptyList<String>(),
            lines.filter { (it.startsWith("var movePicker") || it.startsWith("var moveQuery")) || ("remember" in it && ("movePicker" in it || "moveQuery" in it)) },
        )
    }

    @Test fun `the reader opens, shows, filters and closes the sheet through the holder only`() {
        val lines = codeLines(read(SCREEN))
        assertEquals(
            "the sheet must be shown by the holder's decision for the SETTLED message. Lines found:",
            listOf(
                "val moveSheet = LocalMoveSheet.current",
                "val moveOpenFor by moveSheet.openFor.collectAsStateWithLifecycle()",
                "val movePicker = MoveSheetOpen.shows(moveOpenFor, active.emailId, active.accountId)",
            ),
            lines.filter { it.startsWith("val moveSheet") || it.startsWith("val moveOpenFor") || it.startsWith("val movePicker") },
        )
        assertEquals(
            "the overflow entry must open the sheet for the settled message through the holder. Lines found:",
            listOf("onClick = { menuOpen = false; moveSheet.open(active.emailId, active.accountId) },"),
            lines.filter { "moveSheet.open(" in it },
        )
        assertEquals(
            "the filter must be read from and written to the holder, and the sheet closed through it. Lines found:",
            listOf(
                "val moveQuery by moveSheet.query.collectAsStateWithLifecycle()",
                "query = moveQuery,",
                "onQueryChange = moveSheet::filter,",
                "onDismiss = moveSheet::close,",
            ),
            lines.filter { it.startsWith("val moveQuery") || it == "query = moveQuery," || it.startsWith("onQueryChange = moveSheet") || it.startsWith("onDismiss = moveSheet") },
        )
        assertEquals(
            "MessageScreen must take the holder as a parameter WITHOUT a default and provide it. Lines found:",
            listOf(
                "moveSheet: MoveSheetOpen,",
                "CompositionLocalProvider(LocalSignatureSheet provides signatureSheet, LocalMoveSheet provides moveSheet, LocalPickInstant provides pickInstant) {",
            ),
            lines.filter { it.startsWith("moveSheet: ") || "LocalMoveSheet provides" in it },
        )
    }

    @Test fun `both hosts hand over the inbox ViewModel's holder, built on its SavedStateHandle`() {
        for (host in listOf(STERNA_APP, READING_PANE)) {
            assertEquals(
                "$host must hand MessageScreen the holder of the shared inbox ViewModel, the one " +
                    "owner that outlives the swap between the route and the pane. Lines found:",
                listOf("moveSheet = inboxViewModel.moveSheet,"),
                codeLines(read(host)).filter { "moveSheet" in it },
            )
        }
        assertEquals(
            "InboxViewModel must build the holder on its SavedStateHandle. Lines found:",
            listOf("val moveSheet = MoveSheetOpen({ key -> savedState.get<String>(key) }, { key, value -> savedState[key] = value })"),
            codeLines(read(INBOX_VM)).filter { "MoveSheetOpen(" in it },
        )
    }

    @Test fun `the move sheet is cleared by the inbox ViewModel's fresh reading, never by the pane's open or close`() {
        val lines = codeLines(read(INBOX_VM))
        assertEquals(
            "the move sheet must be closed from one place in InboxViewModel, its fresh-reading entry. " +
                "Closing it from `openInPane` or `closePane` drops it on a rotation, whose route-to-pane " +
                "conversion goes through both. Lines found:",
            listOf("moveSheet.close()"),
            lines.filter { "moveSheet.close()" in it },
        )
        assertEquals(
            "any other line in `startFreshReading`, such as an early return, can leave the move sheet open on a fresh reading. Body:",
            listOf(
                "moveSheet.close()",
                "if (PickInstantOpen.readerLeft(pickInstant.target.value, activeKey = null)) pickInstant.close()",
                "}",
            ),
            body(lines, "fun startFreshReading()"),
        )
        for (carrier in listOf("fun openInPane(", "fun closePane(")) {
            assertEquals(
                "`$carrier` also carries the SAME reading across a rotation: it must not clear the sheet. Lines found:",
                emptyList<String>(),
                body(lines, carrier).filter { "startFreshReading" in it || "moveSheet" in it },
            )
        }
    }

    @Test fun `every fresh reading gesture clears the move sheet before the reader is shown`() {
        val lines = codeLines(read(STERNA_APP))
        // A search result is a row of the list, opened through the same `onOpenEmail` with `fromSearch`.
        for (start in listOf(
            "onOpenEmail = { id, accountId, index, fromSearch ->",
            "onOpenThreadMessage = { id, accountId, threadKey, index ->",
        )) {
            val block = block(lines, start)
            val clear = block.indexOf("inboxViewModel.startFreshReading()")
            val pane = block.indexOf("if (readingPane) inboxViewModel.openInPane(anchor)")
            assertTrue("a row tapped in the list ($start) must clear the move sheet before the reader opens. Block: $block", clear in 0 until pane)
        }
        val notification = block(lines, "LaunchedEffect(pendingEmailOpen) {")
        assertTrue(
            "a tapped notification is a fresh reading, in the pane or on the route, held until the list " +
                "exists on a cold start; cleared before either arm runs. Block: $notification",
            notification.indexOf("if (listViewModel != null) listViewModel.startFreshReading() else pendingFreshReading = true") in
                0 until notification.indexOfFirst { it.startsWith("when (PaneOrders.emailOpen(") },
        )
        val held = block(lines, "LaunchedEffect(pendingFreshReading, listViewModel) {")
        assertEquals(
            "the held cold-start order must reach the list once it exists, then drop. Block: $held",
            listOf("val list = listViewModel ?: return@LaunchedEffect", "pendingFreshReading = false", "list.startFreshReading()"),
            held.filter { it.startsWith("val list") || it.startsWith("pendingFreshReading") || it.startsWith("list.") },
        )
        assertEquals(
            "the fresh reading is called from those gestures and the held cold-start order only, never from " +
                "the rotation's conversions between the route and the pane. Lines found:",
            listOf(
                "if (listViewModel != null) listViewModel.startFreshReading() else pendingFreshReading = true",
                "list.startFreshReading()",
                "inboxViewModel.startFreshReading()",
                "inboxViewModel.startFreshReading()",
            ),
            lines.filter { "startFreshReading" in it },
        )
    }

    /** The lines of the block opened by the line starting with [start], up to its matching close. */
    private fun block(lines: List<String>, start: String): List<String> {
        val from = lines.indexOfFirst { it.startsWith(start) }
        check(from >= 0) { "no line starts with `$start`" }
        var depth = 0
        val out = mutableListOf<String>()
        for (line in lines.drop(from)) {
            out += line
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth <= 0 && out.size > 1) break
        }
        return out
    }

    private fun body(lines: List<String>, start: String): List<String> = block(lines, start).drop(1)

    private fun codeLines(text: String): List<String> = text.lines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private fun read(relative: String): String {
        val fromModule = relative.removePrefix("app/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it.readText() }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it.readText() }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val SCREEN = "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt"
        const val STERNA_APP = "app/src/main/kotlin/app/sterna/ui/SternaApp.kt"
        const val READING_PANE = "app/src/main/kotlin/app/sterna/ui/inbox/ReadingPane.kt"
        const val INBOX_VM = "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"
    }
}
