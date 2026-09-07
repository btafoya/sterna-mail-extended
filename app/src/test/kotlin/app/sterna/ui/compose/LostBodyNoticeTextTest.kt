package app.sterna.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RESOURCE LINT — what the two sentences of a refused send are ALLOWED TO SAY, in the nine
 * languages. Same instrument and same disclaimer as [app.sterna.ui.TranslationParityTest]'s
 * `the draft the phone lost asks for nothing, and blames no network`, and written for the same
 * reason: nothing else in the build reads these words.
 *
 * THE MUTATION THAT SURVIVED THE FIRST VERSION OF THIS BRANCH, and the reason this file exists:
 * replacing the English `compose_body_lost_cannot_send` with `Draft saved. Your text is safe in
 * Drafts.` left the WHOLE suite green. [LostBodySendTest] executes the decision and never reads a
 * string; [LocalDraftReopenWiringTest] pins the resource IDENTIFIER, never its text;
 * [app.sterna.ui.TranslationParityTest] checks that the key exists in nine files and that its
 * format arguments match, and its nominative rules do not name these keys;
 * [DraftKeptToastFitTest] states that it measures its own sentence and no other. So the app could
 * have announced that the draft was saved and the text safe at the exact moment the next gesture
 * destroys both, and nothing would have gone red.
 *
 * WHAT IS BEING PROTECTED, in one line: the composer lost its body, and the tap that follows runs
 * `commitThenConsumeLocalDraft`, which deletes the `local_drafts` row that is the only copy of the
 * text. These two sentences are the whole of what the user is given at that moment.
 *
 * SCOPE — THESE TWO KEYS, and it must stay that way. This is not a template for the string file:
 * a rule of this shape applied to a whole resource file is a list of words to keep in step by hand,
 * green while the sentences change underneath it.
 *
 * NOT COVERED — whether each translation SAYS what the English says. A fragment screen refuses a
 * class of lies; it cannot certify a sentence. Reading the eight translations is review's job, and
 * that is why the ENGLISH is the one pinned whole.
 */
class LostBodyNoticeTextTest {

    /**
     * 56 characters, the plafond [DraftKeptToastFitTest] derives and
     * [app.sterna.ui.connect.AccountToastFitTest] owns: a `Toast` on Android 12+ is two lines
     * beside the app icon, hard. Both sentences below are shown that way (`_notices` →
     * `Toast.makeText(context, res, …)`), so both are held to it.
     *
     * It is not a width. It is a count of characters standing in for one, blind to script, font
     * and font scale. It refuses the sentences already known to be too long; it certifies nothing.
     * If the bench ever contradicts the number, it moves in `AccountToastFitTest` and this file
     * follows.
     */
    private val maxToastChars = 56

    /**
     * FAIL CLOSED. A rule that reads its subject can read NOTHING — a key renamed, a `<string>` tag
     * this pattern misses, a locale added — and then report success over an empty set for as long
     * as nobody looks. Nine locales, two keys, eighteen sentences, counted.
     */
    @Test fun `both sentences resolve in the nine languages`() {
        val strings = localeStrings()
        val missing = KEYS.sorted().flatMap { key ->
            strings.filterValues { key !in it }.keys.sorted().map { "$key: not read from $it" }
        }
        assertEquals(
            "every rule below reads these two keys out of the nine strings.xml files; one that " +
                "resolves nowhere is not a translation problem — parity is happy with a key " +
                "consistently absent from THIS rule's view — it is this rule going blind. Missing:\n" +
                missing.joinToString("\n"),
            emptyList<String>(),
            missing,
        )
        assertEquals("nine locales carry a strings.xml, and each one is screened", 9, strings.size)
        assertEquals(
            "each of the nine locales must have a forbidden-fragment list of its own. A language " +
                "added without one would be screened by nothing, which is how a sentence " +
                "promising that the text is safe gets shipped in one language only.",
            emptyList<String>(),
            (strings.keys - FORBIDDEN.keys).sorted(),
        )
    }

    /**
     * THE MUTATION, refused: neither sentence may claim the message is SAFE, SAVED, KEPT or
     * RECOVERED.
     *
     * At the moment they are shown, nothing has been saved and nothing has been recovered: the body
     * is gone from the editor, and the only thing standing between the user and losing it for good
     * is that she does NOT tap Send again. A sentence that reassures her is worse than no sentence
     * — it invites exactly the gesture the guard exists to stop.
     *
     * The roots are chosen to catch the claim rather than the word: each one is the stem the
     * language builds "saved/safe/kept/recovered" on, so an inflection does not walk around it.
     */
    @Test fun `neither sentence claims the text is safe, saved or recovered`() {
        val offenders = localeStrings().entries.sortedBy { it.key }.flatMap { (locale, texts) ->
            val forbidden = FORBIDDEN[locale].orEmpty()
            KEYS.sorted().flatMap { key ->
                val text = texts[key] ?: return@flatMap emptyList<String>()
                forbidden.filter { it in text.lowercase() }
                    .map { "$locale/$key says '$it' — \"$text\"" }
            }
        }
        assertEquals(
            "these two sentences are shown when the composer has LOST its body and the next tap " +
                "would destroy the only copy of the text. Nothing has been saved, nothing has been " +
                "recovered, and nothing is safe — a sentence saying so invites the very gesture " +
                "that loses the message. Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /** The same two-line box as every other notice of this composer. */
    @Test fun `neither sentence is too long for a toast`() {
        val offenders = localeStrings().entries.sortedBy { it.key }.flatMap { (locale, texts) ->
            KEYS.sorted().mapNotNull { key ->
                val text = texts[key] ?: return@mapNotNull null
                if (text.length <= maxToastChars) null
                else "$locale/$key is ${text.length} chars — \"$text\""
            }
        }
        assertEquals(
            "a Toast renders TWO LINES beside the app icon and ellipsises the rest (measured on " +
                "emu at 411 dp on 2026-08-25, derivation in AccountToastFitTest). Cut in half, " +
                "the instruction — the only useful half — is what falls off. Too long:\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * The ENGLISH, whole, by equality — because it is the source the eight others are translated
     * from, and because the difference between these two sentences is the whole of the second
     * defect this branch closes.
     *
     * `compose_body_lost_reopen_draft` is shown where a copy is stored behind the screen: a local
     * draft, a server draft, or the queued outbox row reopened by Outbox → Edit — which is why it
     * says "the message" and not "the draft", the queued item being no draft. There the text is NOT
     * gone, and "Type it again." is the one instruction that destroys it: three characters unblock
     * the guard and the commit deletes the store that still holds it. It asks for a gesture and
     * promises nothing about what will be found — the same shape as `compose_draft_load_failed`
     * one line above it in the file.
     *
     * `compose_body_lost_cannot_send` stays for the composer that holds no draft at all: nothing to
     * reopen, so retyping is the only true instruction left.
     *
     * The eight translations are deliberately NOT pinned: freezing their text would block a
     * translation fix while proving nothing. Their keys are held by the parity rule, their length
     * and their claims by the two rules above.
     */
    @Test fun `the English of both sentences is the one that was reviewed`() {
        assertEquals(
            "the English text of these two sentences changed. They are the source the other " +
                "eight are translated from, so a reword is a deliberate gesture, not a tidy-up. " +
                "What each owes: with a draft stored behind the screen, a gesture that WORKS " +
                "(close, reopen) and no promise about what comes back; with no draft anywhere, " +
                "retyping, which is then the only thing left to do. Swap the two and the app hands " +
                "the destructive advice to exactly the user whose text is still on the phone.",
            ENGLISH,
            localeStrings().getValue("values").filterKeys { it in KEYS },
        )
    }

    // -- reading the resources -------------------------------------------------------------------

    /** The nine locales' strings, as the READER sees them. */
    private fun localeStrings(): Map<String, Map<String, String>> =
        locales().associate { dir ->
            dir.name to STRING.findAll(File(dir, "strings.xml").readText())
                .associate { it.groupValues[1] to unescaped(it.groupValues[2]) }
                .filterKeys { it in KEYS }
        }

    /** The nine shipped locales, as `DraftKeptToastFitTest.locales()` counts them. */
    private fun locales(): List<File> = (res.listFiles() ?: emptyArray())
        .filter { it.isDirectory && File(it, "strings.xml").isFile }
        .sortedBy { it.name }
        .also {
            check(it.size == 9) {
                "expected 9 locales carrying a strings.xml, found ${it.size} " +
                    "(${it.joinToString { d -> d.name }})"
            }
        }

    /** What the reader sees: `\'`, `\"`, `\\` and `\n` are two characters in the XML, one on
     *  screen. Copied from [DraftKeptToastFitTest], which says why the count is done this way. */
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

    @Test fun `neither sentence is an alias`() {
        val aliases = localeStrings().entries.sortedBy { it.key }.flatMap { (locale, texts) ->
            texts.entries.sortedBy { it.key }
                .filter { it.value.trim().startsWith("@") }
                .map { "$locale/${it.key} is a reference — \"${it.value}\"" }
        }
        assertTrue(
            "everything here is XML text, never what Android renders, so an '@string/…' alias is " +
                "text this rule screens and the reader never sees. Aliases:\n" +
                aliases.joinToString("\n"),
            aliases.isEmpty(),
        )
    }

    private companion object {
        /** The refusal's two sentences, and nothing else in the file. */
        val KEYS = setOf("compose_body_lost_cannot_send", "compose_body_lost_reopen_draft")

        val ENGLISH = mapOf(
            "compose_body_lost_cannot_send" to "The text of this message was lost. Type it again.",
            "compose_body_lost_reopen_draft" to "Text lost. Close this screen and reopen the message.",
        )

        /**
         * The stems that carry the claim "your text is saved / safe / kept / recovered", per
         * language. Stems, not words, so an inflection does not walk around the screen; lower-cased
         * before matching.
         *
         *  - English: `saved`/`safe` are the shipped mutation itself, and `in drafts` is the place it
         *    claimed the text had gone; `kept`/`recovered`/`restored` are the same promise worded
         *    differently.
         *  - German: `gespeichert` (saved), `sicher` (safe, and the stem of `Sicherheit`),
         *    `wiederhergestellt` (restored), `aufbewahrt` (kept).
         *  - Spanish: `guardad` (saved), `salvo` (as in `a salvo`, safe), `seguro` (safe),
         *    `recuperad` (recovered), `conservad` (kept).
         *  - French: `enregistr` (saved), `sauvegard` (backed up), `récupér` (recovered),
         *    `conserv` (kept), `sécurité`/`sûr` (as in `en sécurité`, `en lieu sûr`).
         *  - Italian: `salv` (the stem under salvato/salvo/salvare), `recuperat` (recovered),
         *    `conservat` (kept), `sicuro` (safe).
         *  - Dutch: `opgeslagen` (saved), `veilig` (safe), `hersteld` (restored), `bewaard` (kept).
         *  - Polish: `zapisan` (saved), `bezpieczn` (safe), `odzyskan` (recovered), `zachowan`
         *    (kept).
         *  - Portuguese: `guardad` (saved), `salvo`/`seguro` (safe), `recuperad` (recovered),
         *    `conservad` (kept).
         *  - Russian: `сохран` (saved/kept), `безопасн` (safe), `восстановл` (restored).
         */
        val FORBIDDEN = mapOf(
            "values" to listOf("saved", "safe", "kept", "recovered", "restored", "in drafts"),
            "values-de" to listOf("gespeichert", "sicher", "wiederhergestellt", "aufbewahrt", "gerettet"),
            "values-es" to listOf("guardad", "salvo", "seguro", "recuperad", "conservad"),
            "values-fr" to listOf("enregistr", "sauvegard", "récupér", "conserv", "sécurité", "sûr"),
            "values-it" to listOf("salv", "sicuro", "recuperat", "conservat"),
            "values-nl" to listOf("opgeslagen", "veilig", "hersteld", "bewaard", "gered"),
            "values-pl" to listOf("zapisan", "bezpieczn", "odzyskan", "zachowan", "uratowan"),
            "values-pt" to listOf("guardad", "salvo", "seguro", "recuperad", "conservad"),
            "values-ru" to listOf("сохран", "безопасн", "восстанов"),
        )

        /** A `<string>` with its text, attributes allowed — the pattern [DraftKeptToastFitTest]
         *  uses, and it says why a stricter one goes blind in silence. */
        val STRING = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }

        val res: File by lazy { File(root, "app/src/main/res") }
    }
}
