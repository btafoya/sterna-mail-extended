package app.sterna.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and disclaimer as [UnreadWidgetDeclarationTest].
 * A corner radius is drawn by the LAUNCHER's process out of a `res/drawable`; nothing in this module
 * can inflate it, and there is no Robolectric here.
 *
 * The rule: the cell's corner is one value, declared twice, and read by one drawable that four
 * layout roots share. Under API 31 it is `16dp`; from API 31 it is
 * `android:dimen/system_app_widget_background_radius`, the radius the launcher gives every other
 * widget on the home screen. The indirection through `res/values` exists so that the API split
 * costs a values file instead of a second copy of the drawable.
 *
 * ⭐ The `values-v31` twin is the half nothing on screen can report. Lose it, or let its value
 * drift, and the cell keeps a perfectly round corner on Android 12+ — just not the launcher's. No
 * crash, no blank, nothing to see; only a shape that no longer matches its neighbours.
 *
 * ⛔ Never a `contains` on a line: a substring is blind to every mutation that LENGTHENS what it
 * matches. Every comparison below is of WHOLE lines, so a second declaration, a longer literal or a
 * changed reference all redden.
 *
 * ⚠ Comments are stripped by [DeclarationSource.codeLines] before anything is matched — otherwise
 * this rule would pin the prose written in the drawable to explain it.
 */
class WidgetCornerRadiusTest {

    @Test fun `the widget cell's corner is the dimen, 16dp below API 31 and the launcher's own radius from 31`() {
        assertEquals(
            "app/src/main/res/drawable/widget_unread_background.xml must round its corners through " +
                "@dimen/widget_corner_radius, never a hard-coded length. The dimen is what carries " +
                "the API split: res/values says 16dp, res/values-v31 says " +
                "@android:dimen/system_app_widget_background_radius. Written in place here, the " +
                "cell would ignore the launcher's own corner on Android 12+.",
            listOf(CORNERS),
            codeLinesOf(DRAWABLE).filter { it.startsWith("<corners") },
        )

        assertEquals(
            "the corner radius must be declared in BOTH values directories, with these exact " +
                "values. ⭐ res/values-v31 is the one nothing on screen can report: without it (or " +
                "with a drifted value) the cell stays rounded on Android 12+ at 16dp, so it simply " +
                "stops matching the corner the launcher gives every other widget — no crash, no " +
                "blank, nothing to notice. JUnit prints the rule as `expected` and what the two " +
                "files declare as `was`; `$ABSENT` means the file itself is gone.",
            mapOf(
                "values/dimens.xml" to listOf("<dimen name=\"widget_corner_radius\">16dp</dimen>"),
                "values-v31/dimens.xml" to listOf(
                    "<dimen name=\"widget_corner_radius\">@android:dimen/system_app_widget_background_radius</dimen>",
                ),
            ),
            DIMENS.associateWith { declarationsIn(it) },
        )

        assertEquals(
            "all four widget roots — both widgets and both picker previews — must take their " +
                "background from @drawable/widget_unread_background, the single file that carries " +
                "the rounded corner and the surface colour. A root that names another drawable, or " +
                "none, loses the corner on its own while every other rule here stays green.",
            ROOTS.associateWith { listOf(BACKGROUND) },
            ROOTS.associateWith { backgroundsIn(it) },
        )
    }

    /** The `<dimen name="widget_corner_radius">…` lines of [path], whole, or [ABSENT] if the file is gone. */
    private fun declarationsIn(path: String): List<String> {
        val file = File(DeclarationSource.ROOT, "app/src/main/res/$path")
        if (!file.isFile) return listOf(ABSENT)
        return codeLinesOf(file).filter { "name=\"widget_corner_radius\"" in it }
    }

    /** The `android:background=…` lines of the layout at [path], whole, or [ABSENT] if the file is gone. */
    private fun backgroundsIn(path: String): List<String> {
        val file = File(DeclarationSource.ROOT, "app/src/main/res/$path")
        if (!file.isFile) return listOf(ABSENT)
        return codeLinesOf(file).filter { it.startsWith("android:background=") }
    }

    private fun codeLinesOf(file: File): List<String> = DeclarationSource.codeLines(file)

    private companion object {
        const val ABSENT = "(no such file)"

        const val CORNERS = "<corners android:radius=\"@dimen/widget_corner_radius\" />"

        const val BACKGROUND = "android:background=\"@drawable/widget_unread_background\""

        val DIMENS = listOf("values/dimens.xml", "values-v31/dimens.xml")

        /**
         * Every root that wears the cell's surface: the two widgets, the two picker previews, and
         * the counter's enlarged layout — which the launcher swaps in on a cell dragged large, so a
         * background missing there is a square-cornered cell for anyone who resized theirs.
         */
        val ROOTS = listOf(
            "layout/widget_unread.xml",
            "layout/widget_unread_accounts.xml",
            "layout/widget_unread_preview.xml",
            "layout/widget_recent.xml",
            "layout/widget_recent_preview.xml",
        )

        val DRAWABLE: File by lazy { DeclarationSource.file("app/src/main/res/drawable/widget_unread_background.xml") }
    }
}
