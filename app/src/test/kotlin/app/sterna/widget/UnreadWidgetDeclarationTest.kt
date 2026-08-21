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
 * Two rules, both of them whole-line comparisons. ⛔ Never a `contains`: a substring check is blind
 * to every mutation that LENGTHENS the line, which is how the first version of another lint in this
 * repo was walked straight past.
 */
class UnreadWidgetDeclarationTest {

    /**
     * ⛔ The widget polls nothing. Any `updatePeriodMillis` other than 0 asks the system to wake
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
     * ⛔ AND THE TEST LABEL IS INSIDE `if (testApp)` — WHICH THE RULE ABOVE CANNOT SEE.
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
     * ⛔ THE RUNTIME LAYOUT'S TEXT IS THE OTHER HALF, and this rule used to say the opposite: it
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

        val RUNTIME_LAYOUT: File by lazy { layout("widget_unread") }
        val PREVIEW_LAYOUT: File by lazy { layout("widget_unread_preview") }

        private fun layout(name: String): File =
            File(ROOT, "app/src/main/res/layout/$name.xml").also {
                if (!it.isFile) error("the widget layout is not at ${it.path} — was it moved?")
            }
    }
}
