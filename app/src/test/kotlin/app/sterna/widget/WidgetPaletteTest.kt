package app.sterna.widget

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.sterna.ui.theme.ArcticColorScheme
import app.sterna.ui.theme.PelagicColorScheme
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The widget wears the APP's palette, not the splash screen's.
 *
 * A home-screen widget is drawn by RemoteViews in the launcher's process, so it cannot read the
 * Compose scheme the app picks at runtime: its colours are frozen in the `colors.xml` of `res/values` and `res/values-night`
 * and are only ever compared to the app by eye. That is how they drifted onto the splash nuancier —
 * in light mode not one of the three existed anywhere in `Color.kt`, and nothing went red.
 *
 * ⛔ The expected values are NOT written here. Each resource is mapped to the Material ROLE it
 * stands for (that table is the rule, and it has to live somewhere), and the value is taken by
 * EXECUTING the real scheme objects: `ArcticColorScheme.surface.toArgb()`, etc. Re-tune the palette
 * in `Color.kt` and this test asks for the new value on its own; copy hex literals in here instead
 * and it would only ever prove that two files agree on a colour that matches nothing on screen.
 *
 * ⛔ And never a `contains` on the line: a substring is blind to every mutation that LENGTHENS what
 * it matches. What is compared below is the WHOLE extracted value, `#RRGGBB` and nothing else, so
 * `#2F5E59A0`, `#FF2F5E59` or a second declaration of the same name all redden.
 *
 * The second rule here is [`the widget layouts spend each colour on the role it was declared for`]:
 * a right value in `colors.xml` proves nothing about WHICH view reads it, and the palette rule
 * above stays green while every line of the list is painted with the accent.
 */
class WidgetPaletteTest {

    /** resource name → the Material role it stands for. The rule; the values come from the scheme. */
    private val roles: Map<String, ColorScheme.() -> Color> = mapOf<String, ColorScheme.() -> Color>(
        "widget_unread_background" to { surface },
        "widget_unread_count" to { primary },
        "widget_row_primary" to { onSurface },
        "widget_unread_label" to { onSurfaceVariant },
    )

    @Test fun `the light widget colours are the Arctic scheme's surface, primary, onSurface and onSurfaceVariant`() {
        assertPalette("app/src/main/res/values/colors.xml", ArcticColorScheme, "Arctic (light)")
    }

    @Test fun `the dark widget colours are the Pelagic scheme's surface, primary, onSurface and onSurfaceVariant`() {
        assertPalette("app/src/main/res/values-night/colors.xml", PelagicColorScheme, "Pelagic (dark)")
    }

    /**
     * WHICH view reads WHICH colour — the half the palette rule cannot see.
     *
     * `widget_unread_count` is the ACCENT (teal): the big figure of the counter widget, its picker
     * copy, and the base a row's account mark is retinted over. `widget_row_primary` is the primary TEXT of a row
     * (`onSurface`, near-black in light mode), because that is what the app's own list does —
     * `EmailListItem` writes the sender with no `color =` at all and so inherits `LocalContentColor`,
     * i.e. `onSurface`. The two were one resource until the whole left half of the widget's list
     * came out teal against an app that draws it dark grey.
     *
     * ⛔ Nothing here is a `contains`: each colour-bearing line is pinned WHOLE, next to the
     * `android:id` / `android:text` that says which view it belongs to and in document order. So
     * pointing a row back at the accent, dragging the big figure onto the row colour, adding a
     * second `android:textColor`, or moving one between elements all redden — and the printed diff
     * names the view.
     *
     * The two drawables are in here for the same reason the layouts are: each is the sole consumer
     * of one resource, so nothing else in this file would notice the swap.
     */
    @Test fun `the widget layouts spend each colour on the role it was declared for`() {
        assertEquals(
            "a widget view reads the wrong colour resource. TWO roles, deliberately split: " +
                "@color/widget_unread_count is the ACCENT (Material `primary`, teal) and belongs to " +
                "the big unread figure, its picker copy and the row's account mark; " +
                "@color/widget_row_primary is the primary TEXT of a list row (Material `onSurface`), " +
                "matching what EmailListItem draws in the app. ⛔ Do not merge them back into one " +
                "resource: that is exactly the defect, every row name came out teal. " +
                "JUnit prints the pinned rule as `expected` and what the layouts declare as `was`.",
            PINNED,
            PINNED.keys.associateWith { colourAttributes(it) },
        )
    }

    private fun assertPalette(path: String, scheme: ColorScheme, schemeName: String) {
        val file = DeclarationSource.file(path)
        val declared = DECLARATION.findAll(DeclarationSource.codeLines(file).joinToString("\n"))
            .map { it.groupValues[1] to it.groupValues[2].uppercase() }
            .filter { (name, _) -> name.startsWith("widget_") }
            .toList()

        assertEquals(
            "$path declares a widget colour twice — aapt keeps the LAST one, so a stale first " +
                "declaration would sit above a correct one and nothing would show it: $declared",
            declared.size,
            declared.toMap().size,
        )
        assertEquals(
            "the widget's colours must be the $schemeName scheme's roles, read from " +
                "app/src/main/kotlin/app/sterna/ui/theme/Color.kt and not from the splash palette. " +
                "Roles: " + roles.keys.joinToString { "$it=${roleName(it)}" } +
                ". JUnit prints the scheme's roles as `expected` and what $path declares as `was`.",
            roles.mapValues { (_, role) -> hex(scheme.role()) },
            declared.toMap(),
        )
    }

    private fun roleName(resource: String) = when (resource) {
        "widget_unread_background" -> "surface"
        "widget_unread_count" -> "primary (the ACCENT)"
        "widget_row_primary" -> "onSurface (a row's text)"
        else -> "onSurfaceVariant"
    }

    /**
     * Every line of [path] that names a `@color/widget_…` in an `android:textColor` or
     * `android:color`, in document order, each prefixed by its element's tag and by the
     * `android:id` / `android:text` attributes seen above it inside that same element.
     */
    private fun colourAttributes(path: String): List<String> {
        val lines = DeclarationSource.codeLines(DeclarationSource.file("app/src/main/res/$path"))
        val found = mutableListOf<String>()
        var tag = ""
        var identity = mutableListOf<String>()
        for (line in lines) {
            if (line.startsWith("<") && !line.startsWith("</")) {
                tag = line.drop(1).takeWhile { it.isLetterOrDigit() }
                identity = mutableListOf()
            }
            if (line.startsWith("android:id=") || line.startsWith("android:text=")) identity += line
            if (!WIDGET_COLOUR.containsMatchIn(line)) continue
            found += if (line.startsWith("<")) line
            else (listOf("<$tag") + identity + line).joinToString(" ")
        }
        return found
    }

    /** `#RRGGBB` when the role is opaque, `#AARRGGBB` when it is not — a translucent widget colour must redden. */
    private fun hex(c: Color): String {
        val argb = c.toArgb()
        return if ((argb ushr 24) and 0xFF == 0xFF) String.format("#%06X", argb and 0xFFFFFF)
        else String.format("#%08X", argb)
    }

    private companion object {
        val DECLARATION = Regex("""<color name="([A-Za-z0-9_]+)">(#[0-9A-Fa-f]+)</color>""")

        /** An `android:textColor` (or the dot's `android:color`) naming one of the widget resources. */
        val WIDGET_COLOUR = Regex("android:(?:textColor|color)=\"@color/widget_[A-Za-z0-9_]+\"")

        /** The rule: file → its colour-bearing lines, whole, in order. Paths are under `app/src/main/res/`. */
        val PINNED: Map<String, List<String>> = mapOf(
            "layout/widget_unread.xml" to listOf(
                "<TextView android:id=\"@+id/widget_unread_count\" android:text=\"…\" android:textColor=\"@color/widget_unread_count\"",
                "<TextView android:id=\"@+id/widget_unread_label\" android:text=\"@string/app_name\" android:textColor=\"@color/widget_unread_label\"",
            ),
            "layout/widget_unread_preview.xml" to listOf(
                "<TextView android:id=\"@+id/widget_unread_count\" android:text=\"12\" android:textColor=\"@color/widget_unread_count\"",
                "<TextView android:id=\"@+id/widget_unread_label\" android:text=\"@string/inbox_all_inboxes\" android:textColor=\"@color/widget_unread_label\"",
            ),
            "layout/widget_recent.xml" to listOf(
                "<TextView android:id=\"@+id/widget_recent_notice\" android:text=\"@string/app_name\" android:textColor=\"@color/widget_unread_label\"",
            ),
            "layout/widget_recent_row.xml" to listOf(
                "<TextView android:id=\"@+id/widget_recent_row_primary\" android:textColor=\"@color/widget_row_primary\"",
                "<TextView android:id=\"@+id/widget_recent_row_secondary\" android:textColor=\"@color/widget_unread_label\"",
                "<TextView android:id=\"@+id/widget_recent_row_date\" android:textColor=\"@color/widget_unread_label\"",
            ),
            "layout/widget_recent_preview.xml" to listOf(
                "<TextView android:text=\"Marie Dupont\" android:textColor=\"@color/widget_row_primary\"",
                "<TextView android:text=\"09:12\" android:textColor=\"@color/widget_unread_label\"",
                "<TextView android:text=\"Tomasz Nowak\" android:textColor=\"@color/widget_row_primary\"",
                "<TextView android:text=\"08:41\" android:textColor=\"@color/widget_unread_label\"",
                "<TextView android:text=\"Ada Lindqvist\" android:textColor=\"@color/widget_row_primary\"",
                "<TextView android:text=\"07:58\" android:textColor=\"@color/widget_unread_label\"",
            ),
            // ⭐ The row's 8 dp head mark. It used to be the unread dot; unread is shown by weight
            // now and the same column carries the ACCOUNT mark, whose flat colour is only the base
            // a runtime setColorFilter paints over. Same resource, same reason to pin it: it is the
            // sole consumer, so nothing else here would notice the swap.
            "drawable/widget_recent_account_dot.xml" to listOf(
                "<solid android:color=\"@color/widget_unread_count\" />",
            ),
            // ⭐ The cell's own surface, and the ONLY consumer of widget_unread_background. Point it
            // at the accent instead and every cell plus both picker previews turn solid teal, with
            // colors.xml still declaring four perfectly correct values.
            "drawable/widget_unread_background.xml" to listOf(
                "<solid android:color=\"@color/widget_unread_background\" />",
            ),
        )
    }
}
