package app.sterna.ui.components

import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, and a last resort: nothing in this module composes, so the accessibility tree of a
 * pending account is never built here, no screen reader was listened to and the action was never
 * invoked. What is pinned is that the row's clickable node carries one named action, and that the
 * callback behind it is the one a completed swipe ends on.
 *
 * Holes of `CONTRIBUTING.md` that apply: 1 (`onDismiss`, `dismiss` and `dismissLabel` are only
 * names: the three hand-overs are pinned whole, but a lambda parameter of the same name around any
 * of them would rebind it and stay green), 2 (the `true` the action returns is pinned, the
 * dismissal it reports is not), 3 (the `.semantics {` read is the one right under the row's
 * `clickable`, and nothing sees a branch drawn around the row or the swipe), and 4 (whether the
 * action lands on the node the screen reader focuses, merged with the clickable, is never
 * observed).
 */
class PendingAccountDismissActionWiringTest {

    @Test fun `the action sits on the row's clickable node, named Dismiss`() {
        val lines = function("private fun PendingAccountRow(")
        assertEquals(
            "a pending account can only be swiped away: the node that carries its click must also " +
                "carry a custom action, or a screen reader has no way to dismiss it",
            EXPECTED_ACTION,
            slice(lines, lines.indexOfFirst { it == ".clickable(onClick = onClick)" }, EXPECTED_ACTION.size),
        )
        assertEquals(
            "the action is named by the string the swipe reveals",
            listOf("val dismissLabel = stringResource(R.string.import_pending_dismiss)"),
            lines.filter { it.startsWith("val dismissLabel") },
        )
        assertEquals(
            "PendingAccountRow must be handed its dismissal",
            "private fun PendingAccountRow(account: StoredAccount, onClick: () -> Unit, onDismiss: () -> Unit) {",
            lines.first(),
        )
    }

    @Test fun `the row is handed the dismissal of the swipe around it`() {
        val section = function("fun PendingImportAccountsSection(")
        assertEquals(
            "the row's action and the swipe must end on the same callback, or only one of them " +
                "offers Undo",
            EXPECTED_HAND_OVER,
            slice(section, section.indexOfFirst { it.startsWith("SwipeToDismissRow(") }, EXPECTED_HAND_OVER.size),
        )
    }

    @Test fun `the swipe hands out the callback it ends on itself`() {
        val swipe = function("private fun SwipeToDismissRow(")
        assertEquals(
            listOf("val currentOnDismiss by rememberUpdatedState(onDismiss)"),
            swipe.filter { it.startsWith("val currentOnDismiss") },
        )
        assertEquals(
            "one completed swipe, one hand-over to the content, and nothing else dismisses",
            listOf("currentOnDismiss()", "content(currentOnDismiss)"),
            swipe.filter { "currentOnDismiss" in it && !it.startsWith("val ") },
        )
    }

    private fun slice(lines: List<String>, at: Int, size: Int): List<String> =
        if (at < 0) emptyList() else lines.subList(at, minOf(at + size, lines.size))

    private fun function(signature: String): List<String> = DeclarationSource.functionBody(
        DeclarationSource.file("app/src/main/kotlin/app/sterna/ui/components/PendingImportAccounts.kt"),
        signature,
    )

    private companion object {
        val EXPECTED_ACTION = listOf(
            ".clickable(onClick = onClick)",
            ".semantics {",
            "customActions = listOf(CustomAccessibilityAction(dismissLabel) { onDismiss(); true })",
            "}",
        )
        val EXPECTED_HAND_OVER = listOf(
            "SwipeToDismissRow(onDismiss = { onDismiss(account) }) { dismiss ->",
            "PendingAccountRow(account = account, onClick = { onSignIn(account) }, onDismiss = dismiss)",
            "}",
        )
    }
}
