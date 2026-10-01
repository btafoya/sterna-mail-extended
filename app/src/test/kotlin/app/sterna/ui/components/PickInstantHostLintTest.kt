package app.sterna.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, beside [PickInstantOpenTest] which executes the holder and its decisions. Three
 * hosts draw the "pick date and time" dialogs of a snooze: the reader, the inbox selection and the
 * "Snoozed" screen; no JVM test can compose any of them. What this pins is that each takes the one
 * holder its ViewModel builds on a `SavedStateHandle`, keeps no step or day of its own, draws only
 * for its own target, closes the holder when that target is gone, and offers the entry last in its
 * snooze menu. Whole trimmed lines, never `contains`.
 *
 * Not covered: that the snooze menus themselves stay out of Spam and the Trash. The entry lives
 * inside them, so it is only pinned as being there.
 */
class PickInstantHostLintTest {

    @Test fun `no host builds the holder or keeps its step or day in its own composition`() {
        for (host in listOf(SCREEN, INBOX_SCREEN, SELECTION_BAR, SNOOZED_SCREEN, STERNA_APP, READING_PANE)) {
            val lines = codeLines(read(host))
            assertEquals(
                "$host must not build a holder: a rotation or the route-to-pane swap would drop it. Lines found:",
                emptyList<String>(),
                lines.filter { "PickInstantOpen(" in it },
            )
            assertEquals(
                "$host must not hold the step, the day or the target in `remember`/`rememberSaveable`. Lines found:",
                emptyList<String>(),
                lines.filter { "remember" in it && ("pick" in it || "PickInstant" in it) },
            )
        }
    }

    @Test fun `each ViewModel builds the holder on its SavedStateHandle`() {
        for (vm in listOf(INBOX_VM, SNOOZED_VM)) {
            assertEquals(
                "$vm must build the holder on its SavedStateHandle. Lines found:",
                listOf("val pickInstant = PickInstantOpen({ key -> savedState.get<String>(key) }, { key, value -> savedState[key] = value })"),
                codeLines(read(vm)).filter { "PickInstantOpen(" in it },
            )
        }
        assertEquals(
            "SnoozedViewModel must take the SavedStateHandle the default factory hands it. Lines found:",
            listOf("class SnoozedViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {"),
            codeLines(read(SNOOZED_VM)).filter { it.startsWith("class SnoozedViewModel") },
        )
    }

    @Test fun `the reader takes the inbox ViewModel's holder without a default and draws for the settled message only`() {
        val lines = codeLines(read(SCREEN))
        assertEquals(
            "MessageScreen must take the holder WITHOUT a default and provide it. Lines found:",
            listOf(
                "pickInstant: PickInstantOpen,",
                "CompositionLocalProvider(LocalSignatureSheet provides signatureSheet, LocalMoveSheet provides moveSheet, LocalPickInstant provides pickInstant) {",
            ),
            lines.filter { it.startsWith("pickInstant: ") || "LocalPickInstant provides" in it },
        )
        for (host in listOf(STERNA_APP, READING_PANE)) {
            assertEquals(
                "$host must hand MessageScreen the shared inbox ViewModel's holder. Lines found:",
                listOf("pickInstant = inboxViewModel.pickInstant,"),
                codeLines(read(host)).filter { "pickInstant" in it },
            )
        }
        assertEquals(
            "the reader must decide on the settled message's key, close a target it left, and draw for its own. Lines found:",
            listOf(
                "val pickInstant = LocalPickInstant.current",
                "val pickTarget by pickInstant.target.collectAsStateWithLifecycle()",
                "val pickKey = MessagePaging.entryKey(active.emailId, active.accountId)",
                "LaunchedEffect(pickTarget, pickKey) { if (PickInstantOpen.readerLeft(pickTarget, pickKey)) pickInstant.close() }",
                "if (PickInstantOpen.shows(pickTarget, pickKey)) {",
            ),
            lines.filter { it.startsWith("val pickInstant") || it.startsWith("val pickTarget") || it.startsWith("val pickKey") || it.startsWith("val pickRow") || "PickInstantOpen." in it },
        )
        assertDialogs(lines, "onPicked = { until, _, _ -> viewModel.snooze(until, onBack); pickInstant.close() },")
        assertEntry(lines, "if (snoozeSubmenu) {", "onClick = { menuOpen = false; snoozeSubmenu = false; pickInstant.open(pickKey) },")
    }

    @Test fun `a fresh reading drops the reader's target, and the pane's open and close keep it`() {
        val lines = codeLines(read(INBOX_VM))
        assertEquals(
            "the holder must be closed from InboxViewModel's fresh-reading entry only. Lines found:",
            listOf("if (PickInstantOpen.readerLeft(pickInstant.target.value, activeKey = null)) pickInstant.close()"),
            lines.filter { "pickInstant.close()" in it },
        )
        assertTrue(
            "the close must sit in `startFreshReading`",
            "if (PickInstantOpen.readerLeft(pickInstant.target.value, activeKey = null)) pickInstant.close()" in
                body(lines, "fun startFreshReading()"),
        )
        for (carrier in listOf("fun openInPane(", "fun closePane(")) {
            assertEquals(
                "`$carrier` carries the same reading across a rotation: it must not touch the holder. Lines found:",
                emptyList<String>(),
                body(lines, carrier).filter { "pickInstant" in it || "startFreshReading" in it },
            )
        }
    }

    @Test fun `the selection draws below a non-empty selection, outside the top bar, and closes when emptied`() {
        val lines = codeLines(read(INBOX_SCREEN))
        assertEquals(
            "InboxScreen must read the ViewModel's holder, close it on an emptied selection and draw under that guard. Lines found:",
            listOf(
                "val pickInstant = viewModel.pickInstant",
                "val pickTarget by pickInstant.target.collectAsStateWithLifecycle()",
                "LaunchedEffect(pickTarget, selectionEmpty) { if (PickInstantOpen.selectionGone(pickTarget, selectionEmpty)) pickInstant.close() }",
                "if (!selectionEmpty && PickInstantOpen.shows(pickTarget, PickInstantOpen.SELECTION)) {",
            ),
            lines.filter { it.startsWith("val pickInstant") || it.startsWith("val pickTarget") || it.startsWith("val pickKey") || it.startsWith("val pickRow") || "PickInstantOpen." in it },
        )
        assertEquals(listOf("val selectionEmpty = selectedKeys.isEmpty()"), lines.filter { it.startsWith("val selectionEmpty") })
        assertDialogs(lines, "onPicked = { until, _, _ -> viewModel.snoozeSelected(until); pickInstant.close() },")
        val topBar = block(lines, "topBar = {")
        assertEquals(
            "the dialogs must not live in the top bar slot, which leaves with the selection. Block: $topBar",
            emptyList<String>(),
            topBar.filter { "PickInstantDialogs(" in it },
        )
        assertEquals(
            "the import of the presets is dead in InboxScreen. Lines found:",
            emptyList<String>(),
            lines.filter { "snoozePresets" in it },
        )
        val bar = codeLines(read(SELECTION_BAR))
        assertEquals(emptyList<String>(), bar.filter { "PickInstantDialogs(" in it })
        assertEntry(bar, "if (selSnooze) {", "onClick = { selMenu = false; selSnooze = false; viewModel.pickInstant.open(PickInstantOpen.SELECTION) },")
    }

    @Test fun `Snoozed draws for a row still listed, at the Scaffold's level, and closes on a row gone`() {
        val lines = codeLines(read(SNOOZED_SCREEN))
        assertEquals(
            "the dialogs must be drawn for the listed row whose key is the target, and none other. Lines found:",
            listOf(
                "val pickInstant = viewModel.pickInstant",
                "val pickTarget by pickInstant.target.collectAsStateWithLifecycle()",
                "val pickRow = items.firstOrNull { PickInstantOpen.shows(pickTarget, snoozedRowKey(it)) }",
            ),
            lines.filter { it.startsWith("val pickInstant") || it.startsWith("val pickTarget") || it.startsWith("val pickKey") || it.startsWith("val pickRow") || "PickInstantOpen." in it },
        )
        assertEquals(listOf("if (pickRow != null) {"), lines.filter { it.startsWith("if (pickRow") })
        assertDialogs(lines, "onPicked = { at, _, _ -> viewModel.reschedule(pickRow.emailId, pickRow.accountId, at); pickInstant.close() },")
        val dialogs = lines.indexOfFirst { it.startsWith("PickInstantDialogs(") }
        val scaffold = lines.indexOfFirst { it.startsWith("Scaffold(") }
        assertTrue(
            "the dialogs must follow the Scaffold, never be drawn inside it or a row of its list",
            scaffold >= 0 && dialogs > scaffold + block(lines, "Scaffold(").size - 1,
        )
        assertEntry(lines, "DropdownMenu(", "onClick = { presetsOpen = false; pickInstant.open(snoozedRowKey(item)) },")

        val vm = codeLines(read(SNOOZED_VM))
        assertEquals(
            "the list's own emissions must close a target whose row has gone. Lines found:",
            listOf(".onEach { rows -> if (PickInstantOpen.rowGone(pickInstant.target.value, rows.map(::snoozedRowKey))) pickInstant.close() }"),
            vm.filter { "PickInstantOpen.rowGone" in it },
        )
        assertTrue(
            "the holder must exist before the list that reads it",
            vm.indexOfFirst { it.startsWith("val pickInstant") } in 0 until vm.indexOfFirst { it.startsWith("val items") },
        )
        assertEquals(
            listOf("internal fun snoozedRowKey(row: SnoozedListRow): String = MessagePaging.entryKey(row.emailId, row.accountId)"),
            vm.filter { it.startsWith("internal fun snoozedRowKey") },
        )
    }

    /** The dialogs are fed from the holder, dismissed into it, and [onPicked] closes it. */
    private fun assertDialogs(lines: List<String>, onPicked: String) {
        val call = block(lines, "PickInstantDialogs(")
        assertEquals(
            "the dialogs must be fed and dismissed through the holder only. Block: $call",
            listOf(
                "step = pickStep,",
                "chosenDay = pickDay,",
                "onDayChosen = pickInstant::chooseDay,",
                "onDismiss = pickInstant::close,",
                onPicked,
            ),
            call.drop(1).dropLast(1),
        )
        assertEquals(
            listOf(
                "val pickStep by pickInstant.step.collectAsStateWithLifecycle()",
                "val pickDay by pickInstant.day.collectAsStateWithLifecycle()",
            ),
            lines.filter { it.startsWith("val pickStep") || it.startsWith("val pickDay") },
        )
    }

    /** One entry, labelled with the shared string, last in the snooze menu opened by [menuStart]. */
    private fun assertEntry(lines: List<String>, menuStart: String, open: String) {
        val label = "text = { Text(stringResource(R.string.schedule_pick_date_time)) },"
        assertEquals("one entry per menu. Lines found:", listOf(label), lines.filter { "schedule_pick_date_time" in it })
        val presetsLine = lines.indexOfFirst { it.startsWith("snoozePresets(") }
        val menuFrom = (presetsLine downTo 0).firstOrNull { lines[it].startsWith(menuStart) }
        check(menuFrom != null) { "no `$menuStart` above the presets" }
        val menu = block(lines.drop(menuFrom), menuStart)
        val presets = menu.indexOfFirst { it.startsWith("snoozePresets(") }
        val at = menu.indexOf(label)
        assertTrue("the entry must sit in the snooze menu, after the presets. Block: $menu", presets >= 0 && at > presets)
        assertEquals("the entry must open the holder for its own target", open, menu.getOrNull(at + 1))
        assertEquals(
            "the entry must be the last of the menu. Block: $menu",
            emptyList<String>(),
            menu.drop(at + 1).filter { it.startsWith("DropdownMenuItem(") },
        )
    }

    /**
     * The lines of the block opened by the line starting with [start], braces and parentheses
     * balanced. A line that opens on a `}` closing the block ends it, so an `if` stops at its `else`.
     */
    private fun block(lines: List<String>, start: String): List<String> {
        val from = lines.indexOfFirst { it.startsWith(start) }
        check(from >= 0) { "no line starts with `$start`" }
        var depth = 0
        val out = mutableListOf<String>()
        for (line in lines.drop(from)) {
            out += line
            if (out.size > 1 && line.startsWith("}") && depth == 1) break
            depth += line.count { it == '{' || it == '(' } - line.count { it == '}' || it == ')' }
            if (depth <= 0) break
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
        const val INBOX_SCREEN = "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"
        const val SELECTION_BAR = "app/src/main/kotlin/app/sterna/ui/inbox/SelectionTopBar.kt"
        const val SNOOZED_SCREEN = "app/src/main/kotlin/app/sterna/ui/snoozed/SnoozedScreen.kt"
        const val SNOOZED_VM = "app/src/main/kotlin/app/sterna/ui/snoozed/SnoozedViewModel.kt"
    }
}
