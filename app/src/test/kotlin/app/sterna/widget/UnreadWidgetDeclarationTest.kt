package app.sterna.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and disclaimer as
 * [app.sterna.ui.connect.OAuthRedirectManifestTest]. A widget declaration is not code this module
 * can run: `res/xml`, the merged manifest and the build script only mean anything inside a build,
 * and there is no Robolectric here. What is held below is what a build would not complain about
 * and what nobody would notice on screen.
 *
 * Two rules, both of them whole-line comparisons. Never a `contains`: a substring check is blind
 * to every mutation that LENGTHENS the line, which is how the first version of another lint in this
 * repo was walked straight past.
 */
class UnreadWidgetDeclarationTest {

    /**
     * The widget polls nothing. Any `updatePeriodMillis` other than 0 asks the system to wake
     * this app on a timer — 30 minutes at the very least, for a number that usually has not moved —
     * and nothing on screen would say so: the widget looks identical either way, and the only
     * symptom is battery. Both variants of the declaration are checked; the v31 one is a copy, and
     * a copy is exactly where a value drifts unnoticed.
     */
    @Test fun `every widget asks the system for no periodic update`() {
        val found = INFO_FILES.associate {
            "${it.parentFile.name}/${it.name}" to codeLines(it).filter { l -> "updatePeriodMillis" in l }
        }
        assertEquals(
            "every appwidget-provider must carry exactly the line android:updatePeriodMillis=\"0\" " +
                "— a widget is redrawn when the system asks (placement, boot, app update) and " +
                "must never ask to be woken on a timer. ⭐ This sweeps EVERY appwidget_info_*.xml " +
                "of both resource directories, so a widget added later is covered the day it is " +
                "declared and not the day someone remembers to copy a test — and a declaration " +
                "that loses its res/xml-v31 twin (or gains one nobody wrote) is a missing key here.",
            mapOf(
                "xml/appwidget_info_recent.xml" to listOf(PERIOD),
                "xml/appwidget_info_unread.xml" to listOf(PERIOD),
                "xml-v31/appwidget_info_recent.xml" to listOf(PERIOD),
                "xml-v31/appwidget_info_unread.xml" to listOf(PERIOD),
            ),
            found,
        )
    }

    /**
     * The picker's entry name goes through the build placeholder, never a literal. The test app
     * installs BESIDE production (`app.sterna.test`); with a literal here both offer the same
     * entry in the same list, and a bench pass cannot tell which install it just put on the home
     * screen — the one failure mode where the maintainer's real mail is a tap away.
     *
     * The three lines are pinned together because they are one mechanism split across two files:
     * the manifest that spends the placeholder, and the two branches of the build that fill it.
     */
    @Test fun `the picker entry is a placeholder, resolved to the string in production and to a distinct label under -PtestApp`() {
        assertEquals(
            "the receiver's android:label must be the widgetLabel placeholder, and the build must " +
                "fill it with @string/widget_unread_title by default and with a recognisable " +
                "literal under -PtestApp. A literal in the manifest, or the same value on both " +
                "branches, gives the widget picker two indistinguishable entries. Expected:\n  " +
                RECEIVER_LABEL,
            listOf(
                RECEIVER_LABEL,
                "manifestPlaceholders[\"widgetLabel\"] = \"@string/widget_unread_title\"",
                "manifestPlaceholders[\"widgetLabel\"] = \"Unread count (test)\"",
            ),
            codeLines(MANIFEST).filter { "android:label=" in it && "widgetLabel" in it } +
                codeLines(GRADLE).filter { "widgetLabel" in it },
        )
    }

    /**
     * AND THE TEST LABEL IS INSIDE `if (testApp)` — WHICH THE RULE ABOVE CANNOT SEE.
     *
     * That rule collects the `widgetLabel` lines and compares the list. Move
     * `manifestPlaceholders["widgetLabel"] = "Unread count (test)"` one line DOWN, out of the
     * `if (testApp)` block, and the list is byte-for-byte identical: same lines, same order, green.
     * The shipped app would then offer the widget picker an entry called "Unread count (test)", in
     * English, to every user in all nine languages — the production build's own label, gone.
     *
     * So the block itself is pinned, brace to brace. It is the same class of mistake as the source
     * lints in the widget package: the line existing says nothing about where it lives.
     */
    @Test fun `the test app's label lives INSIDE the testApp gate, not merely somewhere in the build`() {
        assertEquals(
            "the -PtestApp overrides must sit inside `if (testApp) {`. Outside it they apply to " +
                "the production build: the widget picker would list \"Unread count (test)\" in " +
                "English for everyone, and the applicationId suffix and versionName suffix would " +
                "ride along with it.",
            listOf(
                "if (testApp) {",
                "applicationIdSuffix = \".test\"",
                "manifestPlaceholders[\"appLabel\"] = \"Sterna (test)\"",
                "manifestPlaceholders[\"widgetLabel\"] = \"Unread count (test)\"",
                "manifestPlaceholders[\"latestWidgetLabel\"] = \"Latest messages (test)\"",
                "versionNameSuffix = \"-test\"",
                "}",
            ),
            blockContaining(GRADLE, "manifestPlaceholders[\"widgetLabel\"] = \"Unread count (test)\"", "if (testApp) {"),
        )
    }

    /**
     * Both layouts must have text in them, and NOT the same text.
     *
     * The preview (`res/xml-v31`'s `previewLayout`, Android 12+) is what the widget picker draws
     * before anyone places anything, so it carries an example figure and the label the widget
     * usually wears. Pointed at the runtime layout instead, the picker would show "… / Sterna Mail"
     * — true, and useless as an advertisement.
     *
     * THE RUNTIME LAYOUT'S TEXT IS THE OTHER HALF, and this rule used to say the opposite: it
     * required `@layout/widget_unread` to carry no `android:text` at all. That was right about the
     * danger and wrong about the moment. The layout is the `initialLayout`: it is what the system
     * inflates the instant a cell is dropped on the home screen, before the provider has drawn
     * anything, and on a cold start that draw opens Room and can take seconds — or time out at 5 s
     * and never arrive, since nothing here draws on failure. Textless, the user's first sight of
     * this widget was an EMPTY rectangle. "The cell keeps its old number" was the argument, and at
     * the first placement there is no old number.
     *
     * What is still forbidden is what the old rule was protecting: a plausible, wrong COUNT sitting
     * there as if it were true. So the figure must not be a number — the ellipsis is not one, and
     * cannot be misread as "no unread mail" the way a "0" would be. Pinned twice below: once as the
     * exact lines, once as "no digit anywhere in it", which survives a change of character.
     */
    @Test fun `the picker preview advertises the widget, and the runtime layout says the app is waking up`() {
        assertEquals(
            "res/xml-v31 must preview @layout/widget_unread_preview. Previewing the runtime " +
                "layout puts an ellipsis and the app's name in the widget picker: that is what the " +
                "cell shows before its first draw, and it says nothing about what the widget does.",
            listOf(PREVIEW),
            codeLines(INFO_V31).filter { "android:previewLayout=" in it },
        )
        assertEquals(
            "the preview layout must give both TextViews an android:text — an example figure, and " +
                "the label from a string the nine languages already carry. A preview without text " +
                "is the empty rectangle this rule exists to stop.",
            listOf("android:text=\"12\"", "android:text=\"@string/inbox_all_inboxes\""),
            codeLines(PREVIEW_LAYOUT).filter { it.startsWith("android:text=") },
        )
        assertEquals(
            "the production layout must give both TextViews an android:text too — it is the " +
                "initialLayout, and without it a cell just placed is a blank rectangle until the " +
                "first draw comes back, which on a cold start may be seconds away or never. The " +
                "figure is an ellipsis and the label is the app's own name, translated already.",
            listOf("android:text=\"…\"", "android:text=\"@string/app_name\""),
            codeLines(RUNTIME_LAYOUT).filter { it.startsWith("android:text=") },
        )
        assertEquals(
            "⛔ and the production figure must contain no digit. Whatever is written there is what " +
                "the cell shows before the first draw and after a draw that failed: a number would " +
                "sit there as a plausible, wrong count, and a \"0\" would say the mailbox is empty.",
            emptyList<String>(),
            codeLines(RUNTIME_LAYOUT)
                .filter { it.startsWith("android:text=") }
                .filter { line -> line.any { it.isDigit() } },
        )
    }

    /**
     * THE OTHER HALF OF THE RULE ABOVE, and on its own it looks like its opposite.
     *
     * `@layout/widget_unread` MUST carry text because it is the `initialLayout`: the system
     * inflates it the instant a cell is dropped, before any draw comes back. The enlarged layout
     * and its row are the `initialLayout` of NOBODY — they are only ever handed to the launcher
     * already filled in, inside the size map `UnreadWidgetDraw.draw` builds. A literal left in
     * either one would therefore be drawn before `UnreadWidgetContent.byAccount` had decided
     * anything: a name, or a figure, for an account the app lock says must not be named at all, and
     * a row that stays on the cell even when the rule returned no lines.
     *
     * Pinned as "no `android:text` line anywhere in the file", not as a list of expected lines: a
     * text added to a THIRD view in either layout has to redden this too.
     */
    @Test fun `neither layout of the enlarged cell carries any text of its own`() {
        assertEquals(
            "the enlarged counter's layouts must declare no android:text at all. Neither is an " +
                "initialLayout: both are only ever handed over already filled in, so anything " +
                "written in them is drawn by the launcher BEFORE the rule that decides whether an " +
                "account may be named has run — the app-lock fallback included.",
            mapOf(
                "widget_unread_accounts.xml" to emptyList<String>(),
                "widget_unread_account_row.xml" to emptyList<String>(),
            ),
            ENLARGED_LAYOUTS.associate { file ->
                file.name to codeLines(file).filter { it.startsWith("android:text=") }
            },
        )
    }

    /**
     * THE WHOLE BODY OF `draw`, LINE BY LINE — and it is the only thing standing between this
     * widget and any of the mutations below. Nothing in this module renders: there is no
     * Robolectric, `android.util.SizeF` is a stub that throws, and `draw` wants an `Application`,
     * its container and the widget manager. Every decision it takes is therefore reachable only as
     * text, and each of these lines was silently mutable before this rule existed:
     *
     *  - `store.appLockEnabled()` → `false`: every account's name, which falls back to its owner's
     *    mail address, drawn permanently on a LOCKED home screen. The twin widget's own read is
     *    pinned for exactly this ([app.sterna.widget.RecentMailWidgetWiringLintTest]); this one was
     *    not, and it is the guard the branch was written around;
     *  - `unreadByAccount.values.sum()` → `.maxOrNull() ?: 0`: the small cell shows the busiest
     *    account's figure while the drawer beside it shows the true total. The arithmetic is
     *    repeated here because `UnifiedInbox.total` is internal to `core:data`, so nothing in that
     *    module's tests covers this copy of it;
     *  - dropping `rows.isNotEmpty() &&`: under an app lock, or on a single account, an enlarged
     *    cell draws an empty account list — a blank rounded rectangle, no total, no lines;
     *  - swapping the two entries of the map: the branch runs backwards. Account lines crammed
     *    into a 110x40dp cell, one figure in the large one;
     *  - `ventilatedHeightDp(rows.size, …)` → any constant: the enlarged cell goes back to
     *    promising the launcher a fixed height, and the reader with one account more than that
     *    height holds loses that account off the bottom edge, in silence;
     *  - dropping `fontScale`: the same erasure, kept for the readers who enlarge their text;
     *  - a SECOND `store.accounts()`: the label, the tap target and the lines would come from
     *    three different moments — a cell reading "All inboxes" that opens a single folder;
     *  - dropping `palette` from either render call: the map hands the launcher TWO layouts for
     *    one cell, so painting one of them alone is a cell that CHANGES THEME as it is dragged
     *    larger. `UnreadWidgetPaletteWiringLintTest` holds what each render then does with it;
     *    this line is where it would stop arriving at all.
     *
     * Whole lines, never a `contains`: every mutation above is the same length or longer than
     * the line it replaces. The price is that reformatting this function reddens the test — loud,
     * and one line to fix.
     */
    @Test fun `the draw decides the total, the lock and the size map exactly once each`() {
        assertEquals(
            "UnreadWidgetDraw.draw must read the account list ONCE, total the breakdown for the " +
                "small layout, read the app lock from the store, and offer the ventilated layout " +
                "at the height its lines need and the plain one at SMALL — the whole of it " +
                "holdable only as text, " +
                "since nothing in this suite can render a RemoteViews (#112).",
            listOf(
                DRAW_SIGNATURE,
                "if (ids.isEmpty()) return",
                "val palette = WidgetThemeReader.palette(app)",
                "val store = app.container.accountStore",
                "val accounts = store.accounts()",
                "val target = UnreadWidgetTap.of(accounts)",
                "val small = tapped(app, renderTotal(app, UnreadWidgetContent.of(accounts, " +
                    "unreadByAccount.values.sum()), palette), target)",
                "val rows = UnreadWidgetContent.byAccount(accounts, unreadByAccount, store.appLockEnabled())",
                "val views =",
                "if (rows.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {",
                "val fontScale = app.resources.configuration.fontScale",
                "val ventilated = SizeF(VENTILATED_WIDTH_DP, ventilatedHeightDp(rows.size, fontScale))",
                "RemoteViews(mapOf(SMALL to small, ventilated to tapped(app, renderAccounts(app, rows, palette), target)))",
                "} else {",
                "small",
                "}",
                "val manager = AppWidgetManager.getInstance(app)",
                "ids.forEach { manager.updateAppWidget(it, views) }",
                "}",
            ),
            blockContaining(
                WIDGET_DRAW,
                "val rows = UnreadWidgetContent.byAccount(",
                DRAW_SIGNATURE,
            ),
        )
        assertEquals(
            "and the two entries themselves. SMALL must stay the widget's DECLARED minimum, or a " +
                "cell at minimum size matches no entry at all. ⛔ The other one is no longer a " +
                "constant beside it: its height is what THIS MANY lines need at THIS font scale, " +
                "read at draw time. A constant there is a promise about how many accounts the " +
                "reader has — at 110dp it held three, and the fourth was cut off by the edge with " +
                "nothing on screen to say so.",
            listOf(
                "val fontScale = app.resources.configuration.fontScale",
                "val ventilated = SizeF(VENTILATED_WIDTH_DP, ventilatedHeightDp(rows.size, fontScale))",
                "private val SMALL = SizeF(110f, 40f)",
            ),
            codeLines(WIDGET_DRAW)
                .filterNot { it.startsWith("*") || it.startsWith("/") }
                .filter {
                    it.startsWith("private val SMALL") || it.startsWith("val fontScale") ||
                        it.startsWith("val ventilated")
                },
        )
        assertEquals(
            "the numbers that height is built from, and their names. ⛔ Both dimensions of the " +
                "ventilated entry must stay STRICTLY above SMALL's 110x40 — 180 > 110, and a " +
                "floor of 41 > 40 — or the launcher's closest-fit may hand three account lines to " +
                "a cell dragged to the widget's own minimum. What each number MEANS is executed " +
                "in UnreadVentilatedHeightTest; this rule only holds them from drifting apart.",
            listOf(
                "internal const val VENTILATED_WIDTH_DP = 180f",
                "private const val CONTAINER_PADDING_DP = 16f",
                "private const val ROW_PADDING_DP = 8f",
                "private const val ROW_TEXT_DP = 20f",
                "private const val ROW_SLACK_DP = 2f",
                "private const val SMALLEST_VENTILATED_HEIGHT_DP = 41f",
            ),
            codeLines(WIDGET_CONTENT).filter {
                it.startsWith("internal const val VENTILATED") || it.startsWith("private const val")
            },
        )
        assertEquals(
            "⛔ AND THE TWO LAYOUTS THOSE NUMBERS WERE MEASURED OFF. A row is 4dp + 14sp + 4dp and " +
                "the container keeps 8dp all round: move any of them and the height asked for is " +
                "wrong by a few dp per line, which is the sliced last line coming back — with " +
                "every rule above still green, since nothing here renders anything. (The lines " +
                "carry their closing bracket because they are pinned WHOLE — a substring rule " +
                "here would be satisfied by a padding of 40dp.) " +
                "⚠ WHAT THIS DOES NOT CATCH, and it is why ROW_SLACK_DP exists: this reads the " +
                "attributes that are WRITTEN in these two files, so it holds them still — it can " +
                "say nothing about what a TextView actually measures, which is the font's own " +
                "line box and moves with the device. A view ADDED to either layout is caught " +
                "here only if it carries one of these attributes.",
            mapOf(
                // The two `wrap_content` heights are as load-bearing as the paddings: they are
                // what makes a row exactly its text's line box plus its own padding, which is the
                // whole of ROW_TEXT_DP + ROW_PADDING_DP. A fixed `48dp` here, or a `match_parent`,
                // and the model is a fiction while every rule above stays green.
                "widget_unread_accounts.xml" to listOf(
                    "android:layout_height=\"match_parent\"",
                    "android:padding=\"8dp\">",
                    "android:layout_height=\"wrap_content\"",
                ),
                "widget_unread_account_row.xml" to listOf(
                    "android:layout_height=\"wrap_content\"",
                    "android:paddingTop=\"4dp\"",
                    "android:paddingBottom=\"4dp\">",
                    "android:layout_height=\"wrap_content\"",
                    "android:textSize=\"14sp\" />",
                    "android:layout_height=\"wrap_content\"",
                    "android:layout_marginStart=\"8dp\"",
                    "android:textSize=\"14sp\"",
                ),
            ),
            ENLARGED_LAYOUTS.associate { file ->
                file.name to codeLines(file).filter {
                    it.startsWith("android:padding") || it.startsWith("android:textSize=") ||
                        it.startsWith("android:layout_margin") || it.startsWith("android:lineSpacing") ||
                        it.startsWith("android:minHeight") || it.startsWith("android:layout_height")
                }
            },
        )
        assertEquals(
            "SMALL must be exactly the minWidth/minHeight res/xml declares, in both variants.",
            mapOf(
                "xml/appwidget_info_unread.xml" to listOf(MIN_HEIGHT, MIN_WIDTH),
                "xml-v31/appwidget_info_unread.xml" to listOf(MIN_HEIGHT, MIN_WIDTH),
            ),
            INFO_FILES.filter { it.name == "appwidget_info_unread.xml" }.associate { file ->
                "${file.parentFile.name}/${file.name}" to
                    codeLines(file).filter { it.startsWith("android:minWidth=") || it.startsWith("android:minHeight=") }
            },
        )
    }

    /**
     * The weight goes to the NAME, and only to the name — the one line of the row layout that
     * decides what survives a narrow screen.
     *
     * On a 360dp phone the cell is narrow enough that a translated label already overflows (the
     * note at the top of `@layout/widget_unread`), and an account's name can be a full mail
     * address. With the weight on the name, the NAME ellipsises and the digits stay at the end of
     * the line. Put a weight on the figure as well, or take it off entirely, and the number — the
     * only thing this widget exists to show — is what gets cut off instead. Nothing renders in
     * this suite, so this is the only thing standing between that and a bench pass.
     */
    @Test fun `the enlarged row gives its weight to the name, so the figure is never what is cut`() {
        assertEquals(
            "in widget_unread_account_row.xml the NAME must be the weighted, zero-width column " +
                "and the figure must be wrap_content with no weight. Reversed or removed, a long " +
                "account name pushes the unread figure off the end of the line on a narrow screen.",
            listOf(
                "android:layout_width=\"match_parent\"",
                "android:id=\"@+id/widget_unread_account_name\"",
                "android:layout_width=\"0dp\"",
                "android:layout_weight=\"1\"",
                "android:id=\"@+id/widget_unread_account_count\"",
                "android:layout_width=\"wrap_content\"",
            ),
            codeLines(ROW_LAYOUT).filter {
                it.startsWith("android:layout_width=") || it.startsWith("android:layout_weight=") ||
                    it.startsWith("android:id=")
            },
        )
    }

    /**
     * The file's lines, comments removed WHOLE — an `<!-- … -->` block is cut out before the split,
     * and a `//` line is dropped. Without that, every rule below also matches the prose written to
     * explain it: the first run of this file failed on its own comment quoting the attribute it
     * pins.
     */
    private fun codeLines(file: File): List<String> = file.readText()
        .replace(XML_COMMENT, "")
        .lines()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("//") }

    /**
     * The whole brace-balanced block that opens with [opener] and contains [marker] — so a rule can
     * pin WHERE a line lives and not merely that it exists. Same helper as the two source lints in
     * this package.
     *
     * Deliberately returns an empty list when [marker] is nowhere in the file: moving the line out
     * of the block is the mutation the caller exists to catch, and an assertion showing the
     * expected lines against `[]` says more than an exception would.
     */
    private fun blockContaining(file: File, marker: String, opener: String): List<String> {
        val lines = codeLines(file)
        val at = lines.indexOfFirst { marker in it }
        if (at < 0) return emptyList()
        val start = (at downTo 0).firstOrNull { lines[it] == opener }
            ?: error("no `$opener` above `$marker` in ${file.name}")
        val block = mutableListOf<String>()
        var depth = 0
        for (i in start until lines.size) {
            val line = lines[i]
            block += line
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth == 0) return block
        }
        error("`$opener` in ${file.name} never closes")
    }

    private companion object {
        /** Written with the character rather than an escape, so what is compared stays readable. */
        const val DOLLAR = '$'

        val XML_COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

        const val PERIOD = "android:updatePeriodMillis=\"0\""

        const val PREVIEW = "android:previewLayout=\"@layout/widget_unread_preview\""

        val RECEIVER_LABEL = "android:label=\"$DOLLAR{widgetLabel}\">"

        /** Repo root, found by walking up from the module's working directory. */
        val ROOT: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/AndroidManifest.xml").isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the declaration as text and needs a working directory inside the checkout",
                )
        }

        val MANIFEST: File by lazy { File(ROOT, "app/src/main/AndroidManifest.xml") }
        val GRADLE: File by lazy { File(ROOT, "app/build.gradle.kts") }

        /**
         * EVERY widget declaration the app ships, both resource directories, found rather than
         * listed: the rule above must not be a rule about the counter that a second widget walks
         * straight past.
         */
        val INFO_FILES: List<File> by lazy {
            listOf("xml", "xml-v31")
                .flatMap { dir ->
                    (File(ROOT, "app/src/main/res/$dir").listFiles() ?: emptyArray<File>()).toList()
                        .filter { it.name.startsWith("appwidget_info_") && it.name.endsWith(".xml") }
                }
                .also { if (it.isEmpty()) error("no appwidget_info_*.xml under app/src/main/res — were they moved?") }
                .sortedBy { "${it.parentFile.name}/${it.name}" }
        }

        /** The COUNTER's Android 12+ declaration — the preview rule below is about that widget. */
        val INFO_V31: File by lazy {
            INFO_FILES.first { it.parentFile.name == "xml-v31" && it.name == "appwidget_info_unread.xml" }
        }

        /**
         * Pinned whole, `suspend` INCLUDED. The keyword is what buys the colour read: dropped, the
         * three DataStore `first()` calls have nowhere to happen and the cell goes back to the
         * colours `res/values-night` froze into it (#117).
         */
        const val DRAW_SIGNATURE =
            "suspend fun draw(app: Application, ids: IntArray, unreadByAccount: Map<String, Int>) {"

        val RUNTIME_LAYOUT: File by lazy { layout("widget_unread") }
        val PREVIEW_LAYOUT: File by lazy { layout("widget_unread_preview") }

        const val MIN_WIDTH = "android:minWidth=\"110dp\""

        const val MIN_HEIGHT = "android:minHeight=\"40dp\""

        val WIDGET_DRAW: File by lazy {
            File(ROOT, "app/src/main/kotlin/app/sterna/widget/UnreadWidgetDraw.kt")
        }

        /** Where the size rule that can be RUN lives — the constants behind the ventilated entry. */
        val WIDGET_CONTENT: File by lazy {
            File(ROOT, "app/src/main/kotlin/app/sterna/widget/UnreadWidgetContent.kt")
        }

        val ROW_LAYOUT: File by lazy { layout("widget_unread_account_row") }

        /** The counter's ENLARGED layout and its row — the two the launcher only ever gets filled in. */
        val ENLARGED_LAYOUTS: List<File> by lazy {
            listOf(layout("widget_unread_accounts"), layout("widget_unread_account_row"))
        }

        private fun layout(name: String): File =
            File(ROOT, "app/src/main/res/layout/$name.xml").also {
                if (!it.isFile) error("the widget layout is not at ${it.path} — was it moved?")
            }
    }
}
