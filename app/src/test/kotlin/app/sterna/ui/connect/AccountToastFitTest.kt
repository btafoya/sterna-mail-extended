package app.sterna.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE + RESOURCE LINT, NOT A BEHAVIOUR TEST — said first, because what it guards is a RENDERING
 * defect, and nothing in this module can render anything.
 *
 * An add that lands on an account already installed refreshes it in place and says so in a
 * `Toast` ([accountAddedToast], executed in [AccountAddedToastTest]). A `Toast` is TWO LINES, hard,
 * with no setting anywhere: what does not fit is cut, with no ellipsis and nothing on screen saying
 * text was lost. Measured on the virtual bench on 2026-08-18 (`emu`, Android 16, 411 dp, release
 * variant), `connect_account_refreshed`:
 *
 * | locale        | chars | rendered                                              |
 * |---------------|-------|-------------------------------------------------------|
 * | `values` (en) |    71 | whole                                                 |
 * | `values-it`   |    72 | whole                                                 |
 * | `values-de`   |    80 | CUT — "… Seine Verbindung wurde **a…**"                |
 * | `values-pl`   |    82 | CUT — "… Połączenie zostało **od…**"                   |
 *
 * The cut therefore falls between 72 and 80 characters at 411 dp. That is the ONLY measurement that
 * exists; everything below is derived from it and from nothing else.
 *
 * **Two limits, written down rather than left to be discovered** (the same two
 * [app.sterna.ui.SenderRuleDialogFitTest] states, for the same reason):
 *
 *  - a character count is a **proxy** for rendered width. It is blind to the script (Cyrillic and
 *    German set wider than English at equal length), to the font, and to the font scale. It cannot
 *    prove a sentence fits; it can only refuse the ones that certainly do not.
 *  - the threshold comes from a **bench measurement, not from Android**. There is no spec behind
 *    `Toast`'s two lines being 72-to-80 characters wide, and no way to derive one here.
 *
 * The identifiers are EXTRACTED, never listed: a list typed into a test is a copy of the code, and
 * a copy stays green while the code changes underneath it (the lesson `SenderVolumeCopyTest`
 * carries, and the one `SenderRuleDialogFitTest` was written around).
 */
class AccountToastFitTest {

    /**
     * 56 characters, and where the number comes from — a bench reading, three steps, all of them
     * arguable and none of them a specification:
     *
     *  1. 72 characters rendered WHOLE at 411 dp (`values-it`, above). 80 did not.
     *  2. the narrow bench is 360 dp (`ultranabla`), i.e. 360/411 of that width: ≈ 63 characters.
     *  3. 63 is a LATIN reading. Cyrillic and German set wider per character at equal length, so
     *     the plafond keeps a margin under it: **56**.
     *
     * It is not a width. It is a count of characters standing in for one, and the sentence that
     * exactly hits 56 in Russian may still be cut. What this number is good for is refusing the
     * sentences already known to be too long.
     *
     * NOT COVERED — THE FONT SCALE, and it is the axis that produced the defect in the file this
     * one is modelled on. Every figure above was read at the DEFAULT font scale, while
     * [app.sterna.ui.SenderRuleDialogFitTest] derives its own threshold from a measurement at
     * `font_scale 2.0`. Two lines hold far fewer characters there — an extrapolation from the 411 dp
     * reading puts it somewhere around 36 to 40, which the nine sentences shipped today (41 to 52
     * characters) would straddle. That range is arithmetic, not a measurement: nobody has rendered
     * this toast at a large font scale. It is deliberately NOT acted on here — shortening the
     * sentences on a number nobody measured would cost one of the two propositions the rule below
     * demands, and buy an unproven fit. The large-font-scale check belongs to the bench, and until
     * it happens this plafond says nothing whatsoever about it.
     */
    private val maxToastChars = 56

    /**
     * The rule that keeps the other two honest: what is being measured is FOUND.
     *
     * A lint that extracts its subject can extract nothing — a renamed function, a route that stops
     * passing a resource, a `<string>` tag the pattern misses — and then report success over an
     * empty set, for as long as nobody looks. A rule quietly matching nothing is worse than no rule.
     */
    @Test fun `the sentences a finished add can show are found, and there is at least one`() {
        val body = decisionBody()
        assertTrue(
            "the body of accountAddedToast must still name at least one R.string: the re-add " +
                "sentence is chosen THERE and nowhere else (the routes are forbidden to name it, " +
                "see OAuthCodeWiringTest), so a body that names none has moved the decision " +
                "somewhere this rule cannot see, and every length below would be measured over a " +
                "set missing the one sentence this file exists for. Body was:\n$body",
            resourceIds(body).isNotEmpty(),
        )
        assertTrue(
            "no call to accountAddedToast( was found under app/src/main/kotlin — either the toast " +
                "is no longer shown anywhere (in which case delete this file, do not leave it " +
                "green) or the call was renamed and this rule has stopped reading the routes' own " +
                "sentences.",
            callSites().isNotEmpty(),
        )
        assertTrue(
            "no string id at all was extracted; the two rules below would then pass over an empty " +
                "set and report success while measuring nothing.",
            toastIds().isNotEmpty(),
        )
    }

    /**
     * FAIL CLOSED on the resources, second half. An id the string map does not resolve is measured
     * by NOTHING, silently, at any length: a sentence declared in a res file this rule does not
     * read (a split into `values/labels.xml`, ordinary once a file grows), or one whose `<string>`
     * tag this file's pattern misses (an attribute written BEFORE `name`, which neither this
     * pattern nor `TranslationParityTest`'s matches — so parity would be happy too).
     */
    @Test fun `every sentence an add ends on resolves in the nine languages`() {
        val strings = locales().associate { it.name to stringsOf(it) }
        val unresolved = toastIds().flatMap { id ->
            strings.filterValues { id !in it }.keys.map { "R.string.$id: not read from $it" }
        }
        assertEquals(
            "every sentence an add can end on must be READABLE by this rule in all nine locales, " +
                "or the length rule is measuring nothing while reporting success. An id that " +
                "resolves nowhere is not a translation problem — TranslationParityTest is happy " +
                "with a key that is consistently absent from THIS rule's view — it is this rule " +
                "going blind. Unresolved:\n" + unresolved.joinToString("\n"),
            emptyList<String>(),
            unresolved,
        )
    }

    /**
     * THE DEFECT: a two-line box, and a sentence longer than two lines.
     *
     * What the reader loses is not decoration. The toast is the ONLY thing that says the address
     * she typed was already installed and that its connection was refreshed rather than a second
     * account created; cut at "Seine Verbindung wurde a…" she is told an account exists and not
     * what happened to it, which is the half that stops her from adding the same mailbox again.
     */
    @Test fun `no sentence an add ends on is too long for a toast`() {
        val strings = locales().associate { it.name to stringsOf(it) }
        val offenders = toastIds().sorted().flatMap { id ->
            strings.entries.sortedBy { it.key }.mapNotNull { (locale, texts) ->
                val text = texts[id] ?: return@mapNotNull null
                if (text.length <= maxToastChars) null
                else "$locale/$id is ${text.length} chars — \"$text\""
            }
        }
        assertEquals(
            "a Toast renders TWO LINES and cuts the rest with no ellipsis and no scroll: measured " +
                "on emu at 411 dp, 72 characters came out whole and 80 came out as \"… Seine " +
                "Verbindung wurde a…\". What is lost is the clause saying the connection was " +
                "REFRESHED — so the reader is left unable to tell whether the sign-in did " +
                "anything, and adds the same mailbox again. A sentence shown in a toast must hold " +
                "in $maxToastChars characters in all nine languages (see the derivation on " +
                "maxToastChars: a 411 dp bench reading brought back to the 360 dp bench, with a " +
                "margin for scripts that set wider than Latin). Too long:\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * THE OTHER HALF, and the one the plafond above cannot see: a sentence can FIT and still be
     * the wrong sentence.
     *
     * The mutation that survived the first version of this file, with the whole suite green:
     *
     * ```xml
     * <string name="connect_account_refreshed">Konto hinzugefügt.</string>
     * ```
     *
     * 18 characters, comfortably under the plafond — and `connect_account_added` IS "Konto
     * hinzugefügt" in German. A re-add then announces a created account again: the defect this
     * whole branch exists to remove, back, with every length rule green. Its variant survived too:
     * `<string name="connect_account_refreshed">@string/connect_account_added</string>`, which
     * AAPT2 resolves at build time while this lint counts 29 characters of XML literal.
     *
     * So three things are pinned on the re-add sentence, in all nine languages:
     *
     *  1. it DIFFERS from every sentence a route says on a real create — compared normalised
     *     (trimmed, lowercased, trailing punctuation dropped), because a full stop is otherwise
     *     all it takes to make "Konto hinzugefügt." a different string from "Konto hinzugefügt";
     *  2. it is a LITERAL, never a resource reference: everything this file measures is the XML
     *     text, never what Android renders, so an `@string/…` alias is text this rule reads and
     *     the reader never sees. Refused for the create sentences too, since rule 1 compares
     *     against their literals as well;
     *  3. it carries TWO propositions — two non-empty fragments around a full stop. That is the
     *     substance of the message: *the account was already there*, AND *its connection was
     *     refreshed*. A sentence keeping only one of the two leaves the reader unable to tell what
     *     just happened to the address she typed.
     */
    @Test fun `the re-add sentence says a re-add, in every language, and not a create`() {
        val strings = locales().associate { it.name to stringsOf(it) }
        val refreshed = resourceIds(decisionBody()).toSet()
        val created = callSites().flatMap { createdResOf(it) }.toSet() - refreshed
        assertTrue(
            "this rule needs both sides to exist: the re-add sentence chosen in the body of " +
                "$DECISION (found: $refreshed) and the sentences the routes say on a real create " +
                "(found: $created). With either side empty the comparison below is vacuous and " +
                "would report success over nothing.",
            refreshed.isNotEmpty() && created.isNotEmpty(),
        )

        val aliases = (refreshed + created).sorted().flatMap { id ->
            strings.entries.sortedBy { it.key }.mapNotNull { (locale, texts) ->
                val text = texts[id]?.trim() ?: return@mapNotNull null
                if (!text.startsWith("@")) null else "$locale/$id is a reference — \"$text\""
            }
        }
        assertEquals(
            "a sentence written as a resource reference is a sentence this file cannot read: " +
                "AAPT2 resolves \"@string/connect_account_added\" at build time and the reader " +
                "sees \"Account added\" after a re-add, while every rule here counts the 29 " +
                "characters of the alias and finds them short, distinct and well-formed. The " +
                "sentence must be spelled out in each language. Aliases:\n" +
                aliases.joinToString("\n"),
            emptyList<String>(),
            aliases,
        )

        val impostors = refreshed.sorted().flatMap { id ->
            strings.entries.sortedBy { it.key }.flatMap { (locale, texts) ->
                val text = texts[id] ?: return@flatMap emptyList()
                created.mapNotNull { other ->
                    val create = texts[other] ?: return@mapNotNull null
                    if (normalised(text) != normalised(create)) null
                    else "$locale/$id is \"$text\", which is $other (\"$create\")"
                }
            }
        }
        assertEquals(
            "⛔ the re-add sentence must not be a create sentence. Nothing was added: the account " +
                "was already installed and its connection refreshed in place. Told \"Account " +
                "added\", the reader goes looking for a second account in the list, does not find " +
                "one, and adds the mailbox again — which is the defect this whole branch removes, " +
                "and it comes back under the plafond without a single length rule going red. " +
                "Compared normalised, so a trailing full stop does not buy a pass. Found:\n" +
                impostors.joinToString("\n"),
            emptyList<String>(),
            impostors,
        )

        val halved = refreshed.sorted().flatMap { id ->
            strings.entries.sortedBy { it.key }.mapNotNull { (locale, texts) ->
                val text = texts[id] ?: return@mapNotNull null
                val propositions = text.split('.').map { it.trim() }.filter { it.isNotEmpty() }
                if (propositions.size >= 2) null
                else "$locale/$id has ${propositions.size} — \"$text\""
            }
        }
        assertEquals(
            "the re-add sentence must carry BOTH of its propositions: that the account was " +
                "already set up, and that its connection was refreshed. Shortening it to one of " +
                "the two is how it fits the two lines while ceasing to say what happened — the " +
                "reader is left either not knowing the address was already installed, or not " +
                "knowing the sign-in did anything at all. Two fragments around a full stop, in " +
                "every language. Found:\n" + halved.joinToString("\n"),
            emptyList<String>(),
            halved,
        )
    }

    /** Trimmed, lowercased, trailing punctuation dropped: what two sentences must differ by MORE
     *  than. "Konto hinzugefügt." and "Konto hinzugefügt" are the same sentence to a reader. */
    private fun normalised(text: String): String =
        text.trim().lowercase().trimEnd('.', '!', '?', '…', ' ')

    // -- extracting the sentences ---------------------------------------------------------------

    /**
     * Every string id a finished add can put on screen:
     *  - what the DECISION itself names — the body of `accountAddedToast`, which is where the
     *    re-add sentence is chosen;
     *  - plus every `R.string.<id>` a route hands in as its second argument, wherever it is called.
     *
     * Extracted both ways on purpose: neither end knows the whole set. The decision holds the
     * sentence no route is allowed to name, and each route holds the sentence it says on a real
     * create — today `null`, `connect_account_added`, `connect_outlook_added`.
     *
     * A SEVENTH ROUTE INHERITS THESE RULES ONLY IF IT CALLS [accountAddedToast]. One that shows
     * a string of its own is invisible here, and silently so: the three rules above fail closed
     * when this lint goes TOTALLY blind (no decision body, no call site, no id at all), never when
     * it goes PARTIALLY blind — the sets stay comfortably non-empty on the six routes that do call
     * it, and the seventh's sentence is simply never measured. What keeps that from happening is
     * `ConnectPrimingWiringTest` / `OAuthCodeWiringTest`, which read the routes themselves; this
     * file cannot see a route it is not led to.
     */
    private fun toastIds(): Set<String> =
        (resourceIds(decisionBody()) + callSites().flatMap { createdResOf(it) }).toSet()

    /**
     * The body of `accountAddedToast`: its declaration line plus every indented line under it (a
     * top-level declaration starts at column 0, so that is where the body ends).
     *
     * Fails loudly when the declaration is gone: the alternative is reading an empty string and
     * measuring the routes only, with the re-add sentence — the reason this file exists — silently
     * out of scope.
     */
    private fun decisionBody(): String {
        val lines = code(VIEW_MODEL).lines()
        val at = lines.indexOfFirst { DECLARATION.containsMatchIn(it) }
        check(at >= 0) {
            "no 'fun $DECISION(' in ${VIEW_MODEL.name} — this lint reads that function's body to " +
                "learn which sentence a re-add shows, and has nothing to read if it moved or was " +
                "renamed"
        }
        val body = lines.drop(at + 1).takeWhile { it.startsWith(" ") || it.startsWith("\t") }
        return (listOf(lines[at]) + body).joinToString("\n")
    }

    /** The argument text of every call to `accountAddedToast(` under `app/src/main/kotlin`, the
     *  declaration itself excluded — it matches the same name and carries no resource. */
    private fun callSites(): List<String> = File(root, "app/src/main/kotlin")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sortedBy { it.path }
        .flatMap { file ->
            val text = code(file)
            CALL.findAll(text).map { file.name to balanced(text, it.range.last) }
        }
        .map { (name, args) -> "$name: $args" }
        .toList()

    /**
     * The `createdRes` argument of one call, as a list: empty for `null` (a route that stays silent
     * on a create), one id for `R.string.<id>`.
     *
     * Anything else FAILS. A resource reached through a variable, a `when`, or an argument built
     * on the spot renders characters this file cannot count, and the honest answer is to refuse it
     * rather than pass over it — the shape of the mutation that survived `SenderRuleDialogFitTest`'s
     * first version, where the label stopped being one whole resource and every length rule stayed
     * green.
     */
    private fun createdResOf(callSite: String): List<String> {
        val args = arguments(callSite.substringAfter(": "))
        assertEquals(
            "a call to $DECISION must pass exactly two arguments; this rule reads the second one " +
                "to learn what that route says on a create. Found in $callSite",
            2,
            args.size,
        )
        val createdRes = args[1]
        if (createdRes == "null") return emptyList()
        val id = RESOURCE.matchEntire(createdRes)?.groupValues?.get(1)
        assertTrue(
            "the second argument of $DECISION must be exactly 'R.string.<id>' or 'null' — found " +
                "'$createdRes' in $callSite. A sentence reached any other way is text this rule " +
                "cannot measure, and it would then sit in a two-line box at any length with every " +
                "rule here still green.",
            id != null,
        )
        return listOf(id!!)
    }

    private fun resourceIds(text: String): List<String> =
        RESOURCE.findAll(text).map { it.groupValues[1] }.toList()

    // -- reading the sources and the resources --------------------------------------------------

    /** Top-level arguments of a call's argument text: commas inside nested brackets do not split. */
    private fun arguments(text: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = 0
        text.forEachIndexed { i, c ->
            when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    out += text.substring(start, i)
                    start = i + 1
                }
            }
        }
        out += text.substring(start)
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun balanced(text: String, from: Int): String {
        val start = text.indexOf('(', from).let { if (it < 0) from else it + 1 }
        var depth = 1
        var i = start
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return text.substring(start, (i - 1).coerceAtLeast(start)).trim()
    }

    /** The file with its comment lines and trailing comments removed, so no rule here can be
     *  satisfied — or defeated — by prose. Copied from `SenderRuleDialogFitTest`. */
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

    /** The nine shipped locales, as `SenderRuleDialogFitTest.locales()` counts them: a tenth
     *  language added without a translation of these sentences must not slip through as
     *  "not checked". */
    private fun locales(): List<File> = (res.listFiles() ?: emptyArray())
        .filter { it.isDirectory && File(it, "strings.xml").isFile }
        .sortedBy { it.name }
        .also { check(it.size == 9) { "expected 9 locales, found ${it.size}" } }

    private fun stringsOf(dir: File): Map<String, String> =
        STRING.findAll(File(dir, "strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        const val DECISION = "accountAddedToast"

        /** The declaration, and the call, told apart — the declaration carries no resource and
         *  would fail the two-argument reading on its own parameter list. */
        val DECLARATION = Regex("""\bfun\s+$DECISION\(""")
        val CALL = Regex("""(?<!fun )\b$DECISION\(""")

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
            File(root, "app/src/main/kotlin/app/sterna/ui/connect/ConnectViewModel.kt")
        }
    }
}
