package app.sterna.widget

import app.sterna.widget.DeclarationSource.blockContaining
import app.sterna.widget.DeclarationSource.codeLines
import app.sterna.widget.DeclarationSource.element
import app.sterna.widget.DeclarationSource.file
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — the latest-messages widget's half of what
 * [UnreadWidgetDeclarationTest] holds for the counter. A widget declaration is not code this module
 * can run: `res/xml`, the merged manifest and the build script only mean anything inside a build,
 * and there is no Robolectric here.
 *
 * ⚠ The `updatePeriodMillis="0"` rule is NOT here. It lives next door and now sweeps every
 * `appwidget_info_*.xml` of both resource directories, so a third widget is covered on the day it
 * is declared instead of the day someone remembers to copy a test.
 *
 * ⛔ Every rule below is a whole-line comparison. Never a `contains`: a substring check is blind to
 * every mutation that LENGTHENS the line, which is how the first version of another lint in this
 * repo was walked straight past.
 */
class RecentWidgetDeclarationTest {

    /**
     * ⛔ THE MOST IMPORTANT LINE OF THE WHOLE WIDGET.
     *
     * Listing anything in a widget at `minSdk 26` means a `RemoteViewsService` the widget host
     * binds — `RemoteViews.RemoteCollectionItems` is API 31+ — and a service any app may bind is a
     * service that hands out, on demand, the sender and the subject of every message in every
     * inbox. The notification-content setting and the app lock would still govern what the CELL
     * draws and govern nothing at all about what a caller reads, and the reader would have no way
     * of knowing: nothing appears on screen, and the caller declares no permission of its own.
     *
     * `android.permission.BIND_REMOTEVIEWS` is a signature permission held by the system, so only
     * the widget host reaches the factory. The whole element is pinned, not the attribute: an
     * `android:exported="true"` added beside it is the same hole reopened from the other side.
     */
    @Test fun `the row factory can be bound by the widget host and by nobody else`() {
        assertEquals(
            "the RemoteViewsService that serves the widget's rows must carry " +
                "android:permission=\"android.permission.BIND_REMOTEVIEWS\" and must not be " +
                "exported. Without it any installed app binds the factory and reads every " +
                "sender and every subject, whatever the privacy setting says.",
            listOf(
                "<service",
                "android:name=\".widget.RecentMailWidgetService\"",
                "android:exported=\"false\"",
                "android:permission=\"android.permission.BIND_REMOTEVIEWS\" />",
            ),
            element(MANIFEST, "android:name=\".widget.RecentMailWidgetService\""),
        )
    }

    /**
     * The picker's entry name goes through a build placeholder of its OWN, never a literal and
     * never the counter's. The test app installs beside production (`app.sterna.test`); with a
     * literal here, or with one placeholder for two widgets, the picker offers entries a bench pass
     * cannot tell apart — the one failure mode where the maintainer's real mail is a tap away.
     */
    @Test fun `the picker entry is its own placeholder, resolved to a string in production and to a distinct label under -PtestApp`() {
        assertEquals(
            "the receiver's android:label must be the latestWidgetLabel placeholder — not a " +
                "literal, and not the counter's widgetLabel — and the build must fill it with " +
                "@string/widget_latest_title by default and with a recognisable literal under " +
                "-PtestApp.",
            listOf(
                RECEIVER_LABEL,
                "manifestPlaceholders[\"latestWidgetLabel\"] = \"@string/widget_latest_title\"",
                "manifestPlaceholders[\"latestWidgetLabel\"] = \"Latest messages (test)\"",
            ),
            codeLines(MANIFEST).filter { "android:label=" in it && "latestWidgetLabel" in it } +
                codeLines(GRADLE).filter { "latestWidgetLabel" in it },
        )
    }

    /**
     * ⛔ AND THE TEST LABEL IS INSIDE `if (testApp)` — WHICH THE RULE ABOVE CANNOT SEE. Move the
     * line one row down, out of the gate, and the list it collects is byte-for-byte identical. The
     * shipped app would then offer the widget picker an entry called "Latest messages (test)", in
     * English, to every user in all nine languages.
     */
    @Test fun `the test app's label lives INSIDE the testApp gate, not merely somewhere in the build`() {
        assertEquals(
            "the -PtestApp overrides must sit inside `if (testApp) {`. Outside it they apply to " +
                "the production build.",
            listOf(
                "if (testApp) {",
                "applicationIdSuffix = \".test\"",
                "manifestPlaceholders[\"appLabel\"] = \"Sterna (test)\"",
                "manifestPlaceholders[\"widgetLabel\"] = \"Unread count (test)\"",
                "manifestPlaceholders[\"latestWidgetLabel\"] = \"Latest messages (test)\"",
                "versionNameSuffix = \"-test\"",
                "}",
            ),
            blockContaining(GRADLE, "manifestPlaceholders[\"latestWidgetLabel\"] = \"Latest messages (test)\"", "if (testApp) {"),
        )
    }

    /**
     * Both layouts must carry text, and they must carry DIFFERENT text.
     *
     * The preview (`res/xml-v31`'s `previewLayout`, Android 12+) is what the picker draws before
     * anyone places anything: the provider does not run for a preview and the row factory is never
     * bound, so pointing it at the runtime layout would advertise this widget as an empty frame
     * with the app's name in it.
     *
     * ⛔ The runtime layout is the `initialLayout` — what the system inflates the instant a cell is
     * dropped, before any read has answered, and that read may take seconds or time out and never
     * arrive. It must therefore carry text, and that text must claim NOTHING about mail: an
     * "empty inbox" line there is a statement about someone's mailbox made before a single row was
     * read, and it is furthest from true in the very state the widget cannot distinguish yet (an
     * account restored from a backup, an account offline). @string/app_name names the app and
     * stops there.
     */
    @Test fun `the picker preview advertises the widget, and the runtime layout claims nothing about mail`() {
        assertEquals(
            "res/xml-v31 must preview @layout/widget_recent_preview. Previewing the runtime layout " +
                "shows the picker an empty frame: its list is filled by a service the system never " +
                "binds for a preview.",
            listOf(PREVIEW),
            codeLines(INFO_V31).filter { "android:previewLayout=" in it },
        )
        assertEquals(
            "the preview layout must show example rows — senders and clock times, which are the " +
                "same words in every language. A preview without text is the empty rectangle this " +
                "rule exists to stop.",
            listOf(
                "android:text=\"Marie Dupont\"",
                "android:text=\"09:12\"",
                "android:text=\"Tomasz Nowak\"",
                "android:text=\"08:41\"",
                "android:text=\"Ada Lindqvist\"",
                "android:text=\"07:58\"",
            ),
            codeLines(PREVIEW_LAYOUT).filter { it.startsWith("android:text=") },
        )
        assertEquals(
            "the runtime layout must carry exactly one android:text, and it must be " +
                "@string/app_name: it is the initialLayout, so it is what a cell just placed shows " +
                "until a read answers — which may be seconds away, or never.",
            listOf("android:text=\"@string/app_name\""),
            codeLines(RUNTIME_LAYOUT).filter { it.startsWith("android:text=") },
        )
        assertEquals(
            "⛔ and the empty-inbox line must appear NOWHERE in the runtime layout. Which of the " +
                "two silent states a cell is in is RecentMailWidgetContent.notice's decision, taken " +
                "after a read; baked into the layout it is asserted before one.",
            emptyList<String>(),
            codeLines(RUNTIME_LAYOUT).filter { "widget_latest_empty" in it },
        )
    }

    private companion object {
        /** Written with the character rather than an escape, so what is compared stays readable. */
        const val DOLLAR = '$'

        val RECEIVER_LABEL = "android:label=\"$DOLLAR{latestWidgetLabel}\">"

        const val PREVIEW = "android:previewLayout=\"@layout/widget_recent_preview\""

        val MANIFEST = file("app/src/main/AndroidManifest.xml")
        val GRADLE = file("app/build.gradle.kts")
        val INFO_V31 = file("app/src/main/res/xml-v31/appwidget_info_recent.xml")
        val RUNTIME_LAYOUT = file("app/src/main/res/layout/widget_recent.xml")
        val PREVIEW_LAYOUT = file("app/src/main/res/layout/widget_recent_preview.xml")
    }
}
