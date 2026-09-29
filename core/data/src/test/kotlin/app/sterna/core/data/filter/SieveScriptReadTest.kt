package app.sterna.core.data.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a script: the splitter, the round trip, what becomes a form rule and what stays text. The
 * `f148-*` and `f237-*` fixtures are the texts `fixture_148banc.py` and `fixture_237.py` post on the banc.
 */
class SieveScriptReadTest {

    // ---- round trip: parse then emit, untouched, gives back the same bytes ----

    @Test fun `the V1 script of the banc comes back byte for byte`() = assertRoundTrip("f148-good")

    @Test fun `a hand-written script comes back byte for byte`() = assertRoundTrip("f148-nomarker")

    @Test fun `a script with an unreadable V1 marker comes back byte for byte`() = assertRoundTrip("f148-broken")

    @Test fun `the banc's one-rule Roundcube script comes back byte for byte`() = assertRoundTrip("f148-roundcube")

    @Test fun `a V1 script with a hand-written block after it comes back byte for byte`() = assertRoundTrip("f148-hybride-apres")

    @Test fun `a V1 script with a require before its marker comes back byte for byte`() = assertRoundTrip("f148-hybride-avant")

    @Test fun `a CRLF V1 script comes back byte for byte`() = assertRoundTrip("f148-hybride-crlf")

    @Test fun `an empty V1 marker beside a hand-written block comes back byte for byte`() = assertRoundTrip("f148-vide-hybride")

    @Test fun `a script with two V1 markers comes back byte for byte`() = assertRoundTrip("f148-deux-marqueurs")

    @Test fun `the Bulwark script of the banc comes back byte for byte`() = assertRoundTrip("f237-bulwark")

    @Test fun `a Roundcube script with tabs and a text literal comes back byte for byte`() = assertRoundTrip("roundcube")

    @Test fun `a CRLF Roundcube script comes back byte for byte`() = assertRoundTrip("roundcube-crlf")

    @Test fun `a Nextcloud script with its region markers comes back byte for byte`() = assertRoundTrip("nextcloud")

    @Test fun `a text literal, a block comment and no final newline come back byte for byte`() = assertRoundTrip("text-literal")

    @Test fun `an empty script comes back empty`() = assertEquals("", readable("").emit())

    @Test fun `a V1 script with an auto-reply comes back byte for byte`() {
        val text = SieveCodec.generate(
            listOf(FilterRule(name = "A", value = "a", moveTo = "Box"), FilterRule(name = "Off", enabled = false, value = "b", flag = true)),
            SieveVacation(isEnabled = true, fromDate = "2026-10-01T00:00:00Z", subject = "Away", textBody = "Back soon", trashFolder = "Box"),
        )
        assertEquals(text, readable(text).emit())
    }

    // ---- the one unreadable case: something is opened and never closed ----

    @Test fun `a block comment never closed is unreadable`() =
        assertUnreadable("keep;\n/* never closed\n", "/*", 6)

    @Test fun `a string never closed is unreadable`() =
        assertUnreadable("if header :is \"subject\" \"never closed {\n  keep;\n}\n", "\"", 24)

    @Test fun `a brace never closed is unreadable`() =
        assertUnreadable("require \"fileinto\";\nif true {\n  keep;\n", "{", 28)

    @Test fun `a parenthesis never closed is unreadable`() =
        assertUnreadable("if anyof(true, false {\n  keep;\n}\n", "(", 8)

    @Test fun `a text literal with no closing dot is unreadable`() =
        assertUnreadable("vacation text:\nno dot here\n", "text:", 9)

    @Test fun `what can be cut but not understood is text, never unreadable`() {
        val script = readable("frobnicate :x \"y\";\nif foo { bar; } elsif baz { } else { stop; }\nkeep\n")
        assertEquals(
            listOf("frobnicate :x \"y\";", "if foo { bar; } elsif baz { } else { stop; }", "keep\n"),
            script.rules.map { (it as ScriptRule.Text).text },
        )
    }

    // ---- what the form can edit ----

    @Test fun `address and header tests on the four fields are form rules, with the word they were written with`() {
        assertEquals(
            FormBlock(FilterRule(field = RuleField.CC, match = RuleMatch.IS, value = "x", moveTo = "A", markRead = true), "address", null),
            recognizeForm("if address :is \"CC\" \"x\" {\n    addflag \"\\\\Seen\";\n    fileinto \"A\";\n}"),
        )
        assertEquals(
            FormBlock(FilterRule(field = RuleField.FROM, value = "x", flag = true), "header", null),
            recognizeForm("if header :contains \"From\" \"x\" { addflag \"\\\\Flagged\"; }"),
        )
    }

    @Test fun `a body with stop is text, not a form rule`() {
        assertEquals(null, recognizeForm("if header :contains \"subject\" \"x\" {\n    fileinto \"A\";\n    stop;\n}"))
        assertEquals(null, recognizeForm("if header :contains \"subject\" \"x\" {\n    stop;\n}"))
    }

    @Test fun `anything else in the body is text`() {
        listOf(
            "fileinto :copy \"A\";",
            "fileinto \"A\"; fileinto \"B\";",
            "addflag \"\\\\Seen\"; addflag \"\\\\Seen\";",
            "addflag \"\$label1\";",
            "keep;",
            "# a comment\n    fileinto \"A\";",
        ).forEach { body -> assertEquals(body, null, recognizeForm("if header :is \"subject\" \"x\" { $body }")) }
    }

    @Test fun `a test the form cannot show is text`() {
        listOf(
            "address :is \"subject\" \"x\"",
            "header :contains \"X-Spam\" \"x\"",
            "header :matches \"subject\" \"x*\"",
            "header :contains \"subject\" [\"x\", \"y\"]",
            "anyof(header :is \"subject\" \"x\", true)",
            "allof(header :is \"subject\" \"x\")",
            "not header :is \"subject\" \"x\"",
        ).forEach { test -> assertEquals(test, null, recognizeForm("if $test { keep; }".replace("keep;", ""))) }
        assertEquals(null, recognizeForm("if header :is \"subject\" \"x\" { } else { }"))
    }

    @Test fun `the guard of allof(T, G) is kept verbatim, and allof(false, T) is a disabled rule`() {
        val guard = "not spamtest :percent :value \"ge\" :comparator \"i;ascii-numeric\" \"50\""
        assertEquals(
            FormBlock(FilterRule(value = "news@", moveTo = "Archive"), "header", guard),
            recognizeForm("if allof(header :contains \"From\" \"news@\", $guard) {\n    fileinto \"Archive\";\n}"),
        )
        assertEquals(
            FormBlock(FilterRule(enabled = false, value = "x"), "address", null),
            recognizeForm("if allof(false, address :contains \"from\" \"x\") {\n}"),
        )
        assertEquals(
            FormBlock(FilterRule(enabled = false, value = "x"), "address", "true, true"),
            recognizeForm("if allof (false, address :contains \"from\" \"x\", true, true) {\n}"),
        )
    }

    // ---- names ----

    @Test fun `a name is the last comment line glued above the block`() {
        val script = readable(
            "# detached, a blank line below\n\nif true { keep; }\n" +
                "# first\n# second\nif true { stop; }\n" +
                "# rule:[Roundcube name]\nif true { stop; }\n" +
                "# Rule: Bulwark name\nif true { stop; }\n" +
                "/*\n# inside a block comment\n*/\nif true { stop; }\n",
        )
        assertEquals(listOf("", "second", "Roundcube name", "Bulwark name", ""), script.rules.map { it.name })
    }

    @Test fun `the fixtures read as the rules their clients show`() {
        assertEquals(
            listOf(
                ScriptRule.Text(1, "Spam", "# rule:[Spam]\nif header :contains \"subject\" \"[SPAM]\"\n{\n\tfileinto \"Junk\";\n\tstop;\n}", false),
                ScriptRule.Form(2, FilterRule(name = "boss", match = RuleMatch.IS, value = "boss@example.com", flag = true)),
            ),
            readable(fixture("roundcube")).rules.take(2),
        )
        assertEquals(
            listOf("either" to false, "away" to false),
            readable(fixture("roundcube")).rules.drop(2).map { it.name to (it is ScriptRule.Form) },
        )
        val nomarker = readable(fixture("f148-nomarker")).rules.single() as ScriptRule.Form
        assertEquals(
            FilterRule(name = "ecrit a la main, sans marqueur Sterna", field = RuleField.SUBJECT, value = "banc", moveTo = "Archive"),
            nomarker.rule,
        )
    }

    @Test fun `blocks inside a Nextcloud region are text, the ones below it are rules`() {
        val rules = readable(fixture("nextcloud")).rules
        assertEquals(listOf("Invoices" to false, "mine, below the region" to true), rules.map { it.name to (it is ScriptRule.Form) })
    }

    @Test fun `a text literal is delimited by its lone dot, whatever it holds`() {
        val rules = readable(fixture("text-literal")).rules
        assertEquals(4, rules.size)
        assertTrue((rules[0] as ScriptRule.Text).text.startsWith("vacation :subject \"Out\" text:"))
        assertEquals(
            FilterRule(field = RuleField.SUBJECT, match = RuleMatch.IS, value = "a \"quoted\" \\ value", moveTo = "Odd"),
            (rules[1] as ScriptRule.Form).rule,
        )
        assertEquals(listOf("keep;", "stop;"), rules.drop(2).map { (it as ScriptRule.Text).text })
    }

    // ---- V1: names and disabled rules come from the marker ----

    @Test fun `a V1 script takes its names and disabled rules from the marker, in the marker's order`() {
        val a = FilterRule(name = "A", value = "a", moveTo = "Box")
        val b = FilterRule(name = "B", enabled = false, field = RuleField.SUBJECT, match = RuleMatch.IS, value = "b", flag = true)
        val c = FilterRule(name = "C", field = RuleField.TO, value = "c", markRead = true)
        assertEquals(listOf(a, b, c), readable(SieveCodec.generate(listOf(a, b, c))).rules.map { (it as ScriptRule.Form).rule })
        assertEquals(listOf(b, a, c), readable(SieveCodec.generate(listOf(b, a, c))).rules.map { (it as ScriptRule.Form).rule })
    }

    @Test fun `an enabled V1 rule with a blank value comes back disabled`() {
        val blank = FilterRule(name = "Blank", value = " ", moveTo = "Box")
        assertEquals(listOf(blank.copy(enabled = false)), readable(SieveCodec.generate(listOf(blank))).rules.map { (it as ScriptRule.Form).rule })
    }

    @Test fun `a V1 marker that no longer matches its blocks names nothing`() {
        val text = SieveCodec.generate(listOf(FilterRule(name = "A", value = "a", moveTo = "Box")))
            .replace("\"a\" {", "\"changed\" {")
        assertEquals(listOf(""), readable(text).rules.map { it.name })
    }

    // ---- Bulwark ----

    @Test fun `Bulwark rules come from its JSON, the simple ones as form rules, the others locked`() {
        val script = readable(fixture("f237-bulwark"))
        assertEquals(BulwarkMetadata.READABLE, script.bulwarkMetadata)
        val rules = script.rules
        assertEquals(
            listOf(
                FilterRule(name = "Newsletters", value = "news@", moveTo = "Archive"),
                FilterRule(name = "Boss", field = RuleField.SUBJECT, match = RuleMatch.IS, value = "URGENT", markRead = true, flag = true),
            ),
            rules.take(2).map { (it as ScriptRule.Form).rule },
        )
        assertEquals(
            listOf("Big or invoice" to true, "Sleeping" to true),
            rules.subList(2, 4).map { it.name to (it as ScriptRule.Text).lockedByBulwark },
        )
        assertEquals("", (rules[3] as ScriptRule.Text).text)
        assertEquals(FilterRule(name = "Roundcube one", field = RuleField.SUBJECT, value = "roundcube", moveTo = "Archive"), (rules[4] as ScriptRule.Form).rule)
        assertEquals("Roundcube two" to false, rules[5].name to (rules[5] as ScriptRule.Text).lockedByBulwark)
        assertEquals(6, rules.size)
    }

    @Test fun `Bulwark JSON whose blocks come in another order is unreadable`() {
        val swapped = fixture("f237-bulwark")
            .replace("# Rule: Newsletters", "# Rule: TMP").replace("# Rule: Boss", "# Rule: Newsletters").replace("# Rule: TMP", "# Rule: Boss")
        assertEquals(BulwarkMetadata.UNREADABLE, readable(swapped).bulwarkMetadata)
    }

    @Test fun `Bulwark JSON whose block carries another name is unreadable`() {
        val renamed = fixture("f237-bulwark").replace("# Rule: Boss", "# Rule: Chief")
        assertEquals(BulwarkMetadata.UNREADABLE, readable(renamed).bulwarkMetadata)
    }

    @Test fun `Bulwark JSON with a block missing is unreadable`() {
        val missing = fixture("f237-bulwark").replace("# Rule: Boss\n", "# Boss\n")
        assertEquals(BulwarkMetadata.UNREADABLE, readable(missing).bulwarkMetadata)
    }

    @Test fun `Bulwark JSON of another version or form is unreadable, and the script reads without it`() {
        val v2 = readable(fixture("f237-bulwark").replace("{\"version\":1,", "{\"version\":2,"))
        assertEquals(BulwarkMetadata.UNREADABLE, v2.bulwarkMetadata)
        assertEquals(listOf("Newsletters", "Boss", "Big or invoice", "Roundcube one", "Roundcube two"), v2.rules.map { it.name })
        assertEquals(FilterRule(name = "Newsletters", value = "news@", moveTo = "Archive"), (v2.rules[0] as ScriptRule.Form).rule)
        val noId = fixture("f237-bulwark").replace("\"id\":\"b237-3\",", "")
        assertEquals(BulwarkMetadata.UNREADABLE, readable(noId).bulwarkMetadata)
        assertEquals(BulwarkMetadata.ABSENT, readable(fixture("roundcube")).bulwarkMetadata)
    }

    // ---- unescape: the same cases escape is tested with ----

    @Test fun `unescape undoes escape`() {
        listOf("休假中", "我下周一回来。", "a\tb", "a\\nb", "a\nb\tc", "a\"b\\c", "\\\\\"\"").forEach { value ->
            assertEquals(value, unescape(SieveCodec.escape(value)))
        }
        assertEquals("a\"b", unescape("a\\\"b"))
        assertEquals("a\\b", unescape("a\\\\b"))
        assertEquals("a\\nb", unescape("a\\\\nb"))
        assertEquals("anb", unescape("a\\nb"))
        assertFalse(unescape("tab\there").contains("\\"))
    }

    private fun assertRoundTrip(name: String) {
        val text = fixture(name)
        assertEquals(name, text, readable(text).emit())
    }

    private fun assertUnreadable(text: String, opener: String, offset: Int) =
        assertEquals(SieveParse.Unreadable(opener, offset), SieveScript.parse(text))

    companion object {
        const val GUARD = "not spamtest :percent :value \"ge\" :comparator \"i;ascii-numeric\" \"50\""

        fun fixture(name: String): String =
            requireNotNull(SieveScriptReadTest::class.java.getResourceAsStream("/sieve/$name.sieve")) { "no fixture $name" }
                .use { it.readBytes().toString(Charsets.UTF_8) }

        fun readable(text: String): SieveScript {
            val parsed = SieveScript.parse(text)
            check(parsed is SieveParse.Readable) { "unreadable: $parsed" }
            return parsed.script
        }
    }
}
