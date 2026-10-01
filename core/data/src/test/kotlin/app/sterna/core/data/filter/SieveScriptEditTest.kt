package app.sterna.core.data.filter

import app.sterna.core.data.filter.SieveScriptReadTest.Companion.GUARD
import app.sterna.core.data.filter.SieveScriptReadTest.Companion.fixture
import app.sterna.core.data.filter.SieveScriptReadTest.Companion.readable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Editing a script: only the segments an edit names change, plus the `require` line when it must. */
class SieveScriptEditTest {

    // ---- a form rule of another client ----

    @Test fun `editing a rule rewrites its block alone, with the test word it had`() {
        val text = fixture("roundcube")
        val script = readable(text)
        val boss = script.rules[1] as ScriptRule.Form
        assertEquals(
            text.replace(
                "if address :is \"from\" \"boss@example.com\"\n{\n\taddflag \"\\\\Flagged\";\n}",
                "if address :is \"from\" \"chief@example.com\" {\n    addflag \"\\\\Flagged\";\n}",
            ),
            script.update(boss.id, boss.rule.copy(value = "chief@example.com")).emit(),
        )
    }

    @Test fun `renaming a rule touches its comment alone, in the form it had`() {
        val roundcube = fixture("roundcube")
        val boss = readable(roundcube).rules[1] as ScriptRule.Form
        assertEquals(
            roundcube.replace("# rule:[boss]", "# rule:[chief]"),
            readable(roundcube).update(boss.id, boss.rule.copy(name = "chief")).emit(),
        )
        val nomarker = fixture("f148-nomarker")
        val rule = readable(nomarker).rules.single() as ScriptRule.Form
        assertEquals(
            nomarker.replace("# ecrit a la main, sans marqueur Sterna", "# Two words"),
            readable(nomarker).update(rule.id, rule.rule.copy(name = " Two \t words ")).emit(),
        )
    }

    @Test fun `a header test stays header, an address test moved to the subject becomes header`() {
        val nomarker = fixture("f148-nomarker")
        val rule = readable(nomarker).rules.single() as ScriptRule.Form
        assertEquals(
            nomarker.replace("header :contains \"subject\" \"banc\"", "header :contains \"from\" \"banc\""),
            readable(nomarker).update(rule.id, rule.rule.copy(field = RuleField.FROM)).emit(),
        )
        val roundcube = fixture("roundcube")
        val boss = readable(roundcube).rules[1] as ScriptRule.Form
        assertTrue(
            readable(roundcube).update(boss.id, boss.rule.copy(field = RuleField.SUBJECT)).emit()
                .contains("# rule:[boss]\nif header :is \"subject\" \"boss@example.com\" {\n"),
        )
    }

    @Test fun `the guard of allof(T, G) goes back in place, and a disabled rule is allof(false, T, G)`() {
        val text = "require [\"fileinto\", \"spamtestplus\", \"relational\", \"comparator-i;ascii-numeric\"];\n\n" +
            "# Mine\nif allof(address :contains \"from\" \"x\", $GUARD) {\n    fileinto \"A\";\n}\n"
        val script = readable(text)
        val mine = script.rules.single() as ScriptRule.Form
        assertEquals(
            text.replace("fileinto \"A\"", "fileinto \"B\""),
            script.update(mine.id, mine.rule.copy(moveTo = "B")).emit(),
        )
        val disabled = script.update(mine.id, mine.rule.copy(enabled = false)).emit()
        assertEquals(text.replace("if allof(address", "if allof(false, address"), disabled)
        val reread = readable(disabled)
        val off = reread.rules.single() as ScriptRule.Form
        assertEquals(mine.rule.copy(enabled = false), off.rule)
        assertEquals(text, reread.update(off.id, off.rule.copy(enabled = true)).emit())
    }

    @Test fun `a stop block is a form rule, editing another rule keeps it whole, and editing it keeps its stop last`() {
        val text = fixture("f237-bulwark")
        val script = readable(text)
        val two = script.rules[5] as ScriptRule.Form
        val one = script.rules[4] as ScriptRule.Form
        val block = "# rule:[Roundcube two]\nif header :contains \"subject\" \"rc2\" {\n    fileinto \"Archive\";\n    stop;\n}\n"
        assertTrue(script.update(one.id, one.rule.copy(moveTo = "Kept")).emit().endsWith(block))
        assertEquals(
            text.replace(block, block.replace("\"rc2\"", "\"rc3\"").replace("    fileinto", "    addflag \"\\\\Seen\";\n    fileinto")),
            script.update(two.id, two.rule.copy(value = "rc3", markRead = true)).emit(),
        )
    }

    // ---- new rules, removal, raw text ----

    @Test fun `a new rule is written as V1 wrote it, under its name, with the require it needs`() {
        val rule = FilterRule(name = "N", value = "x", moveTo = "Y")
        assertEquals(
            "require [\"fileinto\"];\n\n# N\nif address :contains \"from\" \"x\" {\n    fileinto \"Y\";\n}\n",
            readable("").add(rule).emit(),
        )
        val everything = FilterRule(value = "x", moveTo = "Y", markRead = true, flag = true)
        listOf(everything, everything.copy(field = RuleField.SUBJECT, match = RuleMatch.IS)).forEach {
            assertTrue(SieveCodec.generate(listOf(it)).contains("\n" + formStatement(it, listOf(defaultWord(it.field.condition)), null, "", "\n") + "\n"))
        }
    }

    @Test fun `a new disabled rule is allof(false, T) and reads back disabled`() {
        val off = FilterRule(name = "Off", enabled = false, value = "x", markRead = true)
        val text = readable("").add(off).emit()
        assertEquals(
            "require [\"imap4flags\"];\n\n# Off\nif allof(false, address :contains \"from\" \"x\") {\n    addflag \"\\\\Seen\";\n}\n",
            text,
        )
        assertEquals(listOf(off), readable(text).rules.map { (it as ScriptRule.Form).rule })
    }

    @Test fun `the capabilities a new block needs are added to the first require, never in its place`() {
        val rule = FilterRule(name = "N", value = "x", moveTo = "Y")
        val block = "\n\n# N\nif address :contains \"from\" \"x\" {\n    fileinto \"Y\";\n}\n"
        assertEquals(
            "require [\"vacation\", \"fileinto\"];\n\nvacation \"away\";$block",
            readable("require [\"vacation\"];\n\nvacation \"away\";\n").add(rule).emit(),
        )
        assertEquals(
            "require [\"vacation\", \"fileinto\"];\n\nvacation \"away\";$block",
            readable("require \"vacation\";\n\nvacation \"away\";\n").add(rule).emit(),
        )
        assertEquals("require [\"a\", \"b\", \"c\"];", requireWith("require [\"a\"];", listOf("b", "c")))
        assertEquals("require [\"b\"];", requireWith("require [];", listOf("b")))
        assertEquals("require\n  [ \"a\" , \"b\"] ;", requireWith("require\n  [ \"a\" ] ;", listOf("b")))
    }

    @Test fun `a script with no require gets one before its first command`() {
        assertEquals(
            "require [\"fileinto\"];\n\n# my filters\nif true { keep; }\n\n# N\nif address :contains \"from\" \"x\" {\n    fileinto \"Y\";\n}\n",
            readable("# my filters\nif true { keep; }\n").add(FilterRule(name = "N", value = "x", moveTo = "Y")).emit(),
        )
    }

    @Test fun `a capability already required is not added again`() {
        val text = fixture("roundcube")
        val added = readable(text).add(FilterRule(value = "x", moveTo = "Y", flag = true)).emit()
        assertTrue(added.startsWith(text))
    }

    @Test fun `a new block in a CRLF script is CRLF, and nothing else changes`() {
        val text = fixture("roundcube-crlf")
        val added = readable(text).add(FilterRule(name = "New", value = "x", moveTo = "Y")).emit()
        assertEquals(text + "\r\n# New\r\nif address :contains \"from\" \"x\" {\r\n    fileinto \"Y\";\r\n}\r\n", added)
        assertFalse(added.replace("\r\n", "").contains('\n'))
    }

    @Test fun `removing a rule leaves no blank line behind and keeps the require`() {
        val nomarker = readable(fixture("f148-nomarker"))
        assertEquals("require [\"fileinto\"];\n", nomarker.remove(nomarker.rules.single().id).emit())
    }

    @Test fun `removing the block under a Nextcloud marker keeps the marker`() {
        val text = fixture("nextcloud")
        val script = readable(text)
        assertEquals(
            text.replace("# mine, below the region\nif header :contains \"subject\" \"mine\" {\n    fileinto \"Mine\";\n}\n", ""),
            script.remove(script.rules[1].id).emit(),
        )
    }

    @Test fun `a text block is replaced by its new text, and text that cannot be cut is refused`() {
        val text = fixture("roundcube")
        val script = readable(text)
        val away = script.rules[3] as ScriptRule.Text
        val replacement = "# rule:[away]\nif header :contains \"subject\" \"x\" {\n    keep;\n}"
        assertEquals(text.replace(away.text, replacement), script.replaceText(away.id, replacement)?.emit())
        assertNull(script.replaceText(away.id, "if true {"))
    }

    // ---- V1: migrated at the first rewrite ----

    @Test fun `the first rewrite of a V1 script drops the marker, writes the names and the disabled rules as Sieve`() {
        val a = FilterRule(name = "A", value = "a", moveTo = "Box")
        val b = FilterRule(name = "B", enabled = false, field = RuleField.SUBJECT, match = RuleMatch.IS, value = "b", flag = true)
        val c = FilterRule(name = "C", field = RuleField.TO, value = "c", markRead = true)
        val script = readable(SieveCodec.generate(listOf(a, b, c)))
        val head = "require [\"fileinto\", \"imap4flags\"];\n\n" +
            "# A\nif address :contains \"from\" \"a\" {\n    fileinto \"Box\";\n}\n"
        val tail = "# C2\nif address :contains \"to\" \"c\" {\n    addflag \"\\\\Seen\";\n}\n"
        val renamed = script.update(script.rules[2].id, c.copy(name = "C2")).emit()
        assertEquals(head + "# B\nif allof(false, header :is \"subject\" \"b\") {\n    addflag \"\\\\Flagged\";\n}\n" + tail, renamed)
        assertEquals(listOf(a, b, c.copy(name = "C2")), readable(renamed).rules.map { (it as ScriptRule.Form).rule })
        val enabled = script.update(script.rules[1].id, b.copy(enabled = true)).emit()
        assertEquals(head + "# B\nif header :is \"subject\" \"b\" {\n    addflag \"\\\\Flagged\";\n}\n" + tail.replace("C2", "C"), enabled)
    }

    @Test fun `every V1 rules marker goes at the first rewrite`() {
        val script = readable(fixture("f148-deux-marqueurs"))
        val rewritten = script.update(script.rules[0].id, (script.rules[0] as ScriptRule.Form).rule.copy(name = "X")).emit()
        assertFalse(rewritten, rewritten.contains(SieveCodec.MARKER))
    }

    // ---- the auto-reply ----

    @Test fun `the auto-reply marker moves above its block at the first rewrite, and the auto-reply is rewritten alone`() {
        val away = SieveVacation(isEnabled = true, subject = "Away", textBody = "Back soon")
        val script = readable(SieveCodec.generate(listOf(FilterRule(name = "A", value = "a", moveTo = "Box")), away))
        assertEquals(1, script.rules.size)
        val rules = "require [\"fileinto\", \"vacation\"];\n\n# A2\nif address :contains \"from\" \"a\" {\n    fileinto \"Box\";\n}\n"
        val renamed = script.update(script.rules[0].id, FilterRule(name = "A2", value = "a", moveTo = "Box"))
        assertEquals(rules + SieveCodec.vacationMarkerLine(away) + "\nvacation :subject \"Away\" \"Back soon\";\n", renamed.emit())
        assertEquals(VacationMarker.Found(away), renamed.responder)

        val gone = away.copy(subject = "Gone")
        assertEquals(
            rules + SieveCodec.vacationMarkerLine(gone) + "\nvacation :subject \"Gone\" \"Back soon\";\n",
            renamed.withResponder(gone).emit(),
        )
        assertEquals(rules, renamed.withResponder(null).emit())
    }

    @Test fun `a new auto-reply goes last under its marker, with its requires`() {
        val away = SieveVacation(isEnabled = true, fromDate = "2026-10-01T00:00:00Z", subject = "Away", textBody = "Back soon")
        val text = "require [\"fileinto\"];\n\nif header :is \"subject\" \"x\" {\n    fileinto \"A\";\n}\n"
        assertEquals(
            text.replace("[\"fileinto\"]", "[\"fileinto\", \"vacation\", \"date\", \"relational\"]") + "\n" +
                SieveCodec.vacationMarkerLine(away) + "\n" + SieveCodec.vacationToSieve(away, emptyList()) + "\n",
            readable(text).withResponder(away).emit(),
        )
    }

    @Test fun `a filters edit moves the auto-reply's exclusion with the rule into the Trash, and a rename leaves it verbatim`() {
        val away = SieveVacation(isEnabled = true, subject = "Away", textBody = "Back soon", trashFolder = "Trash")
        val script = readable(SieveCodec.generate(listOf(FilterRule(name = "T", value = "t", moveTo = "Trash")), away))
        val edited = script.update(script.rules[0].id, FilterRule(name = "T", value = "changed", moveTo = "Trash"))
        val block = "if not anyof (address :contains \"from\" \"changed\") {\n    vacation :subject \"Away\" \"Back soon\";\n}"
        assertTrue(edited.emit(), edited.emit().endsWith(SieveCodec.vacationMarkerLine(away) + "\n" + block + "\n"))

        val renamed = edited.update(edited.rules[0].id, FilterRule(name = "T2", value = "changed", moveTo = "Trash")).emit()
        assertEquals(edited.emit().replace("# T\n", "# T2\n"), renamed)
    }

    // ---- Bulwark: the JSON entry and its block move together ----

    @Test fun `editing a Bulwark rule rewrites its entry and its block, the guard kept`() {
        val text = fixture("f237-bulwark")
        val script = readable(text)
        val newsletters = script.rules[0] as ScriptRule.Form
        assertEquals(
            text.replace("\"news@\"", "\"letters@\""),
            script.update(newsletters.id, newsletters.rule.copy(value = "letters@")).emit(),
        )
    }

    @Test fun `disabling a Bulwark rule drops its block, enabling it puts it back where it was`() {
        val text = fixture("f237-bulwark")
        val script = readable(text)
        val boss = script.rules[1] as ScriptRule.Form
        val off = script.update(boss.id, boss.rule.copy(enabled = false))
        assertEquals(
            text.replace("\n# Rule: Boss\nif header :is \"Subject\" \"URGENT\" {\n    addflag \"\\\\Flagged\";\n    addflag \"\\\\Seen\";\n}\n", "")
                .replace("\"name\":\"Boss\",\"enabled\":true", "\"name\":\"Boss\",\"enabled\":false"),
            off.emit(),
        )
        assertEquals(text, off.update(boss.id, boss.rule).emit())
    }

    @Test fun `renaming a Bulwark rule renames its entry and its Rule line, blanks flattened`() {
        val script = readable(fixture("f237-bulwark"))
        val boss = script.rules[1] as ScriptRule.Form
        val renamed = script.update(boss.id, boss.rule.copy(name = "Chief  Boss")).emit()
        assertTrue(renamed.contains("\"name\":\"Chief Boss\""))
        assertFalse(renamed.contains("Chief  Boss"))
        assertTrue(renamed.contains("\n# Rule: Chief Boss\nif header :is \"Subject\" \"URGENT\" {"))
        assertEquals(BulwarkMetadata.READABLE, readable(renamed).bulwarkMetadata)
    }

    @Test fun `a new rule in a Bulwark script is a Bulwark entry, its block after the last Rule block`() {
        val text = fixture("f237-bulwark")
        val added = readable(text).add(FilterRule(name = "New one", value = "x@", moveTo = "Box")) { "sterna-t" }.emit()
        val entry = "{\"id\":\"sterna-t\",\"name\":\"New one\",\"enabled\":true,\"matchType\":\"all\"," +
            "\"conditions\":[{\"field\":\"from\",\"comparator\":\"contains\",\"value\":\"x@\"}]," +
            "\"actions\":[{\"type\":\"move\",\"value\":\"Box\"}],\"stopProcessing\":false}"
        assertEquals(
            text.replace("}]}\n@metadata:end", "},$entry]}\n@metadata:end")
                .replace("    discard;\n}\n", "    discard;\n}\n\n# Rule: New one\nif allof(header :contains \"From\" \"x@\", $GUARD) {\n    fileinto \"Box\";\n}\n"),
            added,
        )
        val reread = readable(added)
        assertEquals(BulwarkMetadata.READABLE, reread.bulwarkMetadata)
        assertEquals(FilterRule(name = "New one", value = "x@", moveTo = "Box"), (reread.rules[4] as ScriptRule.Form).rule)
    }

    @Test fun `a star-slash in a Bulwark value is escaped in the JSON and reads back`() {
        val added = readable(fixture("f237-bulwark")).add(FilterRule(name = "odd", value = "a*/b")) { "sterna-t" }.emit()
        assertTrue(added.contains("\"value\":\"a*\\/b\""))
        val reread = readable(added)
        assertEquals(BulwarkMetadata.READABLE, reread.bulwarkMetadata)
        assertEquals("a*/b", (reread.rules[4] as ScriptRule.Form).rule.value)
    }

    @Test fun `a locked Bulwark rule cannot be edited, and deleting it drops its entry and its block`() {
        val script = readable(fixture("f237-bulwark"))
        val big = script.rules[2]
        assertThrows(IllegalArgumentException::class.java) { script.update(big.id, FilterRule(value = "x")) }
        val removed = script.remove(big.id).emit()
        assertFalse(removed.contains("Big or invoice"))
        assertFalse(removed.contains("anyof(size"))
        val reread = readable(removed)
        assertEquals(BulwarkMetadata.READABLE, reread.bulwarkMetadata)
        assertEquals(listOf("Newsletters", "Boss", "Sleeping", "Roundcube one", "Roundcube two"), reread.rules.map { it.name })
    }

    @Test fun `with an unreadable Bulwark JSON, a renamed rule is never written under Rule`() {
        val script = readable(fixture("f237-bulwark").replace("{\"version\":1,", "{\"version\":2,"))
        val newsletters = script.rules[0] as ScriptRule.Form
        val renamed = script.update(newsletters.id, newsletters.rule.copy(name = "Renamed")).emit()
        assertTrue(renamed.contains("\n# Renamed\nif allof(header :contains \"From\" \"news@\", $GUARD) {"))
        assertFalse(renamed.contains("# Rule: Renamed"))
    }
    // ---- after the independent review ----

    @Test fun `a rule that files before it flags keeps that order when edited`() {
        val text = "require [\"fileinto\", \"imap4flags\"];\n\nif header :contains \"subject\" \"x\" {\n    fileinto \"Invoices\";\n    addflag \"\\\\Seen\";\n}\n"
        val script = readable(text)
        val rule = script.rules.single() as ScriptRule.Form
        assertEquals(text.replace("\"x\"", "\"y\""), script.update(rule.id, rule.rule.copy(value = "y")).emit())
        assertEquals(
            text.replace("    fileinto", "    addflag \"\\\\Flagged\";\n    fileinto"),
            script.update(rule.id, rule.rule.copy(flag = true)).emit(),
        )
    }

    @Test fun `an active rule with a blank value is never written as a live test`() {
        val text = readable("").add(FilterRule(value = "", moveTo = "Archive")).emit()
        assertEquals("require [\"fileinto\"];\n\nif allof(false, address :contains \"from\" \"\") {\n    fileinto \"Archive\";\n}\n", text)
        assertEquals(listOf(FilterRule(enabled = false, value = "", moveTo = "Archive")), readable(text).rules.map { (it as ScriptRule.Form).rule })
        val nomarker = readable(fixture("f148-nomarker"))
        val rule = nomarker.rules.single() as ScriptRule.Form
        assertTrue(nomarker.update(rule.id, rule.rule.copy(value = " ")).emit().contains("if allof(false, header :contains \"subject\" \" \") {"))
        val bulwark = readable(fixture("f237-bulwark")).add(FilterRule(name = "Blank", value = "", moveTo = "Box")) { "sterna-t" }.emit()
        assertFalse(bulwark.contains("# Rule: Blank"))
        val reread = readable(bulwark)
        assertEquals(BulwarkMetadata.READABLE, reread.bulwarkMetadata)
        assertFalse((reread.rules[4] as ScriptRule.Form).rule.enabled)
    }

    @Test fun `a Bulwark block Sterna writes again carries Bulwark's spam guard`() {
        val text = fixture("f237-bulwark")
        val script = readable(text)
        val newsletters = script.rules[0] as ScriptRule.Form
        val off = script.update(newsletters.id, newsletters.rule.copy(enabled = false))
        assertEquals(text, off.update(newsletters.id, newsletters.rule).emit())

        val spamToo = readable(text.replace("\"type\":\"move\",\"value\":\"Archive\"}],\"stopProcessing\":false}", "\"type\":\"move\",\"value\":\"Archive\"}],\"stopProcessing\":false,\"includeSpam\":true}"))
        val back = spamToo.update(newsletters.id, newsletters.rule.copy(enabled = false)).update(newsletters.id, newsletters.rule).emit()
        assertTrue(back, back.contains("# Rule: Newsletters\nif header :contains \"From\" \"news@\" {\n    fileinto \"Archive\";\n}\n"))

        val noSpamtest = readable(text.replace(", \"relational\", \"spamtestplus\"]", "]"))
        val added = noSpamtest.add(FilterRule(name = "New", value = "x@", moveTo = "Box")) { "sterna-t" }.emit()
        assertTrue(added, added.contains("# Rule: New\nif header :contains \"From\" \"x@\" {\n"))
    }

    @Test fun `a V1 marker that no longer matches keeps its disabled rules and still goes at the first rewrite`() {
        val a = FilterRule(name = "A", value = "a", moveTo = "Box")
        val b = FilterRule(name = "B", enabled = false, value = "b", moveTo = "Box")
        val script = readable(SieveCodec.generate(listOf(a, b)).replace("\"a\" {", "\"changed\" {"))
        assertEquals(listOf(a.copy(name = "", value = "changed"), b), script.rules.map { (it as ScriptRule.Form).rule })
        val first = script.rules[0] as ScriptRule.Form
        assertEquals(
            "require [\"fileinto\"];\n\n# X\nif address :contains \"from\" \"changed\" {\n    fileinto \"Box\";\n}\n" +
                "# B\nif allof(false, address :contains \"from\" \"b\") {\n    fileinto \"Box\";\n}\n",
            script.update(first.id, first.rule.copy(name = "X")).emit(),
        )
    }

    @Test fun `a V1 disabled rule that files adds fileinto to the require at the first rewrite`() {
        val a = FilterRule(name = "A", value = "a", markRead = true)
        val b = FilterRule(name = "B", enabled = false, value = "b", moveTo = "Box")
        val script = readable(SieveCodec.generate(listOf(a, b)))
        val out = script.update(script.rules[0].id, a.copy(name = "A2")).emit()
        assertTrue(out, out.startsWith("require [\"imap4flags\", \"fileinto\"];\n"))
    }

    @Test fun `a Bulwark move keeps its mailboxId while the folder stays, and loses it when the folder changes`() {
        val text = fixture("f237-bulwark")
            .replace("{\"type\":\"move\",\"value\":\"Archive\"}", "{\"type\":\"move\",\"value\":\"Archive\",\"mailboxId\":\"m1\"}")
        val script = readable(text)
        val newsletters = script.rules[0] as ScriptRule.Form
        assertTrue(script.update(newsletters.id, newsletters.rule.copy(value = "x@")).emit()
            .contains("{\"type\":\"move\",\"value\":\"Archive\",\"mailboxId\":\"m1\"}"))
        val moved = script.update(newsletters.id, newsletters.rule.copy(moveTo = "Other")).emit()
        assertTrue(moved.contains("{\"type\":\"move\",\"value\":\"Other\"}"))
        assertFalse(moved.contains("m1"))
    }

    @Test fun `a new rule goes after a closing Nextcloud marker, never inside the region`() {
        val marker = "### Nextcloud Mail: Filters ### DON'T EDIT ###\n"
        val text = marker + "require [\"fileinto\"];\n" + marker + "# Hello\n" + marker +
            "# Invoices\nif header :contains \"subject\" \"invoice\" {\nfileinto \"Invoices\";\n}\n" + marker
        assertEquals(
            text + "\n# N\nif address :contains \"from\" \"x\" {\n    fileinto \"Y\";\n}\n",
            readable(text).add(FilterRule(name = "N", value = "x", moveTo = "Y")).emit(),
        )
    }

    @Test fun `the text of a block includes the rest of its last line, and replacing it replaces that too`() {
        val script = readable("keep;  # stay\nstop;\n")
        val keep = script.rules[0] as ScriptRule.Text
        assertEquals("keep;  # stay", keep.text)
        assertEquals("discard;  # stay\nstop;\n", script.replaceText(keep.id, keep.text.replace("keep", "discard"))?.emit())
        assertEquals("discard;\nstop;\n", script.replaceText(keep.id, "discard;")?.emit())
    }
}
