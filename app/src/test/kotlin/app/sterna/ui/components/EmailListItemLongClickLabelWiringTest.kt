package app.sterna.ui.components

import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, and a last resort: nothing in this module composes, so the accessibility tree of a
 * mail row is never built here and no screen reader was listened to. What is pinned is that the
 * row's `combinedClickable` names its long-press with `a11y_select`, and only where a long-press
 * exists. Unnamed, the gesture is announced with the platform's generic hint, and it is the one
 * way into multi-select, hence to archive and delete from the list, once the reader has removed
 * the row monogram.
 *
 * Holes of `CONTRIBUTING.md` that apply: 1 (`selectLabel` and `enterSelection` are only names:
 * each is counted as one `val` in the body, but a lambda parameter of the same name around the row
 * would rebind it and stay green), 3 (the `val` and the modifier are pinned whole, not their place:
 * nothing sees a branch drawn around the row, and the modifier is only known to follow the row's
 * background), and 4 (the label the node ends up exposing is never observed, nor is the wording of
 * `a11y_select`, which is read by name only).
 */
class EmailListItemLongClickLabelWiringTest {

    @Test fun `the label is the select string, and only where a long-press exists`() {
        assertEquals(
            "a row with no long-press must not announce one: the label is null unless " +
                "`enterSelection` is there to answer it",
            listOf("val selectLabel = if (enterSelection != null) stringResource(R.string.a11y_select) else null"),
            body().filter { it.startsWith("val selectLabel") },
        )
        assertEquals(
            "the long-press the label is conditioned on must be the one the row is given",
            listOf("val enterSelection = onLongClick?.let { enter ->"),
            body().filter { it.startsWith("val enterSelection") },
        )
    }

    @Test fun `the row's long-press carries that label`() {
        val lines = body()
        assertEquals(
            "EmailListItem has one combinedClickable, pinned whole: without `onLongClickLabel` a " +
                "screen reader announces the long-press that opens multi-select with a generic hint",
            listOf(EXPECTED_CLICKABLE),
            lines.filter { it.startsWith(".combinedClickable(") },
        )
        assertEquals(
            "that combinedClickable is the row's own, right under its background",
            ".background(rowColor)",
            lines.getOrNull(lines.indexOf(EXPECTED_CLICKABLE) - 1),
        )
    }

    private fun body(): List<String> = DeclarationSource.functionBody(
        DeclarationSource.file("app/src/main/kotlin/app/sterna/ui/components/EmailListItem.kt"),
        "fun EmailListItem(",
    )

    private companion object {
        const val EXPECTED_CLICKABLE =
            ".combinedClickable(onClick = onClick, onLongClick = enterSelection, onLongClickLabel = selectLabel)"
    }
}
