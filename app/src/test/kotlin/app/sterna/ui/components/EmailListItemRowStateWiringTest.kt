package app.sterna.ui.components

import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, and a last resort: nothing in this module composes, so the accessibility tree of a
 * mail row is never built here and no screen reader was listened to. What is pinned is that the
 * row's own node, the one its `combinedClickable` makes, carries the three states the eye reads
 * off the row: selected, unread and starred as one phrase through `rowStateDescription`, which
 * [RowStateDescriptionTest] runs, and `selected` as the semantic boolean on a selected row only.
 *
 * Holes of `CONTRIBUTING.md` that apply: 1 (`selected`, `unread`, `email.isFlagged` and the three
 * labels are only names: the labels are each counted as one `val` in the body, but a lambda
 * parameter of the same name around the row would rebind any of them and stay green), 3 (the
 * `semantics` block is pinned whole and right under the row's `combinedClickable`, but nothing
 * sees a branch drawn around the row), and 4 (what the merged node ends up exposing is never
 * observed: neither that a screen reader speaks the phrase, nor the wording of the three strings,
 * read by name only).
 */
class EmailListItemRowStateWiringTest {

    @Test fun `the three labels are the states of one message, not a view's title`() {
        assertEquals(
            listOf("val selectedLabel = stringResource(R.string.a11y_selected)"),
            body().filter { it.startsWith("val selectedLabel") },
        )
        assertEquals(
            listOf("val unreadLabel = stringResource(R.string.a11y_unread)"),
            body().filter { it.startsWith("val unreadLabel") },
        )
        assertEquals(
            listOf("val starredLabel = stringResource(R.string.a11y_flagged)"),
            body().filter { it.startsWith("val starredLabel") },
        )
        assertEquals(
            "`inbox_unread_view` titles the unread view and is a plural in several languages: " +
                "a row describes one message",
            emptyList<String>(),
            body().filter { "inbox_unread_view" in it },
        )
    }

    @Test fun `the phrase is built from the row's own selected, unread and starred states`() {
        val lines = body()
        val at = lines.indexOf("val rowStates = rowStateDescription(")
        assertEquals(
            "the row's states go through `rowStateDescription` once, each state beside its own " +
                "label: `unread` is the row's parameter, the thread's in conversation view, and " +
                "the star is the one the glyph draws",
            listOf(
                "val rowStates = rowStateDescription(",
                "selected = selected,",
                "unread = unread,",
                "starred = email.isFlagged,",
                "selectedLabel = selectedLabel,",
                "unreadLabel = unreadLabel,",
                "starredLabel = starredLabel,",
                ")",
            ),
            if (at < 0) emptyList() else lines.subList(at, at + 8),
        )
        assertEquals(1, lines.count { "rowStateDescription(" in it })
    }

    @Test fun `the row's node carries the selection and that phrase`() {
        val lines = body()
        val at = lines.indexOf(ROW_CLICKABLE)
        assertEquals(
            "the states sit on the node the row's `combinedClickable` makes, the one a screen " +
                "reader lands on. Compose stops speaking `selected` once a `stateDescription` " +
                "is set, so the phrase is set on every row and says the selection itself; the " +
                "boolean stays for whoever reads the tree, on a selected row only, " +
                "`selected = false` being spoken as \"Not selected\"",
            listOf(
                ".semantics {",
                "if (selected) this.selected = true",
                "rowStates?.let { stateDescription = it }",
                "}",
            ),
            if (at < 0) emptyList() else lines.subList(at + 1, at + 5),
        )
    }

    private fun body(): List<String> = DeclarationSource.functionBody(
        DeclarationSource.file("app/src/main/kotlin/app/sterna/ui/components/EmailListItem.kt"),
        "fun EmailListItem(",
    )

    private companion object {
        const val ROW_CLICKABLE =
            ".combinedClickable(onClick = onClick, onLongClick = enterSelection, onLongClickLabel = selectLabel)"
    }
}
