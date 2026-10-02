package app.sterna.core.data.filter

import app.sterna.core.data.filter.SieveScriptReadTest.Companion.GUARD
import app.sterna.core.data.filter.SieveScriptReadTest.Companion.fixture
import app.sterna.core.data.filter.SieveScriptReadTest.Companion.readable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Bulwark rule carrying `add_label` actions (#237): the form holds it, each label shown and written back
 * as Bulwark wrote it. `i237-label` is the script of the report, as Bulwark saved it.
 */
class BulwarkLabelTest {

    private val text = fixture("i237-label")
    private val subject = Condition(ConditionField.SUBJECT, RuleMatch.CONTAINS, "TESTSIEVE")
    private val reported = FilterRule(
        name = "Test",
        conditions = listOf(subject),
        actions = listOf(Action.MoveTo("Test"), Action.MarkRead, Action.Label("red")),
    )

    // Sterna writes `fileinto` without Bulwark's `:mailboxid`, its JSON keeping the id: outside #237's label.
    private fun rewritten(script: String) = script.replace("fileinto :mailboxid \"o\" \"Test\";", "fileinto \"Test\";")

    private fun entryOf(script: String) = BulwarkFormat.rules(
        requireNotNull(BulwarkFormat.read(script.substringBefore("@metadata:end */") + "@metadata:end */")),
    ).single()

    private fun withActions(actions: String, block: String) = text
        .replace(
            "\"actions\":[{\"type\":\"move\",\"value\":\"Test\",\"mailboxId\":\"o\"},{\"type\":\"mark_read\"},{\"type\":\"add_label\",\"value\":\"red\"}]",
            "\"actions\":[$actions]",
        )
        .replace("    addflag \"\\\\Seen\";\n    addflag \"\$label:red\";\n    fileinto :mailboxid \"o\" \"Test\";\n", block)

    @Test fun `the reported rule opens in the form as a Bulwark rule, its label among its actions`() {
        val script = readable(text)
        assertEquals(BulwarkMetadata.READABLE, script.bulwarkMetadata)
        val rule = script.rules.single()
        assertTrue("$rule", rule is ScriptRule.Form && rule.bulwark)
        assertEquals(reported, (rule as ScriptRule.Form).rule)
    }

    @Test fun `the reported rule saved unchanged writes back byte for byte`() {
        val script = readable(text)
        assertEquals(text, script.emit())
        val entry = entryOf(text)
        assertEquals(entry, BulwarkFormat.updated(entry, requireNotNull(BulwarkFormat.simpleRule(entry))))
        val rule = script.rules.single() as ScriptRule.Form
        assertEquals(text, script.update(rule.id, rule.rule).emit())
        assertEquals("a rename touches the name alone", text.replace("\"name\":\"Test\"", "\"name\":\"Renamed\"").replace("# Rule: Test", "# Rule: Renamed"),
            script.update(rule.id, rule.rule.copy(name = "Renamed")).emit())
    }

    @Test fun `a changed condition rewrites entry and block, the label kept in its place`() {
        val script = readable(text)
        val rule = script.rules.single() as ScriptRule.Form
        val out = script.update(rule.id, rule.rule.copy(conditions = listOf(subject.copy(value = "OTHER")))).emit()
        assertEquals(rewritten(text).replace("TESTSIEVE", "OTHER"), out)
        assertEquals(reported.copy(conditions = listOf(subject.copy(value = "OTHER"))), (readable(out).rules.single() as ScriptRule.Form).rule)
    }

    @Test fun `disabled then enabled again, the block comes back with its label`() {
        val script = readable(text)
        val rule = script.rules.single() as ScriptRule.Form
        val off = script.update(rule.id, rule.rule.copy(enabled = false))
        assertFalse(off.emit(), off.emit().contains("\$label:red\";"))
        assertTrue(off.emit(), off.emit().contains("{\"type\":\"add_label\",\"value\":\"red\"}"))
        assertEquals(rewritten(text), off.update(rule.id, rule.rule).emit())
    }

    @Test fun `a rule with a label alone, enabled again, requires imap4flags`() {
        val only = "/* @metadata:begin\n{\"version\":1,\"rules\":[{\"id\":\"l1\",\"name\":\"Only\",\"enabled\":false,\"matchType\":\"all\"," +
            "\"conditions\":[{\"field\":\"subject\",\"comparator\":\"contains\",\"value\":\"x\"}],\"actions\":[{\"type\":\"add_label\",\"value\":\"red\"}]," +
            "\"stopProcessing\":false}]}\n@metadata:end */\n\nrequire [\"fileinto\"];\n"
        val script = readable(only)
        val rule = script.rules.single() as ScriptRule.Form
        assertEquals(listOf<Action>(Action.Label("red")), rule.rule.actions)
        val on = script.update(rule.id, rule.rule.copy(enabled = true)).emit()
        assertTrue(on, on.contains("require [\"fileinto\", \"imap4flags\"];"))
        assertTrue(on, on.contains("# Rule: Only\nif header :contains \"Subject\" \"x\" {\n    addflag \"\$label:red\";\n}\n"))
    }

    @Test fun `a label beside an action the form does not hold stays locked`() {
        listOf(
            "{\"type\":\"discard\"},{\"type\":\"add_label\",\"value\":\"red\"}",
            "{\"type\":\"add_label\",\"value\":\"red\"},{\"type\":\"discard\"}",
            "{\"type\":\"add_label\",\"value\":\"red\"},{\"type\":\"forward\",\"value\":\"f@example.com\"}",
            "{\"type\":\"add_label\"}",
            "{\"type\":\"add_label\",\"value\":3}",
            "{\"type\":\"add_label\",\"value\":[\"red\"]}",
        ).forEach { actions ->
            val script = readable(withActions(actions, "    discard;\n"))
            assertEquals(actions, BulwarkMetadata.READABLE, script.bulwarkMetadata)
            val rule = script.rules.single()
            assertTrue(actions, rule is ScriptRule.Text && rule.lockedByBulwark)
        }
    }

    @Test fun `two labels are both kept, each in its place among the flags`() {
        val actions = "{\"type\":\"add_label\",\"value\":\"a\"},{\"type\":\"mark_read\"},{\"type\":\"add_label\",\"value\":\"b\",\"color\":\"#f00\"},{\"type\":\"move\",\"value\":\"Test\"}"
        val block = "    addflag \"\$label:a\";\n    addflag \"\\\\Seen\";\n    addflag \"\$label:b\";\n    fileinto \"Test\";\n"
        val two = withActions(actions, block)
        val script = readable(two)
        val rule = script.rules.single() as ScriptRule.Form
        assertEquals(listOf(Action.Label("a"), Action.MarkRead, Action.Label("b"), Action.MoveTo("Test")), rule.rule.actions)
        val out = script.update(rule.id, rule.rule.copy(conditions = listOf(subject.copy(value = "OTHER")))).emit()
        assertEquals(two.replace("TESTSIEVE", "OTHER"), out)
    }

    @Test fun `two labels of the same value are both kept, in the JSON and in the block`() {
        val label = "{\"type\":\"add_label\",\"value\":\"red\"}"
        val line = "addflag \"\$label:red\";"
        val twice = withActions("$label,$label,{\"type\":\"move\",\"value\":\"Test\"}", "    $line\n    $line\n    fileinto \"Test\";\n")
        val script = readable(twice)
        val rule = script.rules.single() as ScriptRule.Form
        assertEquals(listOf(Action.Label("red"), Action.Label("red"), Action.MoveTo("Test")), rule.rule.actions)
        val out = script.update(rule.id, rule.rule.copy(conditions = listOf(subject.copy(value = "OTHER")))).emit()
        assertEquals(out, 2, out.split(label).size - 1)
        assertEquals(out, 2, out.split(line).size - 1)
        assertEquals(twice.replace("TESTSIEVE", "OTHER"), out)
    }

    @Test fun `an action added in the form goes after the actions Bulwark wrote, the flags still before the move`() {
        val script = readable(text)
        val rule = script.rules.single() as ScriptRule.Form
        val starred = rule.rule.copy(actions = listOf(Action.Star) + rule.rule.actions)
        val out = script.update(rule.id, starred).emit()
        assertTrue(out, out.contains(
            "\"actions\":[{\"type\":\"move\",\"value\":\"Test\",\"mailboxId\":\"o\"},{\"type\":\"mark_read\"},{\"type\":\"add_label\",\"value\":\"red\"},{\"type\":\"star\"}]",
        ))
        assertTrue(out, out.contains("{\n    addflag \"\\\\Seen\";\n    addflag \"\$label:red\";\n    addflag \"\\\\Flagged\";\n    fileinto \"Test\";\n}"))
    }

    @Test fun `an action removed then added again in the form keeps its place in Bulwark's JSON`() {
        val script = readable(text)
        val rule = script.rules.single() as ScriptRule.Form
        val readded = rule.rule.copy(actions = listOf(Action.MoveTo("Test"), Action.Label("red"), Action.MarkRead))
        assertEquals(rewritten(text), script.update(rule.id, readded).emit())
    }

    @Test fun `a label value with a quote, a backslash and a star-slash is escaped in the block and in the JSON`() {
        val odd = withActions("{\"type\":\"add_label\",\"value\":\"a\\\"b\\\\c*\\/d\"}", "    addflag \"\$label:a\\\"b\\\\c*/d\";\n")
        val script = readable(odd)
        val rule = script.rules.single() as ScriptRule.Form
        assertEquals(listOf<Action>(Action.Label("a\"b\\c*/d")), rule.rule.actions)
        val out = script.update(rule.id, rule.rule.copy(conditions = listOf(subject.copy(value = "OTHER")))).emit()
        assertTrue(out, out.contains("{\"type\":\"add_label\",\"value\":\"a\\\"b\\\\c*\\/d\"}"))
        assertTrue(out, out.contains("if allof(header :contains \"Subject\" \"OTHER\", $GUARD) {\n    addflag \"\$label:a\\\"b\\\\c*/d\";\n}"))
        assertEquals(rule.rule.actions, (readable(out).rules.single() as ScriptRule.Form).rule.actions)
    }

    @Test fun `Bulwark's JSON holds any number of labels, still one of each other action`() {
        val base = FilterRule(name = "r", conditions = listOf(subject), actions = emptyList())
        assertTrue(BulwarkFormat.holds(base.copy(actions = listOf(Action.Label("a"), Action.Label("b"), Action.MarkRead, Action.Stop))))
        assertFalse(BulwarkFormat.holds(base.copy(actions = listOf(Action.Label("a"), Action.MarkRead, Action.MarkRead))))
        assertFalse(BulwarkFormat.holds(base.copy(actions = listOf(Action.Label("a"), Action.ForwardTo("f@example.com")))))
        assertFalse(BulwarkFormat.holds(base.copy(actions = listOf(Action.Label("a"), Action.Stop, Action.Label("b")))))
    }

    @Test fun `a label line in a script not from Bulwark stays text, editable`() {
        val plain = "require [\"imap4flags\"];\n\n# Tag\nif header :contains \"Subject\" \"x\" {\n    addflag \"\$label:red\";\n}\n"
        val rule = readable(plain).rules.single()
        assertTrue("$rule", rule is ScriptRule.Text && !rule.lockedByBulwark)
    }

    @Test fun `a label needs imap4flags`() {
        assertEquals(setOf("imap4flags"), capabilitiesOf(FilterRule(conditions = listOf(subject), actions = listOf(Action.Label("red")))))
    }
}
