package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Defect: Settings → Appearance → App language lists ten entries (system default + nine
 * locales), and the bottom ones are not clipped at paint time, they are MEASURED SHORT — so their
 * pointer area has zero height (the tap does nothing), the `RadioButton` still shows (its drawing
 * spills past its bounds) and the label is gone (a `Text` clips its own drawing to the size it was
 * measured at). The DEAD TAP is what proves measured-short rather than clipped: a clipped row
 * still takes taps. Working hypothesis for the cause, the same one
 * [SenderRuleDialogFitTest] writes down and no more verified than there: Material's `text` slot
 * hands its child a finite `maxHeight`, which a non-scrolling `Column` SPENDS row by row until the
 * last rows are handed ~0.
 *
 * Measured twice, two days running, Moto G (Android 9, 720×1280, density 320 → 360 × 400 dp): the
 * bottom entries come out as radio circles stacked ~30 px apart with NO labels, tapping them
 * selects nothing, and the list does not scroll (several swipes, screenshots identical byte for
 * byte). At `font_scale` 0.85 the box fits on screen entirely and the defect remains — the ceiling
 * is on the list, not on the box. A Russian or Polish reader therefore cannot pick her language,
 * and with the app already in Russian she cannot even read which entry is the ticked one.
 *
 * The same dialog sits behind all thirteen `SettingChoiceRow`s, including the filters' "Move to"
 * folder picker (`FiltersScreen.kt`), whose option list is UNBOUNDED and whose pick ends in a
 * server-side Sieve write.
 *
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads `SettingsComponents.kt` as text and proves nothing
 * about what happens on screen. There is no Robolectric and no `compose-ui-test` in this repo;
 * same instrument and same disclaimer as [SenderRuleDialogFitTest] and
 * [app.sterna.ui.compose.ComposeDeleteWiringTest], which close this defect on other dialogs.
 */
class SettingChoiceDialogScrollTest {

    /**
     * G1 — the scroll is on THE `Column` of the `text` slot, pinned by WHOLE-LINE equality on the
     * slot's opening line.
     *
     * Anchored on that line, and it has to be: counting the modifier "somewhere in the file", or
     * "somewhere in the slot", is green when it lands on each option's `Row` or on a nested
     * wrapper while the outer `Column` stays bounded — which is the defect, untouched. And never
     * `"verticalScroll" in line`: a substring is blind to every mutation that LENGTHENS the line,
     * and `Modifier.selectableGroup().verticalScroll(rememberScrollState()).heightIn(max = 200.dp)`
     * contains it while bounding the list right back.
     */
    @Test fun `the option list scrolls instead of being crushed against the bottom`() {
        val slot = choiceSlot()
        val head = slot.lines().first { it.isNotBlank() }.normalised()
        assertTrue(
            "the choice dialog's text slot must still OPEN with the Column that lays the options " +
                "out — this rule pins the scroll onto that Column, and must fail loudly rather " +
                "than pin it onto something it never located. Slot was:\n$slot",
            head.startsWith("Column("),
        )
        assertEquals(
            "the option list must carry exactly '$SCROLLING_LIST'. Without the scroll, Material's " +
                "bounded text slot leaves the last entries measured at nearly zero height: on a " +
                "720×1280 phone the bottom locales come out as bare radio circles with NO label, " +
                "and tapping them selects nothing (measured, Moto G, ten entries). A reader whose " +
                "language is down that list cannot choose it, and cannot see which one is ticked. " +
                "selectableGroup() must stay: it is what makes the ten rows one radio group for " +
                "TalkBack. Slot opened with:\n$head",
            SCROLLING_LIST,
            head,
        )
        assertEquals(
            "'$SCROLLING_LIST' must appear exactly once in the slot, on the list's own Column — " +
                "a second copy means the modifier also sits on something nested, and the rule " +
                "above can no longer say which Column actually scrolls. Slot was:\n$slot",
            1,
            slot.lines().count { it.normalised() == SCROLLING_LIST },
        )
    }

    /**
     * G2 — nothing beside the scroll may bound the list again.
     *
     * Written from the precedents' scars: `maxLines = 3` next to a scroll modifier left the
     * sender-rule lint green and the body clipped at three lines, the exact defect back under a
     * scroll with nothing left to scroll. A `heightIn(max = …)` / `height(…)` / `fillMaxHeight()`
     * here is the same shape one level up: the list scrolls, inside a box pinned too short, and
     * the entries below the pin are unreachable exactly as before.
     */
    @Test fun `nothing else pins the option list back to a fixed height`() {
        val slot = choiceSlot()
        assertEquals(
            "nothing INSIDE the choice dialog's text slot may bound the option list again. A " +
                "heightIn, a height, a sizeIn, a fillMaxHeight beside the scroll pins the list to " +
                "a size the ten locales do not fit in — the bottom entries go back to being " +
                "unreachable, only now under a scroll bar that hides the fact. maxLines and " +
                "overflow do it to each label. This rule reads the slot and ONLY the slot: a size " +
                "pinned on the AlertDialog itself is out of its reach, and out of the bench " +
                "reading it was written from. If you are here to add a legitimate " +
                "heightIn(min = 48.dp) — Material's minimum touch target on each option Row, " +
                "which is a floor and not a ceiling — widen this rule deliberately rather than " +
                "work around it. Slot was:\n$slot",
            emptyList<String>(),
            slot.lines().map { it.normalised() }
                .filter { line -> BOUNDS.any { line.contains(it, ignoreCase = true) } },
        )
    }

    // -- reading the source -----------------------------------------------------------------

    /**
     * The `text = { … }` slot of `SettingChoiceDialog`'s `AlertDialog`, braces balanced.
     *
     * Anchored on the declaration and checked at every step: the same file also declares
     * `SettingMultiChoiceDialog` (checkboxes, out of scope here and latent), and
     * `indexOf("text = ")` matches inside `context = …` too — hence [SLOT]'s lookbehind. Every
     * check below fails LOUDLY rather than let this rule read the wrong lambda and report success.
     */
    private fun choiceSlot(): String {
        val code = code(SETTINGS)
        val declarations = DECLARATION.findAll(code).toList()
        check(declarations.size == 1) {
            "SettingsComponents.kt must declare exactly one 'fun <T> SettingChoiceDialog(', found " +
                "${declarations.size} — this lint reads that function and has nothing to read if " +
                "it moved or was renamed"
        }
        val at = code.indexOf("AlertDialog(", declarations.single().range.last)
        check(at >= 0) { "SettingChoiceDialog no longer draws an AlertDialog" }
        val dialog = balanced(code, at, '(', ')')
        check("RadioButton(" in dialog && "optionLabel(option)" in dialog) {
            "the dialog read after 'fun <T> SettingChoiceDialog(' is not the single-choice one — " +
                "no RadioButton and no optionLabel(option) in it. Read:\n$dialog"
        }
        val slots = SLOT.findAll(dialog).toList()
        check(slots.size == 1) {
            "SettingChoiceDialog must declare exactly one 'text = {' slot, found ${slots.size}. " +
                "Dialog was:\n$dialog"
        }
        val slot = balanced(dialog, slots.single().range.last, '{', '}')
        check("confirmButton" !in slot) {
            "SettingChoiceDialog's 'text = {' slot does not close before the next slot — the " +
                "braces did not balance and this rule is reading the rest of the dialog. " +
                "Read:\n$slot"
        }
        return slot
    }

    private fun String.normalised(): String = trim().replace(Regex("""\s+"""), " ")

    /** [text] from the first [open] at or after [from], up to the [close] that balances it. */
    private fun balanced(text: String, from: Int, open: Char, close: Char): String {
        val start = text.indexOf(open, from).let { if (it < 0) from else it + 1 }
        var depth = 1
        var i = start
        while (i < text.length && depth > 0) {
            when (text[i]) {
                open -> depth++
                close -> depth--
            }
            i++
        }
        return text.substring(start, (i - 1).coerceAtLeast(start)).trim()
    }

    /** [file]'s code as one string, comments cut: a comment naming a modifier is not a modifier. */
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

    private companion object {

        val DECLARATION = Regex("""fun <T> SettingChoiceDialog\(""")

        /** Material's bounded body slot. The lookbehind is the point: it also matches `context = `. */
        val SLOT = Regex("""(?<![\w.])text = \{""")

        /** The whole line, as this repo's other source lints pin theirs. */
        const val SCROLLING_LIST =
            "Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {"

        /**
         * Everything that can pin the list, or a label, back into an unreachable size.
         *
         * Matched case-INSENSITIVELY, and that is the whole point: `requiredHeightIn(max = …)`
         * and `requiredSizeIn(maxHeight = …)` bound the list exactly as their plain namesakes do,
         * and neither contains `heightIn` nor `height(` as written. An audit walked out through
         * that gap — a `Column(Modifier.sizeIn(maxHeight = 200.dp))` wrapped around the options
         * left every rule here green while the bottom locales went back to being measured at zero.
         */
        val BOUNDS = listOf("heightIn", "height(", "fillMaxHeight", "sizeIn", "size(", "maxLines", "overflow")

        const val SETTINGS_PATH = "app/src/main/kotlin/app/sterna/ui/settings/SettingsComponents.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SETTINGS_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        val SETTINGS: File by lazy { File(root, SETTINGS_PATH) }
    }
}
