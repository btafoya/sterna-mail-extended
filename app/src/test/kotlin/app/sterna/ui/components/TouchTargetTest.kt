package app.sterna.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every tappable thing is at least 48 dp on both sides. The arithmetic of the overflow is executed;
 * nothing here can lay a composable out, so each site is then pinned as a run of whole lines, in
 * modifier order, since the order is what decides whether a padding or a clip is inside the target.
 *
 * Holes, by CONTRIBUTING's numbering. 1: a declared drawn size (`SealSize`, `drawn =`) says nothing
 * about what is really drawn. 3: a pinned run says nothing about the branch it sits in, and sites 7,
 * 8 and 9 sit in one. 4: nothing here holds a measured size, nor sees an ancestor that clips the
 * overflowing part of a target or a neighbour that wins its hit test. Those are read on a device.
 */
class TouchTargetTest {

    @Test fun `the minimum target is 48 dp`() {
        assertEquals(48.dp, MinTouchTarget)
    }

    @Test fun `the overflow is half of what a drawn side lacks`() {
        assertEquals(12.dp, touchOverflow(24.dp))
        assertEquals(7.dp, touchOverflow(34.dp))
        assertEquals(6.dp, touchOverflow(36.dp))
        assertEquals(4.dp, touchOverflow(40.dp))
    }

    @Test fun `a side already at the minimum or over it gets no overflow`() {
        assertEquals(0.dp, touchOverflow(48.dp))
        assertEquals(0.dp, touchOverflow(56.dp))
    }

    @Test fun `a drawn side plus its two overflows reaches the minimum`() {
        listOf(16.dp, 24.dp, 34.dp, 36.dp, 40.dp, 47.dp, 48.dp, 60.dp).forEach { drawn ->
            assertTrue("$drawn ends at ${drawn + touchOverflow(drawn) * 2}", drawn + touchOverflow(drawn) * 2 >= MinTouchTarget)
        }
    }

    @Test fun `the enlarged content is reported at the size it had without the overflow`() {
        assertEquals(96, overflowReportedExtent(measured = 132, overflowPx = 18))
        assertEquals(132, overflowReportedExtent(measured = 132, overflowPx = 0))
        assertEquals(0, overflowReportedExtent(measured = 10, overflowPx = 18))
    }

    @Test fun `the overflow modifier is declared once, and reports through the executed arithmetic`() {
        val declarations = File(root, UI).walkTopDown().filter { it.extension == "kt" }
            .flatMap { file -> codeLines(file).filter { "fun Modifier.touchTargetOverflow(" in it }.map { "${file.name}: $it" } }
            .toList().sorted()
        assertEquals(
            listOf(
                "TouchTarget.kt: internal fun Modifier.touchTargetOverflow(horizontal: Dp, vertical: Dp): Modifier = layout { measurable, constraints ->",
                "TouchTarget.kt: internal fun Modifier.touchTargetOverflow(overflow: Dp): Modifier = touchTargetOverflow(overflow, overflow)",
            ),
            declarations,
        )
        assertRun(
            "$UI/components/TouchTarget.kt",
            "internal fun Modifier.touchTargetOverflow(horizontal: Dp, vertical: Dp): Modifier = layout { measurable, constraints ->",
            "val x = horizontal.roundToPx()",
            "val y = vertical.roundToPx()",
            "val placeable = measurable.measure(constraints.offset(2 * x, 2 * y))",
            "layout(overflowReportedExtent(placeable.width, x), overflowReportedExtent(placeable.height, y)) {",
            "placeable.place(-x, -y)",
        )
    }

    @Test fun `site 1, the script selector is 48 dp on both sides`() {
        assertRun(
            "$UI/settings/FiltersScreen.kt",
            "private fun ScriptSelector(",
            ".clickable(enabled = enabled, onClickLabel = choose) { expanded = true }",
            ".sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget),",
        )
    }

    @Test fun `site 2, the move picker's account row is 48 dp high`() {
        assertRun(
            "$UI/inbox/MoveAccountRow.kt",
            "internal fun MoveAccountRow(",
            ".fillMaxWidth()",
            ".clickable { open = true }",
            ".semantics(mergeDescendants = true) { role = Role.Button }",
            ".heightIn(min = MinTouchTarget)",
            ".padding(horizontal = 16.dp, vertical = 8.dp),",
        )
    }

    @Test fun `site 3, the identity row's head is 48 dp high`() {
        assertRun(
            "$UI/settings/SettingsScreen.kt",
            "private fun IdentityRowHeader(",
            ".fillMaxWidth()",
            ".clickable(onClick = onToggle)",
            ".heightIn(min = MinTouchTarget)",
            ".padding(vertical = 8.dp),",
        )
    }

    @Test fun `site 4, the default sender radio is 48 dp high and its top gap selects`() {
        assertRun(
            "$UI/settings/SettingsScreen.kt",
            "private fun DefaultIdentityRadioRow(",
            ".fillMaxWidth()",
            ".selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)",
            ".heightIn(min = MinTouchTarget)",
            ".padding(top = 8.dp),",
        )
    }

    @Test fun `site 5, the pairing code copies from a 48 dp line on both screens`() {
        listOf(
            "$UI/settings/SettingsScreen.kt" to "private fun InlineDeviceApproval(",
            "$UI/connect/ConnectScreen.kt" to "private fun DeviceApprovalContent(",
        ).forEach { (path, anchor) ->
            assertRun(
                path,
                anchor,
                ".fillMaxWidth()",
                ".clickable {",
                "clipboard.setText(AnnotatedString(userCode))",
                "Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()",
                "}",
                ".heightIn(min = MinTouchTarget),",
            )
        }
    }

    @Test fun `site 6, the failed send banner is 48 dp high`() {
        assertRun(
            "$UI/inbox/InboxScreen.kt",
            "private fun OutboxFailureBanner(",
            "modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = MinTouchTarget),",
        )
    }

    @Test fun `site 7, the drawer chevron overflows to 48 dp and reports its 24`() {
        assertEquals(1, codeLines(File(root, "$UI/inbox/InboxScreen.kt")).count { it == "private val FolderChevronSize = 24.dp" })
        assertRun(
            "$UI/inbox/InboxScreen.kt",
            "if (node.hasChildren) {",
            "modifier = Modifier",
            ".touchTargetOverflow(touchOverflow(FolderChevronSize))",
            ".clickable {",
            "viewModel.setFolderCollapsed(mailbox.id, !collapsed)",
            "}",
            ".padding(touchOverflow(FolderChevronSize))",
            ".size(FolderChevronSize),",
            ")",
            "} else {",
            "Spacer(Modifier.width(24.dp))",
        )
    }

    @Test fun `site 8, the conversation pill overflows to 48 dp around a drawing that does not grow`() {
        val row = codeLines(File(root, "$UI/components/EmailListItem.kt"))
        assertRun(
            "$UI/components/EmailListItem.kt",
            "private val PillPaddingX",
            "private val PillPaddingX = 8.dp",
            "private val PillPaddingY = 4.dp",
            "private val PillGap = 2.dp",
            "private val PillChevron = 16.dp",
            "private val PillTouchOverflowX = touchOverflow(drawn = PillPaddingX * 2 + PillGap + PillChevron)",
            "private val PillTouchOverflowY = touchOverflow(drawn = PillPaddingY * 2 + PillChevron)",
        )
        assertEquals(
            "the pill's drawn minimum is only true while the row is built from the same four sizes",
            listOf("Spacer(Modifier.width(PillGap))", "modifier = Modifier.size(PillChevron).rotate(rotation),"),
            row.filter { "PillGap)" in it || "PillChevron)." in it },
        )
        assertRun(
            "$UI/components/EmailListItem.kt",
            "private fun ThreadPill(",
            "val base = Modifier",
            ".clip(MaterialTheme.shapes.small)",
            ".background(fill)",
            "val pillModifier = if (onToggleExpand != null) {",
            "Modifier",
            ".touchTargetOverflow(horizontal = PillTouchOverflowX, vertical = PillTouchOverflowY)",
            ".clickable(interactionSource = interaction, indication = null, onClick = onToggleExpand)",
            ".semantics {",
            "contentDescription = pillLabel",
            "stateDescription = stateLabel",
            "role = Role.Button",
            "}",
            ".padding(horizontal = PillTouchOverflowX, vertical = PillTouchOverflowY)",
            ".then(base)",
            ".indication(interaction, LocalIndication.current)",
            ".padding(horizontal = PillPaddingX, vertical = PillPaddingY)",
            "} else {",
            "base.padding(horizontal = 6.dp, vertical = 1.dp)",
            "}",
        )
    }

    @Test fun `site 9, the signature seal overflows to 48 dp and reports its 36`() {
        assertEquals(1, codeLines(File(root, "$UI/message/MessageScreen.kt")).count { it == "private val SealSize = 36.dp" })
        assertRun(
            "$UI/message/MessageScreen.kt",
            "if (decrypted != null && seal != null) {",
            "Box(",
            "modifier = Modifier",
            ".touchTargetOverflow(touchOverflow(SealSize))",
            ".size(MinTouchTarget)",
            ".clickable(role = Role.Button) { signatureSheet.open(msg.id, msg.header.accountId) },",
            "contentAlignment = Alignment.Center,",
        )
    }

    /** The first run of [expected] after the line opening with [anchor], whole trimmed lines, comments left out. */
    private fun assertRun(path: String, anchor: String, vararg expected: String) {
        val lines = codeLines(File(root, path))
        val from = lines.indexOfFirst { it.startsWith(anchor) }
        check(from >= 0) { "$path no longer has a line starting with `$anchor`" }
        val at = (from until lines.size).firstOrNull { lines[it] == expected.first() }
            ?: error("$path has no line `${expected.first()}` after `$anchor`")
        assertEquals(
            "$path, after `$anchor`",
            expected.toList(),
            lines.subList(at, minOf(at + expected.size, lines.size)),
        )
    }

    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        const val UI = "app/src/main/kotlin/app/sterna/ui"
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "$UI/components/RowMonogram.kt").isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }
    }
}
