package app.sterna.ui.settings

import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, and a last resort: nothing in this module composes, so no button is drawn and no
 * accessibility focus was followed. That a disabled `IconButton` stays a node a reader can rest on
 * is the library's, read and not run.
 */
class MoveSlotWiringTest {

    @Test fun `a move button that may not move is disabled, never removed`() {
        assertEquals(
            "at the edge of the list the button under the accessibility focus must stay composed, " +
                "disabled and still named: swapped for a spacer, the node dies right after the " +
                "press and the focus jumps elsewhere",
            listOf(
                "internal fun MoveSlot(enabled: Boolean, icon: ImageVector, description: String, onClick: () -> Unit) {",
                "IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {",
                "Icon(icon, contentDescription = description)",
                "}",
            ),
            DeclarationSource.functionBody(
                DeclarationSource.file("app/src/main/kotlin/app/sterna/ui/settings/FolderManagementScreen.kt"),
                "internal fun MoveSlot(",
            ),
        )
    }
}
