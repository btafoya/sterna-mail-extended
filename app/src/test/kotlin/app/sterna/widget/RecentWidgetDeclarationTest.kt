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

    /**
     * ⛔ THE ROW HAS ONE UNREAD MARK, AND IT IS THE WEIGHT.
     *
     * The cell shipped an 8 dp dot; the message list inside the app had already settled the same
     * question the other way (`EmailListItem`: "Unread is shown by weight (bold) rather than a
     * status dot"), so a row on the home screen and the row it opens disagreed about the only
     * piece of state either of them carries. The dot is gone — drawable, view and
     * contentDescription string — and this rule is what keeps it gone: re-added beside the weight
     * it would say the same thing twice, on a line whose whole content is a name, a time and
     * whether it has been read.
     *
     * ⛔ AND THE PREVIEW HAS TO SHOW IT. That layout duplicates the row BY HAND, three times, and
     * nothing at runtime ever touches it: it is the picture the widget picker draws, and it is the
     * only thing anyone sees before deciding whether to place the cell at all. Left with three
     * identical rows it advertises a list where nothing is ever unread — which is exactly the state
     * the pane just spent its work making visible. One bold row and two plain ones is what the cell
     * does.
     *
     * ⭐ AND THE HEAD COLUMN STAYS, in all three blocks. The unread dot left the row, but the row
     * did not lose its ImageView: the ACCOUNT dot took the column and holds it always, VISIBLE or
     * INVISIBLE and never GONE, so every served line is indented by 16 dp. A preview whose senders
     * start at the left edge draws a geometry no real row has. ⛔ Invisible there and not coloured:
     * the picker is read by someone who may hold a single account, and a coloured dot would promise
     * a distinction that only exists from two accounts up.
     *
     * ⚠ WHAT THIS CANNOT SEE: whether the runtime row is actually drawn bold. That is a
     * `StyleSpan` handed to `RemoteViews` in another process; there is no Robolectric here and
     * `RecentMailWidgetFactory` cannot be run at all. That the three fields go through the call is
     * pinned in [RecentMailRowTapWiringLintTest]; that it LOOKS right is a bench claim.
     */
    @Test fun `the unread dot is gone from both layouts, and the preview shows the weight instead`() {
        assertEquals(
            "⛔ no trace of the unread dot may remain in either layout. Unread is shown by weight, " +
                "like the message list inside the app; a dot beside the bold is the same state " +
                "said twice on a row that has nothing else to say, and the app contradicting " +
                "itself between the home screen and the list that home screen opens.",
            emptyList<String>(),
            (codeLines(ROW_LAYOUT) + codeLines(PREVIEW_LAYOUT))
                .filter { "widget_recent_unread_dot" in it },
        )
        assertEquals(
            "the picker preview must show ONE unread row and two read ones — bold on the first " +
                "block's sender and time, and on nothing else. The preview is hand-duplicated and " +
                "never drawn by the factory, so it lies on its own the day the row changes: three " +
                "plain rows advertise a widget that never marks anything unread, in the one place " +
                "the widget is seen before it is placed.",
            listOf("android:textStyle=\"bold\"", "android:textStyle=\"bold\""),
            codeLines(PREVIEW_LAYOUT).filter { it.startsWith("android:textStyle=") },
        )
        assertEquals(
            "and all three preview blocks must keep the row's head column — a BADGE then the " +
                "account dot, in that order, in each of the three. The served row carries both " +
                "ImageViews ALWAYS, VISIBLE or INVISIBLE and NEVER GONE, so every real line is " +
                "indented by the same amount; a preview short of either advertises a layout the " +
                "cell does not have, senders flush against the left edge. And this file is a copy " +
                "made BY HAND that nothing at runtime ever redraws, so it lies on its own at the " +
                "first change to the row. ⚠ Each badge line is pinned WITH its closing `/>`, " +
                "which is also what says no attribute follows it — a visibility slipped in there " +
                "would hide the one thing this picture now has to show.",
            listOf(
                MONOGRAM_SRC, ACCOUNT_DOT_SRC,
                MONOGRAM_SRC, ACCOUNT_DOT_SRC,
                MONOGRAM_SRC, ACCOUNT_DOT_SRC,
            ),
            codeLines(PREVIEW_LAYOUT).filter { it.startsWith("android:src=") },
        )
        assertEquals(
            "⛔ and THE THREE INVISIBLE ONES ARE THE DOTS, still three and still only the dots. " +
                "The picker is looked at by someone who may hold one account, where the row draws " +
                "no dot at all; a coloured dot in the picture promises a telling-apart of " +
                "mailboxes that only exists from two accounts up AND with a colour chosen in the " +
                "account editor. ⭐ The badge carries no visibility line for a different reason, " +
                "and NOT because every row wears one — three shipped paths draw none: the NONE " +
                "position of the notification-content setting, the app lock, a colour read that " +
                "did not answer, and the sender-initials switch turned off (#144). What the " +
                "preview shows is the row's GEOMETRY, and a badge is the ORDINARY case of an " +
                "unlocked row, where a coloured dot is the case of a reader who holds two " +
                "accounts and has picked colours for them. A fourth invisible in this list means " +
                "the badge has been hidden and the picture no longer shows the column the served " +
                "row always spends.",
            listOf(DOT_INVISIBLE, DOT_INVISIBLE, DOT_INVISIBLE),
            codeLines(PREVIEW_LAYOUT).filter { it.startsWith("android:visibility=") },
        )
    }

    /**
     * ⛔ THE SERVED ROW'S BADGE, PINNED WHOLE — the element and not one of its attributes.
     *
     * Nothing else in this repository looks at it. [RecentMailRowTapWiringLintTest] reads the row
     * layout's first three lines only (its root and that root's id), the factory's lint pins the
     * `RemoteViews` calls in Kotlin, and no test can inflate a layout: there is no Robolectric
     * here. So `@dimen/widget_recent_monogram` turned into `0dp`, or the `ImageView` deleted
     * outright, leaves every rule in this package green and every home-screen row bare — while the
     * picker's preview, which is a hand-made copy, goes on promising three badges.
     *
     * Three facts, and each is one line away from being lost:
     *
     * - the id, which is what `setImageViewBitmap` and `setViewVisibility` address;
     * - BOTH sides on `@dimen/widget_recent_monogram`. A literal here, or a zero, is a bitmap
     *   scaled to something the disc was never rendered at — the badge is drawn at the dimension's
     *   own pixel size ([WidgetMonogram]) — or no badge at all;
     * - `importantForAccessibility="no"`. Removed, TalkBack reads the initial out loud, which is
     *   the sender's name one letter at a time, on a screen where the pixels are deliberately not
     *   drawn under the lock. The layout's own comment forbids it in writing.
     *
     * ⛔ AND NO `android:src`. The badge is a `Bitmap` posted at runtime, painted in the tones the
     * read answered with; a compile-time drawable here is a colour this layout chose, and it would
     * show on exactly the rows that have no badge — under the lock, at NONE, with the initials
     * switched off, and before any read has answered.
     */
    @Test fun `the served row's badge keeps its own square of the column, and never names itself`() {
        assertEquals(
            "the row layout must declare the badge ImageView whole: the id the factory posts a " +
                "bitmap to, both sides on @dimen/widget_recent_monogram, its margin, and " +
                "importantForAccessibility=\"no\". A 0dp side or a deleted view leaves every " +
                "served row bare while the picker's preview still promises three badges; a " +
                "missing importantForAccessibility has TalkBack read the sender's initial aloud, " +
                "which is the naming the app lock exists to prevent, one letter at a time.",
            listOf(
                "<ImageView",
                MONOGRAM_ID,
                "android:layout_width=\"@dimen/widget_recent_monogram\"",
                "android:layout_height=\"@dimen/widget_recent_monogram\"",
                "android:layout_marginEnd=\"8dp\"",
                "android:importantForAccessibility=\"no\" />",
            ),
            element(ROW_LAYOUT, MONOGRAM_ID),
        )
        assertEquals(
            "⛔ and it must carry NO android:src. The bitmap is posted at runtime in the tones " +
                "the colour read answered with; a drawable compiled in here is a colour the " +
                "layout chose, and it would sit on precisely the rows that must wear no badge — " +
                "locked, at NONE, with the initials switched off, and before any read answered.",
            emptyList<String>(),
            element(ROW_LAYOUT, MONOGRAM_ID).filter { it.startsWith("android:src=") },
        )
    }

    private companion object {
        /** Written with the character rather than an escape, so what is compared stays readable. */
        const val DOLLAR = '$'

        val RECEIVER_LABEL = "android:label=\"$DOLLAR{latestWidgetLabel}\">"

        const val PREVIEW = "android:previewLayout=\"@layout/widget_recent_preview\""

        const val ACCOUNT_DOT_SRC = "android:src=\"@drawable/widget_recent_account_dot\""

        /** The served row's badge, addressed by the id the factory posts its bitmap to. */
        const val MONOGRAM_ID = "android:id=\"@+id/widget_recent_row_monogram\""

        /** Last attribute of its element, hence the `/>` — see the rule that compares it. */
        const val MONOGRAM_SRC = "android:src=\"@drawable/widget_recent_monogram_badge\" />"

        const val DOT_INVISIBLE = "android:visibility=\"invisible\" />"

        val MANIFEST = file("app/src/main/AndroidManifest.xml")
        val GRADLE = file("app/build.gradle.kts")
        val INFO_V31 = file("app/src/main/res/xml-v31/appwidget_info_recent.xml")
        val RUNTIME_LAYOUT = file("app/src/main/res/layout/widget_recent.xml")
        val PREVIEW_LAYOUT = file("app/src/main/res/layout/widget_recent_preview.xml")
        val ROW_LAYOUT = file("app/src/main/res/layout/widget_recent_row.xml")
    }
}
