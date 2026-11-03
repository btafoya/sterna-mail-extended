package app.sterna.widget

import app.sterna.widget.DeclarationSource.element
import app.sterna.widget.DeclarationSource.file
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, not a behaviour test: a JVM test cannot lay out a launcher's `RemoteViews`. What it
 * pins is the one attribute that decides who receives a touch below the last row of a list widget.
 *
 * Holes 2 and 3 of CONTRIBUTING apply, in their layout form: the pinned attribute says nothing about
 * the rest of the element nor about its parent. A bottom padding on the list, or a weighted list in
 * a vertical `LinearLayout`, gives it the whole cell back and stays green here.
 */
class RecentWidgetListHeightLintTest {

    @Test fun `the list wraps its rows, leaving the empty part of the cell to the root`() {
        assertEquals(
            "widget_recent_list must be android:layout_height=\"wrap_content\". At match_parent " +
                "the ListView covers the whole cell and swallows a touch below its last row, so " +
                "it never reaches widget_recent_root: on the Unread messages widget, tapping the " +
                "empty part of a cell with two rows opens nothing.",
            listOf("android:layout_height=\"wrap_content\""),
            element(LAYOUT, "android:id=\"@+id/widget_recent_list\"")
                .filter { it.startsWith("android:layout_height=") },
        )
    }

    private companion object {
        val LAYOUT = file("app/src/main/res/layout/widget_recent.xml")
    }
}
