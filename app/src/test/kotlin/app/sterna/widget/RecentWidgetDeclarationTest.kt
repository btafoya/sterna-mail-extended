package app.sterna.widget

import app.sterna.widget.DeclarationSource.blockContaining
import app.sterna.widget.DeclarationSource.codeLines
import app.sterna.widget.DeclarationSource.element
import app.sterna.widget.DeclarationSource.file
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, not a behaviour test, the latest-messages widget's half of what
 */
class RecentWidgetDeclarationTest {

    /**
     * The most important line of the whole widget.
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
     * The picker's entry name goes through a build placeholder of its own, never a literal and
     * never the counter's. The test app installs beside production (`app.sterna.test`); with a
     * literal here, or with one placeholder for two widgets, the picker offers entries a bench pass
     * cannot tell apart, the one failure mode where the maintainer's real mail is a tap away.
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

    @Test fun `the unread list is its own receiver, with a fourth placeholder and its own test label`() {
        assertEquals(
            "the unread-list receiver's android:label must be the unreadListWidgetLabel " +
                "placeholder, filled with @string/widget_unread_list_title by default and with its " +
                "own literal under -PtestApp: one placeholder for two widgets gives the picker two " +
                "entries called the same thing.",
            listOf(
                UNREAD_LIST_RECEIVER_LABEL,
                "manifestPlaceholders[\"unreadListWidgetLabel\"] = \"@string/widget_unread_list_title\"",
                "manifestPlaceholders[\"unreadListWidgetLabel\"] = \"Unread messages (test)\"",
            ),
            codeLines(MANIFEST).filter { "android:label=" in it && "unreadListWidgetLabel" in it } +
                codeLines(GRADLE).filter { "unreadListWidgetLabel" in it },
        )
        assertEquals(
            "it must be an exported APPWIDGET_UPDATE receiver and nothing more, pointing at its own " +
                "declaration: pointed at appwidget_info_recent it previews and describes the " +
                "inboxes' list.",
            listOf(
                "<receiver",
                "android:name=\".widget.UnreadListWidgetProvider\"",
                "android:exported=\"true\"",
                UNREAD_LIST_RECEIVER_LABEL,
                "<intent-filter>",
                "<action android:name=\"android.appwidget.action.APPWIDGET_UPDATE\" />",
                "</intent-filter>",
                "<meta-data",
                "android:name=\"android.appwidget.provider\"",
                "android:resource=\"@xml/appwidget_info_unread_list\" />",
                "</receiver>",
            ),
            codeLines(MANIFEST).let { lines ->
                val at = lines.indexOf("android:name=\".widget.UnreadListWidgetProvider\"")
                if (at < 1) emptyList() else lines.subList(at - 1, (at until lines.size).first { lines[it] == "</receiver>" } + 1)
            },
        )
        assertEquals(
            "and the latest-messages list keeps ITS component, declaration and row service: Android " +
                "knows a placed cell by its ComponentName, and both lists are served by the one " +
                "service.",
            listOf(
                "android:name=\".widget.RecentMailWidgetProvider\"",
                "android:resource=\"@xml/appwidget_info_recent\" />",
                "android:name=\".widget.RecentMailWidgetService\"",
            ),
            codeLines(MANIFEST).filter {
                it == "android:name=\".widget.RecentMailWidgetProvider\"" ||
                    it == "android:resource=\"@xml/appwidget_info_recent\" />" ||
                    (it.startsWith("android:name=\".widget.") && "Service" in it)
            },
        )
    }

    @Test fun `the unread list's declarations are the latest-messages list's but for their description and preview`() {
        val differs = listOf("android:description=", "android:previewLayout=")
        assertEquals(
            "res/xml and res/xml-v31 appwidget_info_unread_list.xml must carry the very attributes " +
                "of appwidget_info_recent.xml — initialLayout, sizes, resizeMode, " +
                "updatePeriodMillis=\"0\", widgetCategory — and differ only by description and preview.",
            listOf("xml", "xml-v31").associateWith { dir ->
                codeLines(file("app/src/main/res/$dir/appwidget_info_recent.xml"))
                    .filterNot { l -> differs.any { l.startsWith(it) } }
            },
            listOf("xml", "xml-v31").associateWith { dir ->
                codeLines(file("app/src/main/res/$dir/appwidget_info_unread_list.xml"))
                    .filterNot { l -> differs.any { l.startsWith(it) } }
            },
        )
        assertEquals(
            "its Android 12+ picker entry describes and previews ITSELF, never the runtime layout, " +
                "whose list a preview never fills.",
            listOf(
                "android:description=\"@string/widget_unread_list_description\"",
                "android:previewLayout=\"@layout/widget_unread_list_preview\"",
            ),
            codeLines(UNREAD_LIST_INFO_V31).filter { l -> differs.any { l.startsWith(it) } },
        )
        assertEquals(
            "the preview shows the same three example rows as the latest-messages list's, and " +
                "EVERY one of them bold, sender and time: the list holds nothing but unread mail, " +
                "and bold is a row's only unread mark. One plain row advertises a read message " +
                "in a list that never shows one.",
            listOf(
                codeLines(PREVIEW_LAYOUT).filter { it.startsWith("android:text=") },
                List(6) { "android:textStyle=\"bold\"" },
                listOf(MONOGRAM_SRC, ACCOUNT_DOT_SRC, MONOGRAM_SRC, ACCOUNT_DOT_SRC, MONOGRAM_SRC, ACCOUNT_DOT_SRC),
                listOf(DOT_INVISIBLE, DOT_INVISIBLE, DOT_INVISIBLE),
            ),
            listOf(
                codeLines(UNREAD_LIST_PREVIEW).filter { it.startsWith("android:text=") },
                codeLines(UNREAD_LIST_PREVIEW).filter { it.startsWith("android:textStyle=") },
                codeLines(UNREAD_LIST_PREVIEW).filter { it.startsWith("android:src=") },
                codeLines(UNREAD_LIST_PREVIEW).filter { it.startsWith("android:visibility=") },
            ),
        )
    }

    /**
     * And the test label is inside `if (testApp)`, which the rule above cannot see.
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
                "manifestPlaceholders[\"everyFolderWidgetLabel\"] = \"Unread, every folder (test)\"",
                "manifestPlaceholders[\"unreadListWidgetLabel\"] = \"Unread messages (test)\"",
                "versionNameSuffix = \"-test\"",
                "}",
            ),
            blockContaining(GRADLE, "manifestPlaceholders[\"latestWidgetLabel\"] = \"Latest messages (test)\"", "if (testApp) {"),
        )
    }

    /**
     * Both layouts must carry text, and they must carry different text.
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
     * The row has one unread mark, and it is the weight.
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
     * The served row's badge, pinned whole, the element and not one of its attributes.
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

        val UNREAD_LIST_RECEIVER_LABEL = "android:label=\"$DOLLAR{unreadListWidgetLabel}\">"

        const val PREVIEW = "android:previewLayout=\"@layout/widget_recent_preview\""

        const val ACCOUNT_DOT_SRC = "android:src=\"@drawable/widget_recent_account_dot\""

        /** The served row's badge, addressed by the id the factory posts its bitmap to. */
        const val MONOGRAM_ID = "android:id=\"@+id/widget_recent_row_monogram\""

        /** Last attribute of its element, hence the `/>`, see the rule that compares it. */
        const val MONOGRAM_SRC = "android:src=\"@drawable/widget_recent_monogram_badge\" />"

        const val DOT_INVISIBLE = "android:visibility=\"invisible\" />"

        val MANIFEST = file("app/src/main/AndroidManifest.xml")
        val GRADLE = file("app/build.gradle.kts")
        val INFO_V31 = file("app/src/main/res/xml-v31/appwidget_info_recent.xml")
        val RUNTIME_LAYOUT = file("app/src/main/res/layout/widget_recent.xml")
        val PREVIEW_LAYOUT = file("app/src/main/res/layout/widget_recent_preview.xml")
        val UNREAD_LIST_INFO_V31 = file("app/src/main/res/xml-v31/appwidget_info_unread_list.xml")
        val UNREAD_LIST_PREVIEW = file("app/src/main/res/layout/widget_unread_list_preview.xml")
        val ROW_LAYOUT = file("app/src/main/res/layout/widget_recent_row.xml")
    }
}
