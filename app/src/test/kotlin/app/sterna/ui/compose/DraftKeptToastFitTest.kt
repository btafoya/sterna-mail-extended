package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE + RESOURCE LINT, NOT A BEHAVIOUR TEST — said first, because what it guards is a RENDERING
 * defect, and nothing in this module can render anything.
 *
 * A save that could not replace the draft it reopened says so in a `Toast`: the decision is
 * `draftSaveNeedsNotice(outcome)` (executed in `LocalDraftSaveTest`), the sentence it shows is
 * chosen in the body of that guard in `ComposeViewModel.kt`, and `ComposeScreen.kt` collects
 * `viewModel.notices` into `Toast.makeText(context, res, Toast.LENGTH_LONG)`.
 *
 * A `Toast` on Android 12+ is TWO LINES beside the app icon, hard, with no setting anywhere.
 * Measured on the virtual bench on 2026-08-25 (`emu`, Android 16, 411 dp, release variant),
 * `compose_draft_original_kept` as it was first shipped:
 *
 * | locale        | chars | rendered                                                    |
 * |---------------|-------|-------------------------------------------------------------|
 * | `values` (en) |    97 | CUT — "… because this copy couldn't carry e**…**"            |
 * | `values-de`   |   124 | CUT — "… weil diese Kop**…**"                                |
 *
 * The half that fell was the justification; the two facts that carry the information survived by a
 * hair. The remedy taken on this branch is to bring the SENTENCE down, not the code up: a
 * `Snackbar` is not available to this screen — the message is read 2.5 to 6 s after the tap, after
 * the composer has closed.
 *
 * **Two limits, written down rather than left to be discovered**, the same two
 * [app.sterna.ui.connect.AccountToastFitTest] states, for the same reason:
 *
 *  - a character count is a **proxy** for rendered width. It is blind to the script (German and
 *    Cyrillic set wider than English at equal length), to the font, and to the font scale. It
 *    cannot prove a sentence fits; it can only refuse the ones that certainly do not.
 *  - the threshold comes from a **bench measurement, not from Android**. There is no specification
 *    behind a two-line `Toast` holding N characters, and no way to derive one here.
 *
 * ⛔ NOT COVERED — THE FONT SCALE. Every figure above and every figure behind [maxToastChars] was
 * read at the DEFAULT font scale. Two lines hold far fewer characters at `font_scale 2.0`, nobody
 * has rendered this toast there, and this file says nothing whatsoever about it.
 *
 * ⛔ NOT COVERED — THE OTHER TOASTS OF THE COMPOSER, deliberately. Six other `_notices` sentences
 * already exceed [maxToastChars] in at least one language (`compose_emptied_draft_server_copy_kept`
 * 94 chars in pt, `compose_pgp_no_draft` 84 in de, `compose_forward_attachment_dropped` 78 in de,
 * `compose_quote_not_added` 77 in de). That is PRE-EXISTING neighbourhood, out of this branch's
 * scope and already recorded by the integrating session. This file measures the sentence of THIS
 * branch, not the family — which is why its subject is extracted from one guard and not from the
 * `_notices` flow.
 *
 * The identifier is EXTRACTED, never listed: a list typed into a test is a copy of the code, and a
 * copy stays green while the code changes underneath it.
 */
class DraftKeptToastFitTest {

    /**
     * ⭐ 56 characters — the SAME number as
     * [app.sterna.ui.connect.AccountToastFitTest.maxToastChars], and deliberately not a second
     * derivation of it. Its three steps live in that file's KDoc and are quoted here only so this
     * one can be read alone:
     *
     *  1. 72 characters came out WHOLE at 411 dp on `emu` (`values-it`, 2026-08-18). 80 did not.
     *  2. the narrow bench is 360 dp (`ultranabla`), i.e. 360/411 of that width: ≈ 63 characters.
     *  3. 63 is a LATIN reading; German and Cyrillic set wider per character, so the plafond keeps
     *     a margin under it: **56**.
     *
     * ⛔ It is not a width. It is a count of characters standing in for one, and a sentence that
     * exactly hits 56 in Russian may still be cut. What the number is good for is refusing the
     * sentences already known to be too long — which the shipped 97/124-character sentence was.
     *
     * If the bench ever contradicts it, the number moves THERE, in `AccountToastFitTest`, and this
     * file follows; two independently maintained plafonds for one box is how they drift.
     */
    private val maxToastChars = 56

    /**
     * FAIL CLOSED, first half: what is being measured is FOUND.
     *
     * A lint that extracts its subject can extract nothing — a renamed guard, a notice moved to
     * another call site, a `<string>` tag the pattern misses — and then report success over an
     * empty set, for as long as nobody looks. A rule quietly matching nothing is worse than no
     * rule.
     */
    @Test fun `the sentence a save that could not replace shows is found`() {
        val blocks = noticeBlocks()
        assertTrue(
            "no 'if (draftSaveNeedsNotice(' block was found in ${VIEW_MODEL.name}. That guard is " +
                "where the sentence shown after a save that could not replace the reopened draft " +
                "is chosen, and it is the ONLY thing this file reads. Renamed, or moved to " +
                "another file, it takes the whole lint with it: every rule below would then " +
                "measure an empty set and report success while the sentence sits in a two-line " +
                "box at any length.",
            blocks.isNotEmpty(),
        )
        assertTrue(
            "the body of the 'if (draftSaveNeedsNotice(' guard names no R.string at all. Either " +
                "the notice is no longer shown (in which case delete this file, do not leave it " +
                "green) or it is reached through a variable or a when — text this file cannot " +
                "count. Block was:\n" + blocks.joinToString("\n---\n"),
            noticeIds().isNotEmpty(),
        )
    }

    /**
     * FAIL CLOSED, second half: the resources. An id the string map does not resolve is measured
     * by NOTHING, silently, at any length — a sentence declared in a res file this rule does not
     * read (a split into `values/labels.xml`, ordinary once a file grows), or one whose `<string>`
     * tag this file's pattern misses (an attribute written BEFORE `name`, which neither this
     * pattern nor `TranslationParityTest`'s matches, so parity would be happy too). The nine
     * locales are counted for the same reason: a tenth language shipped without this sentence must
     * not slip through as "not checked".
     */
    @Test fun `the sentence resolves in the nine languages`() {
        val strings = locales().associate { it.name to stringsOf(it) }
        val unresolved = noticeIds().sorted().flatMap { id ->
            strings.filterValues { id !in it }.keys.sorted().map { "R.string.$id: not read from $it" }
        }
        assertEquals(
            "the sentence must be READABLE by this rule in all nine locales, or the rules below " +
                "are measuring nothing while reporting success. An id that resolves nowhere is " +
                "not a translation problem — TranslationParityTest is happy with a key " +
                "consistently absent from THIS rule's view — it is this rule going blind. " +
                "Unresolved:\n" + unresolved.joinToString("\n"),
            emptyList<String>(),
            unresolved,
        )
    }

    /**
     * ⭐ THE DEFECT: a two-line box, and a sentence longer than two lines.
     *
     * What the reader loses is not decoration. Cut at "… weil diese Kop…" she is told a draft was
     * saved and an original kept, and nothing about why — and the shipped English cut ("… because
     * this copy couldn't carry e…") ends mid-word, which reads as a bug rather than a message.
     *
     * Counted on what the reader SEES, not on the XML: see [unescaped].
     */
    @Test fun `the sentence is not too long for a toast`() {
        val strings = locales().associate { it.name to stringsOf(it) }
        val offenders = noticeIds().sorted().flatMap { id ->
            strings.entries.sortedBy { it.key }.mapNotNull { (locale, texts) ->
                val text = unescaped(texts[id] ?: return@mapNotNull null)
                if (text.length <= maxToastChars) null
                else "$locale/$id is ${text.length} chars — \"$text\""
            }
        }
        assertEquals(
            "a Toast renders TWO LINES beside the app icon and ellipsises the rest: measured on " +
                "emu at 411 dp on 2026-08-25, this sentence came out as \"… weil diese Kop…\" in " +
                "German and \"… because this copy couldn't carry e…\" in English. A sentence " +
                "shown in a toast must hold in $maxToastChars characters in all nine languages " +
                "(derivation on maxToastChars, and in AccountToastFitTest: a 411 dp bench reading " +
                "brought back to the 360 dp bench, with a margin for scripts that set wider than " +
                "Latin). Too long:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * ⭐ THE OTHER HALF, and the one the plafond above cannot see: a sentence can FIT and still be
     * the wrong sentence. "Saved." is five characters and says nothing.
     *
     * ⛔ **WHAT IS ACTUALLY PINNED, and it is less than the paragraph below wants.** The rule
     * counts NON-EMPTY FRAGMENTS AROUND A FULL STOP and refuses fewer than two. That is a
     * **proxy** for "two propositions", and a coarse one: it sees SHAPE, never MEANING. The
     * mutation that walks straight through it, in French:
     *
     * ```
     * Enregistré comme nouveau brouillon. Original supprimé.
     * ```
     *
     * 52 characters, two fragments, no alias — every rule in this file green, and it tells the
     * reader the exact OPPOSITE of what happened. Nothing here reads the second fact, in any of
     * the nine languages. Named the way [app.sterna.ui.connect.AccountToastFitTest] names the
     * mutations that survived its first version, so the gap is on the page rather than waiting to
     * be rediscovered.
     *
     * ⛔ And a keyword list per language is NOT the answer: a list of words typed into a test is a
     * copy of the resource it is checking, green while the sentence changes underneath it. Whether
     * each half SAYS the right thing is held by review and by the bench, not by this file.
     *
     * What the shape is still good for: the two facts are both load-bearing — *a new draft has
     * been saved* AND *the original was kept*. Drop the first and the reader does not know her
     * edit was recorded at all; drop the second and she does not know a second copy is now sitting
     * in Drafts, nor which of the two to keep. Cutting the sentence down to ONE clause is exactly
     * how it comes to fit two lines, and that is the collapse this refuses — the same shape
     * [app.sterna.ui.connect.AccountToastFitTest] pins on its own sentence.
     */
    @Test fun `the sentence keeps both of its propositions, in every language`() {
        val strings = locales().associate { it.name to stringsOf(it) }
        val halved = noticeIds().sorted().flatMap { id ->
            strings.entries.sortedBy { it.key }.mapNotNull { (locale, texts) ->
                val text = unescaped(texts[id] ?: return@mapNotNull null)
                val propositions = text.split('.').map { it.trim() }.filter { it.isNotEmpty() }
                if (propositions.size >= 2) null
                else "$locale/$id has ${propositions.size} — \"$text\""
            }
        }
        assertEquals(
            "the sentence must keep TWO non-empty fragments around a full stop — a proxy for " +
                "its two propositions, and only a proxy: this rule reads shape, never meaning, " +
                "so \"Enregistré comme nouveau brouillon. Original supprimé.\" satisfies it " +
                "while saying the opposite of what happened. What it does refuse is the COLLAPSE " +
                "to one clause, which is exactly how this sentence comes to fit two lines while " +
                "ceasing to say what happened: the reader is then left either not knowing her " +
                "edit was saved anywhere, or not knowing that there are now TWO drafts in the " +
                "folder and having no way to tell which one to keep. Found:\n" +
                halved.joinToString("\n"),
            emptyList<String>(),
            halved,
        )
    }

    /**
     * A sentence written as a resource reference is a sentence this file cannot read: AAPT2
     * resolves `@string/compose_draft_saved` at build time and the reader sees whatever that other
     * key says, while every rule here counts the characters of the alias and finds them short,
     * two-propositioned and well-formed. It must be spelled out in each language.
     */
    @Test fun `the sentence is spelled out in every language, never an alias`() {
        val strings = locales().associate { it.name to stringsOf(it) }
        val aliases = noticeIds().sorted().flatMap { id ->
            strings.entries.sortedBy { it.key }.mapNotNull { (locale, texts) ->
                val text = texts[id]?.trim() ?: return@mapNotNull null
                if (!text.startsWith("@")) null else "$locale/$id is a reference — \"$text\""
            }
        }
        assertEquals(
            "everything this file measures is the XML text, never what Android renders, so an " +
                "'@string/…' alias is text this rule reads and the reader never sees. Aliases:\n" +
                aliases.joinToString("\n"),
            emptyList<String>(),
            aliases,
        )
    }

    // -- extracting the sentence ------------------------------------------------------------------

    /** Every `R.string.<id>` the notice guard can emit. */
    private fun noticeIds(): Set<String> =
        noticeBlocks().flatMap { RESOURCE.findAll(it).map { m -> m.groupValues[1] } }.toSet()

    /**
     * The body of every `if (draftSaveNeedsNotice(…)) { … }` in `ComposeViewModel.kt`, brace
     * balanced. Every occurrence, not the first: a second guard added later and read by nobody is
     * the same blindness as no guard at all.
     *
     * Comment lines are stripped first ([code]), so the prose that names `draftSaveNeedsNotice`
     * three times in this file cannot satisfy — or defeat — the extraction.
     */
    private fun noticeBlocks(): List<String> {
        val text = code(VIEW_MODEL)
        return GUARD.findAll(text).map { balancedBraces(text, it.range.last) }.toList()
    }

    // -- reading the sources and the resources ----------------------------------------------------

    /**
     * What the READER sees, from what the XML holds: `\'`, `\"`, `\\` and `\n` are Android
     * escapes — two characters in the file, ONE on screen. Counting the XML instead would let the
     * plafond lie by one character per apostrophe, in the direction that hurts (a 57-character
     * sentence with an apostrophe would be refused for a width it does not occupy, and, worse, a
     * 56-character budget spent on backslashes would look full).
     *
     * ⛔ Backslash escapes only. No `&…;` entity appears in these strings today; one appearing
     * would be counted LONGER than it renders, which fails in the safe direction.
     */
    private fun unescaped(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                out.append(if (text[i + 1] == 'n') '\n' else text[i + 1])
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** The `{ … }` block opening at or after [from], without its braces. */
    private fun balancedBraces(text: String, from: Int): String {
        val open = text.indexOf('{', from)
        check(open >= 0) {
            "the 'if (draftSaveNeedsNotice(' at offset $from in ${VIEW_MODEL.name} opens no " +
                "block; this lint reads that block to learn which sentence the notice shows"
        }
        var depth = 1
        var i = open + 1
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return text.substring(open + 1, (i - 1).coerceAtLeast(open + 1)).trim()
    }

    /** The file with its comment lines and trailing comments removed. Copied from
     *  `AccountToastFitTest`, which copied it from `SenderRuleDialogFitTest`. */
    private fun code(file: File): String = file.readLines().mapNotNull { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }.joinToString("\n")

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

    /** The nine shipped locales, as `AccountToastFitTest.locales()` counts them. */
    private fun locales(): List<File> = (res.listFiles() ?: emptyArray())
        .filter { it.isDirectory && File(it, "strings.xml").isFile }
        .sortedBy { it.name }
        .also {
            check(it.size == 9) {
                "expected 9 locales carrying a strings.xml, found ${it.size} " +
                    "(${it.joinToString { d -> d.name }}) — a language added or a file moved " +
                    "leaves this sentence unmeasured in it"
            }
        }

    private fun stringsOf(dir: File): Map<String, String> =
        STRING.findAll(File(dir, "strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        /** The guard, and only the guard: the decision itself is executed in `LocalDraftSaveTest`,
         *  this file only follows it to the sentence. */
        val GUARD = Regex("""\bif\s*\(\s*draftSaveNeedsNotice\(""")

        /** One `R.string.<id>`. */
        val RESOURCE = Regex("""R\.string\.(\w+)""")

        /**
         * A `<string>` with its text — attributes ALLOWED, as `TranslationParityTest` reads them.
         * A pattern demanding `<string name="x">` exactly is silently blind to
         * `<string name="x" formatted="false">`: the key would still exist for the parity rule and
         * the sentence would simply drop out of the length rule, unchecked, at any length.
         */
        val STRING = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources and resources as text and needs a working directory inside " +
                        "the checkout",
                )
        }

        val res: File by lazy { File(root, "app/src/main/res") }

        val VIEW_MODEL: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt")
        }
    }
}
