package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads source files as text and proves nothing about what
 * happens on screen. Same instrument and disclaimer as `NavHostSourceRulesTest`.
 *
 * [ReplyBarTest] runs both decisions. Neither of them is plugged in by anything a JVM test here can
 * execute: `MessageScreen` is a composable and `SettingsRepository` needs a DataStore on a device.
 * The three edits it guards are all silent — the suite stays green through every one of them:
 *
 *  1. `barVisible` back on `bodyReady && showBar`: the switch does nothing at all;
 *  2. the bottom inset computed inline again: the bar goes, and every message keeps ~80 dp of
 *     white under nothing — the trap this setting's whole implementation turns on;
 *  3. the setting missing from the backup snapshot or the restore: it exports as if it did not
 *     exist, and an import silently puts the bar back.
 */
class ReplyBarWiringTest {

    @Test fun `the reader asks the shared decision whether to show the bar`() {
        val screen = code(MESSAGE_SCREEN)
        assertTrue(
            "MessageScreen must compute barVisible through replyBarVisible(...) — the decision " +
                "ReplyBarTest runs — and be handed the setting. Back on 'bodyReady && showBar' the " +
                "switch is inert, with every test green.",
            Regex(
                """val\s+barVisible\s*=\s*replyBarVisible\(\s*replyBarEnabled\s*,\s*bodyReady\s*,\s*showBar\s*,?\s*\)""",
            ).containsMatchIn(screen),
        )
    }

    @Test fun `the blank the document reserves goes through the same setting`() {
        val screen = code(MESSAGE_SCREEN).replace(Regex("""\s+"""), " ")
        assertTrue(
            "the bottom inset must be the WHOLE call " +
                "bodyBottomInsetPx(replyBarEnabled, barHeightPx, density.density). It is a DIV " +
                "inside the HTML document, not Compose padding, so hiding the bar without zeroing " +
                "it leaves a strip of white at the end of every message.\n" +
                "The two dp values it used to be handed (the fallback height and the clearance) " +
                "now live inside the function: as two `val`s here they could be swapped for each " +
                "other — the measured bar plus 76 dp instead of plus 4, about 72 dp of white at " +
                "the end of every message, with the first frame identical (4 + 76 = 76 + 4) and " +
                "no test able to reach it. ReplyBarTest fails on that swap now.",
            "val bottomInsetPx = bodyBottomInsetPx(replyBarEnabled, barHeightPx, density.density)" in screen,
        )
    }

    @Test fun `the invisible measuring copy is not composed when the bar is off`() {
        // alpha(0f) hides a node from the eye, not from the finger: Compose hit-tests a fully
        // transparent node like any other. With the setting OFF and nothing drawn on top of it —
        // offline, or a body that failed to load — a band the height of a bar the user switched
        // off would sit at the bottom of the reader swallowing taps.
        val screen = code(MESSAGE_SCREEN)
        val at = screen.indexOf("ReplyForwardBar {}")
        assertTrue(
            "the invisible measuring copy (ReplyForwardBar {}) is gone from MessageScreen — if it " +
                "moved, move this rule with it.",
            at >= 0,
        )
        val before = screen.substring(0, at).takeLast(400)
        assertTrue(
            "the measuring copy must sit inside 'if (replyBarEnabled) {'. Preceding source was:\n$before",
            Regex("""if \(replyBarEnabled\) \{[\s\S]*$""").containsMatchIn(before),
        )
    }

    @Test fun `the reveal machinery is not what the setting switches off`() {
        // bodyReady also drives the body's alpha and its spinner; scrollY drives the collapsing
        // header. The setting must reach barVisible and the inset, and stop there — gating the
        // reveal itself would make the message body disappear along with the bar.
        val touched = code(MESSAGE_SCREEN).lines()
            .filter { "replyBarEnabled" in it }
            .filter { line -> REVEAL_MACHINERY.any { it.containsMatchIn(line) } }
        assertTrue(
            "replyBarEnabled must not reach the reveal machinery. bodyReady governs the body's " +
                "alpha and its spinner — gate it and the message itself disappears — and scrollY " +
                "governs the collapsing header. The setting removes the BAR and the blank kept for " +
                "it, nothing else. Offending lines: $touched",
            touched.isEmpty(),
        )
        assertTrue(
            "and it must still reach both of the two places it belongs.",
            "replyBarVisible(replyBarEnabled" in code(MESSAGE_SCREEN).replace(Regex("""\s+"""), " ") &&
                "bodyBottomInsetPx(replyBarEnabled" in code(MESSAGE_SCREEN).replace(Regex("""\s+"""), " "),
        )
    }

    @Test fun `the setting is exported and restored like every other preference`() {
        val repository = code(SETTINGS_REPOSITORY)
        assertTrue(
            "SettingsRepository.snapshot must carry 'replyBar = replyBar.first()': without it the " +
                "switch is absent from every export, and a restore on a new device silently puts " +
                "the bar back.",
            Regex("""replyBar\s*=\s*replyBar\.first\(\s*\)""").containsMatchIn(repository),
        )
        assertTrue(
            "restoreBackup must apply it: 'backup.replyBar?.let { setReplyBar(it) }'. Exported and " +
                "never read back is the same defect one step later.",
            Regex("""backup\.replyBar\?\.let\s*\{\s*setReplyBar\(\s*it\s*\)\s*}""")
                .containsMatchIn(repository),
        )
    }

    @Test fun `the subtitle's promise matches where the two actions really are`() {
        // The setting's subtitle told the user where Reply and Forward would still be found with
        // the bar off, and it was wrong in nine languages: "Both stay available in the toolbar at
        // the top" — Forward is not in the toolbar, it is in the overflow menu beside it. Reply's
        // own icon is there; Reply all and Forward are menu items.
        //
        // So the rule reads the READER, not the sentence: if Forward ever moves up into the
        // toolbar, this fails and the sentence is rewritten with it.
        val screen = code(MESSAGE_SCREEN).replace(Regex("""\s+"""), " ")
        assertTrue(
            "Reply must still be a toolbar icon of the reader: IconButton(onClick = { " +
                "onReply(\"reply\", replyTargetId, accountId) }). Screen was normalised.",
            """IconButton(onClick = { onReply("reply", replyTargetId, accountId) })""" in screen,
        )
        assertTrue(
            "Forward must still be a DropdownMenuItem, not a toolbar icon — the subtitle says it " +
                "is in the menu.",
            Regex(
                """DropdownMenuItem\( text = \{ Text\(stringResource\(R\.string\.message_forward\)\) }[^)]*""" +
                    """[\s\S]{0,200}?onReply\("forward", replyTargetId, accountId\)""",
            ).containsMatchIn(screen),
        )
        assertTrue(
            "Forward must NOT also be a toolbar IconButton: two ways to reach it makes the " +
                "sentence ambiguous again.",
            """IconButton(onClick = { onReply("forward"""" !in screen,
        )
    }

    @Test fun `every language puts Forward in a menu, not in the top bar`() {
        // Same instrument and same trade as SettingsScreenHonestyTest's protocol rule: requiring a
        // literal constrains the SHAPE of every translation, and that is accepted here because the
        // whole point of the sentence is WHERE the action is. Translation parity cannot see this —
        // the key exists in every locale already; only its content was false.
        val files = localeStringFiles()
        assertTrue(
            "only ${files.size} locale string files found; the app ships at least nine, so this " +
                "rule is no longer reading them all and a locale can keep the old sentence.",
            files.size >= 9,
        )
        val wrong = files.mapNotNull { file ->
            val subtitle = SUBTITLE.find(file.readText())?.groupValues?.get(1)
                ?: return@mapNotNull "${file.parentFile.name}: no settings_reply_bar_subtitle"
            val namesAMenu = MENU_WORDS.any { it in subtitle.lowercase() }
            if (namesAMenu) null else "${file.parentFile.name}: does not say Forward is in a menu — \"$subtitle\""
        }
        assertEquals(
            "the reply-bar subtitle must place Forward in its MENU, in every language: the shipped " +
                "sentence promised the top toolbar, where Forward is not, and a reader who turns " +
                "the bar off goes looking for it there.",
            emptyList<String>(), wrong,
        )
    }

    // -- reading the sources --------------------------------------------------------------------

    private fun localeStringFiles(): List<File> = (File(root, "app/src/main/res").listFiles() ?: emptyArray())
        .filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) }
        .map { File(it, "strings.xml") }
        .filter { it.isFile && SUBTITLE.containsMatchIn(it.readText()) }
        .sortedBy { it.parentFile.name }


    /** [file]'s code as one string, comments cut — the comments beside these call sites name the
     *  very expressions the rules forbid. */
    private fun code(file: File): String = file.readLines().mapNotNull { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }.joinToString("\n")

    /** [line] up to its first `//` outside a double-quoted string; `\` escapes the next character. */
    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    @Test fun `the settle poll refuses to conclude on a reading that measured nothing`() {
        // The reader's ONE guaranteed report of the resting geometry is this poll's terminal one.
        // contentRangePx() is floored at the view's own height, so a body whose renderer is still
        // booting reads that floor over and over — three agreeing readings of nothing. Concluding
        // there announced "the body fits" for the first message opened after process start, and the
        // ordering rule of barAfterReport then made that verdict permanent: the bar followed the
        // RANK of the selection, not the body.
        //
        // BarReveal now discards such a report. That alone is not enough, and this rule is why: if
        // the poll still STOPPED on the floor, its report would be discarded and nothing would ever
        // report the real geometry — a body that fits would lose its bar for good, which is worse
        // than the defect. The gate must be the poll's, and it must be rangeMeasured (BodyRevealTest
        // runs that decision); the old 'range > 0' is satisfied by the floor itself.
        val screen = code(MESSAGE_SCREEN).replace(Regex("""\s+"""), " ")
        assertTrue(
            "the settle poll must gate its stability counter on the WHOLE call " +
                "BodyReveal.rangeMeasured(range, webView.visibleExtentPx(), webView.contentHeight) " +
                "&& range == settleLast. Back on 'range > 0 && range == settleLast' it concludes on " +
                "the pre-layout floor again, and the bar is a function of the rank of the selection.",
            "if (BodyReveal.rangeMeasured(range, webView.visibleExtentPx(), webView.contentHeight) " +
                "&& range == settleLast ) {" in screen,
        )
        assertTrue(
            "and the cap must still report, AND mark itself the last word: " +
                "reportScroll(webView, settleStable < 2). The capped report is the only report of " +
                "the resting geometry the reader is guaranteed — a body that fits never scrolls — " +
                "so BarReveal must be allowed to take it even unmeasured. Drop the flag and a short " +
                "body whose renderer never announced a document loses its bar for the life of the " +
                "page, with no gesture that brings it back.",
            "if (settleStable >= 2 || triesLeft <= 0) { webView.reportingEnabled = true " +
                "reportScroll(webView, settleStable < 2)" in screen,
        )
        assertTrue(
            "the poll's two counters are load-bearing and neither is reachable from a JVM test: " +
                "'settleStable++' (three agreeing reads, not two — two reopens the flicker of #63) " +
                "and 'webView.postDelayed({ settlePoll(50) }, 50)' (a ~2.5s cap; shorten it and the " +
                "cap lands inside the cold-renderer window, where the last-word report is the " +
                "pre-layout floor and the bar comes back on the FIRST message of the process).",
            "settleStable++" in screen &&
                "webView.postDelayed({ settlePoll(50) }, 50)" in screen,
        )
        assertTrue(
            "nothing may sit between the reading and the gate: the whole block must be " +
                "val range = webView.contentRangePx() if (BodyReveal.rangeMeasured(...)). A line " +
                "inserted here (e.g. forcing settleStable) restores the defect while every " +
                "substring this rule reads stays intact.",
            "val range = webView.contentRangePx() if (BodyReveal.rangeMeasured(range, " +
                "webView.visibleExtentPx(), webView.contentHeight) && range == settleLast ) {"
                in screen,
        )
    }

    @Test fun `the two writers are wired to the one place that decides`() {
        // Both doors must fold through BarReveal, and the live one must pass the last-word flag on.
        // Rewired to anything else — or the flag dropped at the call site — the guards above are
        // still there, still tested, and no longer reached.
        val screen = code(MESSAGE_SCREEN).replace(Regex("""\s+"""), " ")
        assertTrue(
            "the height poll's door must be onReady = { resting -> showBar = " +
                "barReveal.bodyReady(resting, revealThresholdPx) ... }",
            "showBar = barReveal.bodyReady(resting, revealThresholdPx)" in screen,
        )
        assertTrue(
            "the scroll door must be onScroll = { m, lastWord -> ... showBar = " +
                "barReveal.scrolled(m, revealThresholdPx, lastWord) }. Dropping lastWord here " +
                "silently discards the capped report again.",
            "onScroll = { m, lastWord -> scrollY.intValue = m.scrollY showBar = " +
                "barReveal.scrolled(m, revealThresholdPx, lastWord) }" in screen,
        )
    }

    @Test fun `both doors report the raw geometry, so the decision stays in one place`() {
        // The two writers must hand BarReveal what they READ — range, viewport and the document's
        // own laid-out height — not a maxScroll they already reduced. Reduced to maxScroll, "the
        // renderer has not laid out yet" and "the body fits" are the same number, and no guard
        // downstream can tell them apart.
        val screen = code(MESSAGE_SCREEN).replace(Regex("""\s+"""), " ")
        assertTrue(
            "reportScroll must build the whole BodyMetrics( scrollY = wv.scrollY, rangePx = " +
                "wv.contentRangePx(), viewportPx = wv.visibleExtentPx(), contentHeightPx = " +
                "wv.contentHeight, ). Dropping contentHeightPx leaves the floor indistinguishable " +
                "from a body that fits.",
            "BodyMetrics( scrollY = wv.scrollY, rangePx = wv.contentRangePx(), viewportPx = " +
                "wv.visibleExtentPx(), contentHeightPx = wv.contentHeight, )" in screen,
        )
        assertTrue(
            "restingMetrics must do the same, keeping the tallest-reading rule: BodyMetrics( " +
                "scrollY = body.scrollY, rangePx = maxOf(step.px, maxSeen), viewportPx = " +
                "body.visibleExtentPx(), contentHeightPx = body.contentHeight, )",
            "BodyMetrics( scrollY = body.scrollY, rangePx = maxOf(step.px, maxSeen), viewportPx = " +
                "body.visibleExtentPx(), contentHeightPx = body.contentHeight, )" in screen,
        )
    }
    companion object {
        /** The reveal machinery, which the setting must not touch: two of these three serve the
         *  body and the header, not the bar. */
        private val REVEAL_MACHINERY = listOf(
            Regex("""\bbodyReady\s*="""),
            Regex("""\bshowBar\s*="""),
            Regex("""\bscrollY\b"""),
            Regex("""\bBarReveal\b"""),
            Regex("""\bspinnerDue\b"""),
            Regex("""\.alpha\("""),
        )

        /** The reply-bar subtitle, per locale. */
        private val SUBTITLE = Regex(
            "<string name=\"settings_reply_bar_subtitle\">(.*?)</string>",
            RegexOption.DOT_MATCHES_ALL,
        )

        /** "Menu", in the nine languages the app ships. */
        private val MENU_WORDS = listOf("menu", "menü", "menú", "меню")

        private const val MESSAGE_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt"
        private const val SETTINGS_REPOSITORY_PATH =
            "core/data/src/main/kotlin/app/sterna/core/data/settings/SettingsRepository.kt"

        /** Repo root, walked up from the module's working directory — the rules read BOTH modules. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, MESSAGE_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val MESSAGE_SCREEN: File by lazy { File(root, MESSAGE_SCREEN_PATH) }
        private val SETTINGS_REPOSITORY: File by lazy { File(root, SETTINGS_REPOSITORY_PATH) }
    }
}
