package app.sterna.core.data.filter

import app.sterna.core.data.mail.FilterRulesState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What reading an account's filters answers — [loadedFilterRules], executed.
 *
 * `MailRepository` cannot be built in a JVM test (session, JMAP client, device), so the decision
 * lives outside it and is called here with the two things it actually depends on: the body of the
 * `sterna` script, and whether another script is active. Nothing here reads a line of source.
 */
class FilterRulesReadTest {

    private val rule = FilterRule(
        name = "Boss", field = RuleField.FROM, match = RuleMatch.IS, value = "boss@example.com",
        moveTo = "Work",
    )

    @Test fun `a script this app wrote gives its rules back, with no warning`() {
        val state = loadedFilterRules(SieveCodec.generate(listOf(rule)), otherActiveScript = false)
        assertEquals(listOf(rule), state.rules)
        assertFalse("a script we can read is not foreign", state.foreignActiveScript)
    }

    @Test fun `no sterna script at all is zero rules, and no warning`() {
        val state = loadedFilterRules(sternaScript = null, otherActiveScript = false)
        assertEquals(emptyList<FilterRule>(), state.rules)
        assertFalse(state.foreignActiveScript)
    }

    /**
     * THE BUG. A `sterna` script with content nobody can decode used to come back as
     * `Loaded(rules = [], foreignActiveScript = false)` — an ordinary account with no filters.
     * The per-sender gesture was then offered, and confirming it recompiled the entire script
     * from that one rule, deleting whatever the unreadable script held.
     *
     * The flag every writer already refuses is the answer: no new state, no new string.
     */
    @Test fun `an unreadable sterna script warns instead of reporting no rules`() {
        val state = loadedFilterRules("if header :is \"subject\" \"x\" { discard; }", otherActiveScript = false)
        assertEquals("nothing readable, so no rules to show", emptyList<FilterRule>(), state.rules)
        assertTrue(
            "a script we cannot read must refuse the write, not look like an empty one",
            state.foreignActiveScript,
        )
        assertTrue(
            "…and it must say WHICH of the two refusals it is: folded into foreignActiveScript " +
                "alone, the filters screen can only show a sentence about somebody else's script",
            state.scriptUnreadable,
        )
    }

    /**
     * The two causes are told apart on the way out even though they add up on the way in. Without
     * this, "unreadable" is indistinguishable from "another script is active" and the screen keeps
     * announcing the wrong cost.
     */
    @Test fun `only the unreadable script raises the unreadable flag`() {
        assertFalse(
            "another active script says nothing about whether OURS could be read",
            loadedFilterRules(SieveCodec.generate(listOf(rule)), otherActiveScript = true).scriptUnreadable,
        )
        assertFalse(
            "no script at all is not an unreadable one",
            loadedFilterRules(null, otherActiveScript = true).scriptUnreadable,
        )
        assertFalse(
            "a blank script holds nothing to lose",
            loadedFilterRules("  ", otherActiveScript = false).scriptUnreadable,
        )
        assertTrue(
            "broken metadata is unreadable, whatever else is running",
            loadedFilterRules("# STERNA-RULES-V1: {oops\nif true { keep; }", otherActiveScript = true)
                .scriptUnreadable,
        )
    }

    /** Marker present but its JSON broken: same refusal. Half a script is not zero rules. */
    @Test fun `a sterna script with broken metadata warns too`() {
        val state = loadedFilterRules("# STERNA-RULES-V1: {oops\nif true { keep; }", otherActiveScript = false)
        assertEquals(emptyList<FilterRule>(), state.rules)
        assertTrue(state.foreignActiveScript)
    }

    /**
     * The witness. A blank script is not unreadable — it holds nothing, so rewriting it loses
     * nothing. Without this, every account whose `sterna` script is an empty stub would be
     * refused the gesture for ever.
     */
    @Test fun `a blank sterna script is zero rules, and no warning`() {
        for (blank in listOf("", "   ", "\n\n")) {
            val state = loadedFilterRules(blank, otherActiveScript = false)
            assertEquals(blank.length.toString(), emptyList<FilterRule>(), state.rules)
            assertFalse("a blank script has nothing to lose: $blank", state.foreignActiveScript)
        }
    }

    /** Unchanged: another active script warns, whatever our own script says. */
    @Test fun `another active script warns even when ours reads fine`() {
        val state = loadedFilterRules(SieveCodec.generate(listOf(rule)), otherActiveScript = true)
        assertEquals(listOf(rule), state.rules)
        assertTrue(state.foreignActiveScript)
    }

    /** The two causes add up rather than cancelling: either one alone is enough. */
    @Test fun `the two refusals add up`() {
        assertTrue(loadedFilterRules("if true { keep; }", otherActiveScript = true).foreignActiveScript)
        assertFalse(loadedFilterRules(null, otherActiveScript = false).foreignActiveScript)
    }

    // ---- a script we read only PART of: our marker, and hand-written Sieve around it ----

    /** The marker line `generate` writes for [rule] alone, JSON and all. */
    private val bossMarker =
        """# STERNA-RULES-V1: [{"name":"Boss","enabled":true,"field":"FROM","match":"IS",""" +
            """"value":"boss@example.com","moveTo":"Work","markRead":false,"flag":false}]"""

    /** The Sieve `generate` emits for [rule] alone, line by line. */
    private val bossSieve = arrayOf(
        """require ["fileinto"];""",
        "",
        """if address :is "from" "boss@example.com" {""",
        """    fileinto "Work";""",
        "}",
    )

    private fun script(vararg lines: String) = lines.joinToString("\n", postfix = "\n")

    /**
     * THE BUG THIS BRANCH FIXES. A perfectly valid marker line, and around it Sieve this app
     * never wrote — a rule added from Roundcube. The marker parsed, so the script was called fully
     * understood: the per-sender gesture was offered, and saving recompiled the whole script from
     * the JSON alone, dropping the hand-written rule without a word.
     */
    @Test fun `a rule written by hand after our own marks the script unreadable`() {
        val state = loadedFilterRules(
            script(
                bossMarker,
                *bossSieve,
                """if header :contains "subject" "sale" {""",
                "    discard;",
                "}",
            ),
            otherActiveScript = false,
        )
        assertEquals("the rules we DID read are real and must stay on screen", listOf(rule), state.rules)
        assertTrue("Sieve we did not write must refuse the per-sender write", state.foreignActiveScript)
        assertTrue(
            "…and Save on the filters screen must ask before replacing it",
            state.scriptUnreadable,
        )
    }

    /**
     * **THE LIKELIEST SHAPE OF THE BUG, and the one every other test here misses.** Sterna
     * creates the `sterna` script the first time the filters screen is opened, the user adds no
     * rule, and the marker decodes to an EMPTY list. Another client — Roundcube edits whichever
     * script is active — then adds its own rule to that same file.
     *
     * Every other foreign-Sieve test above hands in a non-empty rule list, so this one is what
     * stands between the fix and a mutation that guts it:
     *
     *     parsed == null || (parsed.isNotEmpty() && holdsForeignSieve(sternaScript, parsed))
     *
     * Measured: that edit left the whole suite green, and it reopens the reported bug exactly —
     * the per-sender gesture is offered again, and its save recompiles the script from an empty
     * list, taking the hand-written rule with it and saying nothing.
     */
    @Test fun `an empty rule list with hand-written Sieve around it is still unreadable`() {
        val state = loadedFilterRules(
            script(
                "# STERNA-RULES-V1: []",
                "",
                """require ["fileinto"];""",
                """if header :contains "subject" "roundcube" {""",
                """    fileinto "Archive";""",
                "}",
            ),
            otherActiveScript = false,
        )
        assertEquals("we read no rules of our own out of it", emptyList<FilterRule>(), state.rules)
        assertTrue(
            "zero rules of ours is not zero content: the per-sender write must stop",
            state.foreignActiveScript,
        )
        assertTrue("…and Save must ask before replacing it", state.scriptUnreadable)
    }

    /** The marker is not on line 1: everything above it is Sieve nobody here compiled. */
    @Test fun `hand-written Sieve before the marker marks the script unreadable`() {
        val state = loadedFilterRules(
            script(
                """if header :contains "subject" "invoice" {""",
                """    fileinto "Bills";""",
                "}",
                bossMarker,
                *bossSieve,
            ),
            otherActiveScript = false,
        )
        assertEquals(listOf(rule), state.rules)
        assertTrue(state.foreignActiveScript)
        assertTrue(state.scriptUnreadable)
    }

    /** A `require` this codec would never have emitted: someone edited the script. */
    @Test fun `an extra require marks the script unreadable`() {
        val state = loadedFilterRules(
            script(bossMarker, """require ["vacation"];""", *bossSieve),
            otherActiveScript = false,
        )
        assertEquals(listOf(rule), state.rules)
        assertTrue(state.foreignActiveScript)
        assertTrue(state.scriptUnreadable)
    }

    /**
     * Only the FIRST marker line is ours — the one [SieveCodec.parseRulesOrNull] read. A second one
     * further down is content in excess, so it counts as foreign like any other line.
     */
    @Test fun `a second marker line lower down marks the script unreadable`() {
        val state = loadedFilterRules(
            script(bossMarker, *bossSieve, """# STERNA-RULES-V1: [{"name":"Ghost"}]"""),
            otherActiveScript = false,
        )
        assertEquals(listOf(rule), state.rules)
        assertTrue(state.foreignActiveScript)
        assertTrue(state.scriptUnreadable)
    }

    /** The JSON says `Work`, the Sieve says `Archive`: the script no longer does what it lists. */
    @Test fun `Sieve edited away from the metadata marks the script unreadable`() {
        val state = loadedFilterRules(
            script(
                bossMarker,
                """require ["fileinto"];""",
                "",
                """if address :is "from" "boss@example.com" {""",
                """    fileinto "Archive";""",
                "}",
            ),
            otherActiveScript = false,
        )
        assertEquals(listOf(rule), state.rules)
        assertTrue(state.foreignActiveScript)
        assertTrue(state.scriptUnreadable)
    }

    /** Both causes at once: neither cancels the other, and the red line must still name ours. */
    @Test fun `a foreign script warns as unreadable even when another script is active`() {
        val state = loadedFilterRules(
            script(bossMarker, *bossSieve, "keep;"),
            otherActiveScript = true,
        )
        assertTrue(state.foreignActiveScript)
        assertTrue("another active script must not hide that OURS was half-read", state.scriptUnreadable)
    }

    // ---- the witnesses: what must NOT be called foreign ----

    /** What this app writes, read straight back. Anything else here would be a false alarm. */
    @Test fun `a script this app just generated is never foreign`() {
        val rules = listOf(
            rule,
            FilterRule(name = "off", enabled = false, value = "x@y.com", moveTo = "Work"),
            FilterRule(name = "flagged", field = RuleField.SUBJECT, value = "urgent", flag = true),
        )
        for (list in listOf(emptyList(), listOf(rule), rules)) {
            val state = loadedFilterRules(SieveCodec.generate(list), otherActiveScript = false)
            assertEquals(list, state.rules)
            assertFalse("we wrote this one: $list", state.foreignActiveScript)
            assertFalse("we wrote this one: $list", state.scriptUnreadable)
        }
    }

    /**
     * THE WITNESS THAT FIXES THE COMPARED VALUE. A script written by an OLDER version of this
     * app: the Sieve below is byte-for-byte what we emit, but the marker's JSON carries a key we
     * do not know (a field added later, or dropped later).
     *
     * Comparing the WHOLE script would call every one of those foreign — on the entire installed
     * base, the day a ninth field is added to [FilterRule], because `encodeDefaults = true` writes
     * them all. The marker line is therefore removed from the comparison, and only the Sieve is
     * compared.
     */
    @Test fun `a marker written by another version of this app is not foreign`() {
        val extraKey =
            """# STERNA-RULES-V1: [{"name":"Boss","enabled":true,"field":"FROM","match":"IS",""" +
                """"value":"boss@example.com","moveTo":"Work","markRead":false,"flag":false,""" +
                """"colour":"red"}]"""
        val missingKeys =
            """# STERNA-RULES-V1: [{"name":"Boss","field":"FROM","match":"IS",""" +
                """"value":"boss@example.com","moveTo":"Work"}]"""
        for (marker in listOf(extraKey, missingKeys)) {
            val state = loadedFilterRules(script(marker, *bossSieve), otherActiveScript = false)
            assertEquals(listOf(rule), state.rules)
            assertFalse("only the JSON differs, and the JSON is not Sieve: $marker", state.foreignActiveScript)
            assertFalse(marker, state.scriptUnreadable)
        }
    }

    /** A server that hands the blob back in CRLF is not a server that rewrote the script. */
    @Test fun `the same script in CRLF is not foreign`() {
        val crlf = SieveCodec.generate(listOf(rule)).replace("\n", "\r\n")
        val state = loadedFilterRules(crlf, otherActiveScript = false)
        assertEquals(listOf(rule), state.rules)
        assertFalse(state.foreignActiveScript)
        assertFalse(state.scriptUnreadable)
    }

    /** Trailing blanks on two lines and no final newline: still our script, character for word. */
    @Test fun `trailing spaces and a missing final newline are not foreign`() {
        val sloppy = SieveCodec.generate(listOf(rule))
            .replace(";\n", ";  \n")
            .trimEnd('\n')
        val state = loadedFilterRules(sloppy, otherActiveScript = false)
        assertEquals(listOf(rule), state.rules)
        assertFalse(state.foreignActiveScript)
        assertFalse(state.scriptUnreadable)
    }

    /**
     * The non-regression witness. A blank script holds nothing to lose, so it is zero rules and
     * NO alarm — the guard is written into the comparison itself, not left to the luck of what
     * `generate(emptyList())` happens to normalise to.
     */
    @Test fun `a blank script is never foreign`() {
        for (blank in listOf("", "   ", "\n\n", "\r\n\r\n")) {
            val state = loadedFilterRules(blank, otherActiveScript = false)
            assertEquals(emptyList<FilterRule>(), state.rules)
            assertFalse("a blank script has nothing to lose: ${blank.length}", state.foreignActiveScript)
            assertFalse("a blank script has nothing to lose: ${blank.length}", state.scriptUnreadable)
        }
    }

    /** The decision on its own, with literal arguments — no state, no fold, just the comparison. */
    @Test fun `holdsForeignSieve compares the Sieve and ignores the marker line`() {
        assertTrue(
            "one hand-written line in excess is enough",
            holdsForeignSieve(script(bossMarker, *bossSieve, "keep;"), listOf(rule)),
        )
        assertFalse(
            "the same Sieve under a marker from another version of this app is ours",
            holdsForeignSieve(
                script(
                    """# STERNA-RULES-V1: [{"name":"Boss","field":"FROM","match":"IS",""" +
                        """"value":"boss@example.com","moveTo":"Work","extra":1}]""",
                    *bossSieve,
                ),
                listOf(rule),
            ),
        )
        // Measured on 2026-08-12, and left standing: deleting the `script.isBlank()` guard from
        // holdsForeignSieve leaves the whole suite green. It is EQUIVALENT BY CONSTRUCTION, not a
        // hole — a blank script and the script we write for zero rules normalise to the same empty
        // body, and `parseRulesOrNull` answers emptyList() for a blank script, so the only call
        // loadedFilterRules can ever make is the one asserted here. The guard stays as the written
        // form of a rule that must not be rediscovered by accident (a blank script holds nothing to
        // lose), NOT because a test needs it. Pinning it would have meant asserting a state no
        // caller can reach.
        assertFalse("blank is never foreign", holdsForeignSieve("   \n\t\n", emptyList()))
        assertFalse("what we write is never foreign", holdsForeignSieve(SieveCodec.generate(listOf(rule)), listOf(rule)))
    }

    // ---- the wiring, read out of the shipped source: a LAST RESORT, not the proof ----

    /**
     * Everything above executes the decision; this one cannot. `MailRepository` opens shared
     * preferences in its constructor and needs a JMAP session, so no JVM test builds one — and a
     * decision that is correct but never called is worth nothing. A mutation campaign measured
     * exactly that: replacing the script argument with `null` in `loadFilterRules` left the whole
     * suite green.
     *
     * So the ARGUMENTS are pinned, not the fact that a function of the right name is called: that
     * the blob it downloaded is what gets handed over, and that the answer is no longer assembled
     * on the spot from `parseRules`. A rename fails this loudly rather than quietly passing.
     *
     * What a `contains` does NOT prove: a substring is satisfied by every mutation that makes
     * the line LONGER. `val managed = scripts.firstOrNull { … }?.takeIf { it.isActive }` contains
     * the text asserted below and walked straight past the first version of this lint. The line
     * that carries the selection is therefore compared WHOLE, the way
     * `MailBySenderWiringTest.the dialog is bound to the row it was opened over` does.
     */
    @Test fun `the repository hands the decision the script it downloaded`() {
        val body = loadFilterRulesBody()
        assertTrue(
            "loadFilterRules must decode the downloaded blob into a script it can hand over",
            body.contains("val script = managed?.let {") && body.contains(".toString(Charsets.UTF_8)"),
        )
        assertTrue(
            "the downloaded script itself must be the argument — not null, not an empty string",
            body.contains("sternaScript = script,"),
        )
        assertTrue(
            "the other-script question must still be asked of the OTHER scripts",
            body.contains("otherActiveScript = scripts.any { it.isActive && it.name != SieveCodec.SCRIPT_NAME }"),
        )
        // Measured: narrowing this selection to the ACTIVE script left the whole suite green, and
        // it reopens the very bug above — an unreadable script that is merely INACTIVE is then not
        // seen at all, so the gesture is offered and the save recompiles the script from an empty
        // list. The same edit also reports zero rules for a healthy but inactive script, which is
        // the state the return from holiday leaves every account in. Measured again on this lint:
        // `?.takeIf { it.isActive }` appended to the line CONTAINS the text that used to be
        // asserted, so the whole line is compared instead.
        assertEquals(
            "the account's own script must be selected BY NAME ALONE, as exactly `val managed = " +
                "scripts.firstOrNull { it.name == SieveCodec.SCRIPT_NAME }` and nothing more on " +
                "that line: a script that is not the active one is still the script this save " +
                "would overwrite",
            listOf("val managed = scripts.firstOrNull { it.name == SieveCodec.SCRIPT_NAME }"),
            declarationsOf(body, "managed"),
        )
        assertFalse(
            "the state must come from loadedFilterRules, not be assembled beside it",
            body.contains("FilterRulesState.Loaded("),
        )
    }

    /**
     * Every WHOLE line of [body] declaring `val [name]`, trimmed, comments dropped.
     *
     * The instrument of `MailBySenderWiringTest`: the line, never a substring of it. Returned as a
     * list so that a second declaration of the same name — the other half of a mutation — fails
     * the comparison instead of being hidden by the first.
     */
    private fun declarationsOf(body: String, name: String): List<String> =
        SourceText.codeLines(body).filter { it.substringBefore(" =") == "val $name" }

    /** The body of `MailRepository.loadFilterRules`, from its signature to the next declaration. */
    private fun loadFilterRulesBody(): String = SourceText.functionSource(
        SourceText.read("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt"),
        "suspend fun loadFilterRules(credentials: AccountCredentials)",
    )
}
