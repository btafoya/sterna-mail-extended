package app.sterna.core.data.filter

import app.sterna.core.data.filter.SieveScriptReadTest.Companion.fixture
import app.sterna.core.data.filter.SieveScriptReadTest.Companion.readable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rules of several conditions and actions: what is read as a form rule, what is written, and what stays text. */
class RuleBuilderSieveTest {

    private fun cond(field: ConditionField, match: RuleMatch, value: String) = Condition(field, match, value)

    // ---- reading ----

    @Test fun `allof and anyof of form tests read as one rule of several conditions`() {
        val all = requireNotNull(
            recognizeForm("if allof(address :contains \"from\" \"a@\", header :contains \"subject\" \"b\") {\n    fileinto \"A\";\n}"),
        )
        assertEquals(RuleMode.ALL, all.rule.mode)
        assertEquals(
            listOf(cond(ConditionField.FROM, RuleMatch.CONTAINS, "a@"), cond(ConditionField.SUBJECT, RuleMatch.CONTAINS, "b")),
            all.rule.conditions,
        )
        assertEquals(listOf("address", "header"), all.words)
        assertNull(all.guard)

        val any = requireNotNull(
            recognizeForm(
                "if anyof(header :contains \"List-Id\" \"<l.example>\", not header :contains \"subject\" \"x\", " +
                    "body :text :contains \"y\", size :over 5M, size :under 100K) {\n}",
            ),
        )
        assertEquals(RuleMode.ANY, any.rule.mode)
        assertEquals(
            listOf(
                cond(ConditionField.LIST, RuleMatch.CONTAINS, "<l.example>"),
                cond(ConditionField.SUBJECT, RuleMatch.NOT_CONTAINS, "x"),
                cond(ConditionField.BODY, RuleMatch.CONTAINS, "y"),
                cond(ConditionField.SIZE, RuleMatch.OVER, "5242880"),
                cond(ConditionField.SIZE, RuleMatch.UNDER, "102400"),
            ),
            any.rule.conditions,
        )
    }

    @Test fun `every form action is read in the order the block runs it`() {
        val block = requireNotNull(
            recognizeForm(
                "if header :is \"subject\" \"x\" {\n    redirect :copy \"fwd@example.com\";\n    fileinto :copy \"Copies\";\n" +
                    "    addflag \"\\\\Flagged\";\n    fileinto \"A\";\n    addflag \"\\\\Seen\";\n    stop;\n}",
            ),
        )
        assertEquals(
            listOf(Action.ForwardTo("fwd@example.com"), Action.CopyTo("Copies"), Action.Star, Action.MoveTo("A"), Action.MarkRead, Action.Stop),
            block.rule.actions,
        )
    }

    @Test fun `an anyof with one test the form cannot show stays text, whole`() {
        val statement = "if anyof(header :contains \"subject\" \"x\", header :matches \"subject\" \"y*\") {\n    fileinto \"A\";\n}"
        assertNull("dropping the test would widen the rule", recognizeForm(statement))
        val text = "require [\"fileinto\"];\n\n# wide\n$statement\n"
        val rule = readable(text).rules.single()
        assertTrue(rule is ScriptRule.Text)
        assertEquals("# wide\n$statement", (rule as ScriptRule.Text).text)
    }

    @Test fun `in an allof, the tests after the first the form cannot show are its guard, in place`() {
        val guarded = requireNotNull(
            recognizeForm("if allof(header :contains \"subject\" \"a\", address :is \"to\" \"b\", exists \"x-spam\", header :contains \"subject\" \"c\") {\n}"),
        )
        assertEquals(2, guarded.rule.conditions.size)
        assertEquals("exists \"x-spam\", header :contains \"subject\" \"c\"", guarded.guard)
        assertNull("no form test first: the block is text", recognizeForm("if allof(exists \"x-spam\", header :contains \"subject\" \"c\") {\n}"))

        val anyGuarded = requireNotNull(
            recognizeForm("if allof(anyof(header :contains \"subject\" \"a\", header :contains \"subject\" \"b\"), exists \"x-spam\") {\n}"),
        )
        assertEquals(RuleMode.ANY, anyGuarded.rule.mode)
        assertEquals("exists \"x-spam\"", anyGuarded.guard)

        val offAny = requireNotNull(
            recognizeForm("if allof(false, anyof(header :contains \"subject\" \"a\", header :contains \"subject\" \"b\")) {\n}"),
        )
        assertFalse(offAny.rule.enabled)
        assertEquals(RuleMode.ANY, offAny.rule.mode)
        assertNull(offAny.guard)
    }

    @Test fun `a hand-written guard is shown as such on the rule`() {
        val text = "require [\"fileinto\"];\n\n# g\nif allof(header :contains \"subject\" \"a\", exists \"x-spam\") {\n    fileinto \"A\";\n}\n"
        val rule = readable(text).rules.single() as ScriptRule.Form
        assertTrue(rule.handCondition)
        assertFalse((readable(fixture("f148-nomarker")).rules.single() as ScriptRule.Form).handCondition)
    }

    // ---- writing ----

    @Test fun `a new rule writes its actions flags, copy, fileinto, redirect, stop, with the requires they need`() {
        val rule = FilterRule(
            name = "N",
            conditions = listOf(cond(ConditionField.FROM, RuleMatch.CONTAINS, "a@"), cond(ConditionField.SUBJECT, RuleMatch.IS, "b")),
            actions = listOf(Action.ForwardTo("x@example.com"), Action.Stop, Action.MoveTo("A"), Action.CopyTo("C"), Action.Star),
        )
        val text = readable("").add(rule).emit()
        assertEquals(
            "require [\"fileinto\", \"imap4flags\", \"copy\"];\n\n# N\n" +
                "if allof(address :contains \"from\" \"a@\", header :is \"subject\" \"b\") {\n" +
                "    addflag \"\\\\Flagged\";\n    fileinto :copy \"C\";\n    fileinto \"A\";\n    redirect :copy \"x@example.com\";\n    stop;\n}\n",
            text,
        )
        assertEquals(
            listOf(Action.Star, Action.CopyTo("C"), Action.MoveTo("A"), Action.ForwardTo("x@example.com"), Action.Stop),
            (readable(text).rules.single() as ScriptRule.Form).rule.actions,
        )
    }

    @Test fun `anyof and allof only from two conditions, a disabled any keeps its anyof, and each reads back`() {
        val two = listOf(cond(ConditionField.LIST, RuleMatch.CONTAINS, "l.example"), cond(ConditionField.BODY, RuleMatch.NOT_CONTAINS, "y"))
        val any = FilterRule(name = "Any", mode = RuleMode.ANY, conditions = two, actions = listOf(Action.MarkRead))
        val cases = mapOf(
            any to "if anyof(header :contains \"list-id\" \"l.example\", not body :text :contains \"y\") {",
            any.copy(enabled = false) to "if allof(false, anyof(header :contains \"list-id\" \"l.example\", not body :text :contains \"y\")) {",
            any.copy(mode = RuleMode.ALL) to "if allof(header :contains \"list-id\" \"l.example\", not body :text :contains \"y\") {",
            any.copy(conditions = two.take(1)) to "if header :contains \"list-id\" \"l.example\" {",
            any.copy(conditions = listOf(cond(ConditionField.SIZE, RuleMatch.OVER, "5242880"))) to "if size :over 5M {",
            any.copy(conditions = listOf(cond(ConditionField.FROM, RuleMatch.NOT_CONTAINS, "q"))) to "if not address :contains \"from\" \"q\" {",
        )
        for ((rule, line) in cases) {
            val text = readable("").add(rule).emit()
            assertTrue(text, text.contains("\n$line\n"))
            val back = (readable(text).rules.single() as ScriptRule.Form).rule
            val expected = if (rule.conditions.size < 2) rule.copy(mode = RuleMode.ALL) else rule
            assertEquals(line, expected, back)
        }
    }

    @Test fun `an active rule with one blank condition is written disabled`() {
        val rule = FilterRule(
            conditions = listOf(cond(ConditionField.FROM, RuleMatch.CONTAINS, "a@"), cond(ConditionField.SUBJECT, RuleMatch.CONTAINS, " ")),
            actions = listOf(Action.MarkRead),
        )
        assertTrue(readable("").add(rule).emit().contains("\nif allof(false, address :contains \"from\" \"a@\", header :contains \"subject\" \" \") {\n"))
    }

    @Test fun `a block read with a stop keeps it last, whatever the edit`() {
        val text = "require [\"fileinto\"];\n\n# S\nif header :contains \"subject\" \"x\" {\n    fileinto \"A\";\n    stop;\n}\n\n" +
            "# After\nif header :contains \"subject\" \"y\" {\n    fileinto \"B\";\n}\n"
        val script = readable(text)
        val s = script.rules[0] as ScriptRule.Form
        assertEquals(text.replace("\"x\"", "\"z\""), script.update(s.id, s.rule.copy(value = "z")).emit())
        assertEquals(
            "a rule handed over without its stop is still written with it",
            text.replace("fileinto \"A\"", "fileinto \"C\""),
            script.update(s.id, s.rule.copy(actions = listOf(Action.MoveTo("C")))).emit(),
        )
        assertEquals(
            "an action added after the stop goes before it",
            text.replace("require [\"fileinto\"]", "require [\"fileinto\", \"copy\"]")
                .replace("    fileinto \"A\";\n    stop;", "    fileinto \"A\";\n    redirect :copy \"f@example.com\";\n    stop;"),
            script.update(s.id, s.rule.copy(actions = s.rule.actions + Action.ForwardTo("f@example.com"))).emit(),
        )
    }

    @Test fun `a copy asks for copy and a body test for body, added to the require and never in its place`() {
        val rule = FilterRule(conditions = listOf(cond(ConditionField.BODY, RuleMatch.CONTAINS, "invoice")), actions = listOf(Action.CopyTo("Invoices")))
        assertTrue(readable("").add(rule).emit().startsWith("require [\"fileinto\", \"body\", \"copy\"];\n"))
        val roundcube = readable(fixture("roundcube"))
        val boss = roundcube.rules[1] as ScriptRule.Form
        val edited = roundcube.update(boss.id, boss.rule.copy(actions = boss.rule.actions + Action.CopyTo("Boss"))).emit()
        assertTrue(edited, edited.startsWith("require [\"fileinto\",\"imap4flags\",\"vacation\", \"copy\"];\n"))
    }

    @Test fun `a Delete files into the Trash and never discards`() {
        val delete = FilterRule(name = "D", value = "spam@example.com", moveTo = "INBOX.Trash")
        val text = readable("").add(delete).emit()
        assertTrue(text, text.contains("    fileinto \"INBOX.Trash\";\n"))
        assertFalse(text, "discard" in text)
        assertEquals(listOf(Action.MarkRead, Action.MoveTo("INBOX.Trash")), blockRule("spam@example.com", "INBOX.Trash").actions)
    }

    @Test fun `a rule into the Trash of several conditions is left out of the auto-reply whole`() {
        val away = SieveVacation(isEnabled = true, subject = "Away", textBody = "Back soon", trashFolder = "Trash")
        val rule = FilterRule(
            name = "T",
            conditions = listOf(cond(ConditionField.FROM, RuleMatch.IS, "a@example.com"), cond(ConditionField.SUBJECT, RuleMatch.CONTAINS, "promo")),
            actions = listOf(Action.MoveTo("Trash")),
        )
        val text = readable("").add(rule).withResponder(away).emit()
        assertTrue(text, text.contains("\nif not anyof (allof(address :is \"from\" \"a@example.com\", header :contains \"subject\" \"promo\")) {\n"))
    }

    // ---- a condition keeps what was read with it, not what was read at its place ----

    private val threeWords = "require [\"fileinto\"];\n\n# w\n" +
        "if allof(header :contains \"subject\" \"x\", address :is \"from\" \"a@b.c\", header :contains \"to\" \"list@x\") {\n" +
        "    fileinto \"A\";\n}\n"

    @Test fun `removing or reordering conditions keeps each one's address or header`() {
        val script = readable(threeWords)
        val w = script.rules.single() as ScriptRule.Form
        val c = w.rule.conditions
        assertEquals(
            "the first removed",
            threeWords.replace("header :contains \"subject\" \"x\", ", ""),
            script.update(w.id, w.rule.copy(conditions = listOf(c[1], c[2]))).emit(),
        )
        assertEquals(
            "the middle one removed",
            threeWords.replace("address :is \"from\" \"a@b.c\", ", ""),
            script.update(w.id, w.rule.copy(conditions = listOf(c[0], c[2]))).emit(),
        )
        assertEquals(
            "reordered",
            threeWords.replace(
                "allof(header :contains \"subject\" \"x\", address :is \"from\" \"a@b.c\", header :contains \"to\" \"list@x\")",
                "allof(header :contains \"to\" \"list@x\", header :contains \"subject\" \"x\", address :is \"from\" \"a@b.c\")",
            ),
            script.update(w.id, w.rule.copy(conditions = listOf(c[2], c[0], c[1]))).emit(),
        )
        val two = "# t\nif allof(header :contains \"subject\" \"x\", address :is \"from\" \"a@b.c\") {\n}\n"
        val twoScript = readable(two)
        val t = twoScript.rules.single() as ScriptRule.Form
        assertEquals(
            "# t\nif address :is \"from\" \"a@b.c\" {\n}\n",
            twoScript.update(t.id, t.rule.copy(conditions = t.rule.conditions.drop(1))).emit(),
        )
    }

    @Test fun `in Bulwark's JSON, what a condition carries follows it when another is removed or they are reordered`() {
        val read = "\"matchType\":\"all\",\"conditions\":[{\"field\":\"subject\",\"comparator\":\"is\",\"value\":\"URGENT\",\"note\":\"s\"}," +
            "{\"field\":\"from\",\"comparator\":\"contains\",\"value\":\"boss@\",\"note\":\"f\"}]"
        val script = readable(fixture("f237-bulwark").replace(bossEntry, bossEntry.replace(
            "\"matchType\":\"all\",\"conditions\":[{\"field\":\"subject\",\"comparator\":\"is\",\"value\":\"URGENT\"}]", read,
        )))
        assertEquals(BulwarkMetadata.READABLE, script.bulwarkMetadata)
        val boss = script.rules[1] as ScriptRule.Form
        val c = boss.rule.conditions
        val subject = "{\"field\":\"subject\",\"comparator\":\"is\",\"value\":\"URGENT\",\"note\":\"s\"}"
        val from = "{\"field\":\"from\",\"comparator\":\"contains\",\"value\":\"boss@\",\"note\":\"f\"}"
        val removed = script.update(boss.id, boss.rule.copy(conditions = listOf(c[1]))).emit()
        assertTrue(removed, removed.contains("\"conditions\":[$from]"))
        val swapped = script.update(boss.id, boss.rule.copy(conditions = listOf(c[1], c[0]))).emit()
        assertTrue(swapped, swapped.contains("\"conditions\":[$from,$subject]"))
    }

    // ---- forward ----

    @Test fun `a new forward keeps the message and a copy is asked for`() {
        val rule = FilterRule(name = "F", conditions = listOf(cond(ConditionField.FROM, RuleMatch.CONTAINS, "a@")), actions = listOf(Action.ForwardTo("fwd@example.com")))
        assertEquals(
            "require [\"copy\"];\n\n# F\nif address :contains \"from\" \"a@\" {\n    redirect :copy \"fwd@example.com\";\n}\n",
            readable("").add(rule).emit(),
        )
    }

    @Test fun `a forward read with or without copy is rewritten in the form it had`() {
        val text = "require [\"copy\"];\n\n# a\nif header :contains \"subject\" \"x\" {\n    redirect \"plain@example.com\";\n}\n\n" +
            "# b\nif header :contains \"subject\" \"y\" {\n    redirect :copy \"kept@example.com\";\n}\n"
        val script = readable(text)
        val (a, b) = script.rules.map { it as ScriptRule.Form }
        assertEquals(text.replace("\"x\"", "\"x2\""), script.update(a.id, a.rule.copy(value = "x2")).emit())
        assertEquals(text.replace("\"y\"", "\"y2\""), script.update(b.id, b.rule.copy(value = "y2")).emit())
    }

    // ---- the auto-reply leaves out the Trash rules as they are written ----

    @Test fun `the auto-reply leaves out a Trash rule by the test its block runs, words and guard included`() {
        val away = SieveVacation(isEnabled = true, subject = "Away", textBody = "Back soon", trashFolder = "Trash")
        val text = "require [\"fileinto\"];\n\n# g\nif allof(address :is \"from\" \"a@b.c\", exists \"x-spam\") {\n    fileinto \"Trash\";\n}\n\n" +
            "# h\nif header :is \"from\" \"Boss <b@c.d>\" {\n    fileinto \"Trash\";\n}\n"
        val out = readable(text).withResponder(away).emit()
        assertTrue(out, out.contains("\nif not anyof (allof(address :is \"from\" \"a@b.c\", exists \"x-spam\"), header :is \"from\" \"Boss <b@c.d>\") {\n"))
    }

    // ---- sizes ----

    @Test fun `a size too large for its quantifier is text, never an overflowed number`() {
        assertEquals(999_999_999L * 1024 * 1024 * 1024, sizeBytes("999999999G"))
        assertNull(sizeBytes("999999999999G"))
        assertEquals(999_999_999_999L * 1024 * 1024, sizeBytes("999999999999M"))
        assertNull(sizeBytes("9999999999999M"))
        assertNull(recognizeForm("if size :over 999999999999G {\n}"))
    }

    // ---- what reads unchanged is not rewritten ----

    @Test fun `every form rule of the fixtures, saved unchanged, leaves its script byte for byte`() {
        for (name in listOf("roundcube", "roundcube-crlf", "nextcloud", "text-literal", "f148-nomarker", "f148-roundcube", "f237-bulwark")) {
            val text = fixture(name)
            val script = readable(text)
            for (rule in script.rules.filterIsInstance<ScriptRule.Form>()) {
                assertEquals("$name: ${rule.name}", text, script.update(rule.id, rule.rule).emit())
            }
        }
    }

    // ---- Bulwark ----

    private val bossEntry = "\"matchType\":\"all\",\"conditions\":[{\"field\":\"subject\",\"comparator\":\"is\",\"value\":\"URGENT\"}]," +
        "\"actions\":[{\"type\":\"star\"},{\"type\":\"mark_read\"}],\"stopProcessing\":false"

    @Test fun `a Bulwark rule of several conditions and a stop is a form rule, its edit rewrites entry and block together`() {
        val anyBoss = "\"matchType\":\"any\",\"conditions\":[{\"field\":\"subject\",\"comparator\":\"is\",\"value\":\"URGENT\"}," +
            "{\"field\":\"from\",\"comparator\":\"contains\",\"value\":\"boss@\"}],\"actions\":[{\"type\":\"star\"},{\"type\":\"mark_read\"}],\"stopProcessing\":true"
        val text = fixture("f237-bulwark").replace(bossEntry, anyBoss)
        val script = readable(text)
        assertEquals(BulwarkMetadata.READABLE, script.bulwarkMetadata)
        val boss = script.rules[1] as ScriptRule.Form
        assertEquals(
            FilterRule(
                name = "Boss",
                mode = RuleMode.ANY,
                conditions = listOf(cond(ConditionField.SUBJECT, RuleMatch.IS, "URGENT"), cond(ConditionField.FROM, RuleMatch.CONTAINS, "boss@")),
                actions = listOf(Action.Star, Action.MarkRead, Action.Stop),
            ),
            boss.rule,
        )
        val edited = boss.rule.copy(conditions = listOf(boss.rule.conditions[0], cond(ConditionField.FROM, RuleMatch.CONTAINS, "chief@")))
        val out = script.update(boss.id, edited).emit()
        assertTrue(out, out.contains(anyBoss.replace("boss@", "chief@")))
        assertTrue(
            out,
            out.contains(
                "# Rule: Boss\nif anyof(header :is \"Subject\" \"URGENT\", header :contains \"From\" \"chief@\") {\n" +
                    "    addflag \"\\\\Flagged\";\n    addflag \"\\\\Seen\";\n    stop;\n}\n",
            ),
        )
        val reread = readable(out)
        assertEquals(BulwarkMetadata.READABLE, reread.bulwarkMetadata)
        assertEquals(edited, (reread.rules[1] as ScriptRule.Form).rule)
    }

    @Test fun `what Bulwark's JSON cannot hold is refused, never written as a word Bulwark would read otherwise`() {
        val script = readable(fixture("f237-bulwark"))
        val newsletters = script.rules[0] as ScriptRule.Form
        listOf(
            newsletters.rule.copy(actions = newsletters.rule.actions + Action.ForwardTo("f@example.com")),
            newsletters.rule.copy(actions = newsletters.rule.actions + Action.CopyTo("C")),
            newsletters.rule.copy(conditions = listOf(cond(ConditionField.LIST, RuleMatch.CONTAINS, "l"))),
            newsletters.rule.copy(conditions = listOf(cond(ConditionField.FROM, RuleMatch.NOT_CONTAINS, "x"))),
            newsletters.rule.copy(conditions = listOf(cond(ConditionField.SIZE, RuleMatch.UNDER, "1024"))),
        ).forEach { rule ->
            assertFalse("$rule", BulwarkFormat.holds(rule))
            assertThrows(IllegalArgumentException::class.java) { script.update(newsletters.id, rule) }
            assertThrows(IllegalArgumentException::class.java) { script.add(rule) { "sterna-t" } }
        }
    }

    @Test fun `a Bulwark entry on size and body reads as a rule and writes back as it was`() {
        val entry = Json.parseToJsonElement(
            "{\"id\":\"b\",\"name\":\"Big\",\"enabled\":true,\"matchType\":\"any\",\"conditions\":[" +
                "{\"field\":\"size\",\"comparator\":\"greater_than\",\"value\":\"5M\"},{\"field\":\"body\",\"comparator\":\"contains\",\"value\":\"invoice\"}]," +
                "\"actions\":[{\"type\":\"move\",\"value\":\"Big\",\"mailboxId\":\"m\"}],\"stopProcessing\":false}",
        ).jsonObject
        val rule = requireNotNull(BulwarkFormat.simpleRule(entry))
        assertEquals(
            listOf(cond(ConditionField.SIZE, RuleMatch.OVER, "5242880"), cond(ConditionField.BODY, RuleMatch.CONTAINS, "invoice")),
            rule.conditions,
        )
        assertEquals(entry, BulwarkFormat.updated(entry, rule))
        assertEquals(
            "if anyof(size :over 5M, body :contains \"invoice\") {\n    fileinto \"Big\";\n}",
            BulwarkFormat.statement(entry, null, "", "\n"),
        )
        assertNull("5000000 bytes is no whole KB: the form could not show it", BulwarkFormat.simpleRule(JsonObject(entry + ("conditions" to Json.parseToJsonElement("[{\"field\":\"size\",\"comparator\":\"greater_than\",\"value\":\"5000000\"}]")))))
    }
}
