package app.sterna.ui.settings

import app.sterna.core.data.filter.Action
import app.sterna.core.data.filter.Condition
import app.sterna.core.data.filter.ConditionField
import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.RuleMatch
import app.sterna.core.data.filter.RuleMode
import app.sterna.core.data.filter.ScriptRule
import app.sterna.core.data.filter.loadedFilterRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule editor's decisions, executed: what "+ Add action" offers, what a line holds, what Save accepts. */
class FilterRuleFormTest {

    private val rule = FilterRule(name = "r", value = "a@example.com")

    @Test fun `a server that announced nothing is offered everything`() {
        ActionKind.entries.forEach { assertEquals("$it", Availability.OFFERED, actionAvailability(it, rule, offered = null, bulwark = false)) }
        ConditionField.entries.forEach { assertEquals("$it", Availability.OFFERED, fieldAvailability(it, offered = null, bulwark = false)) }
    }

    @Test fun `what the server did not announce is greyed, what base Sieve has never is`() {
        val announced = listOf("fileinto", "imap4flags")
        assertEquals(Availability.NOT_BY_SERVER, actionAvailability(ActionKind.COPY, rule, announced, bulwark = false))
        assertEquals(Availability.OFFERED, actionAvailability(ActionKind.MOVE, rule, announced, bulwark = false))
        assertEquals("a forward keeps a copy: it needs copy", Availability.NOT_BY_SERVER, actionAvailability(ActionKind.FORWARD, rule, announced, bulwark = false))
        assertEquals(Availability.OFFERED, actionAvailability(ActionKind.FORWARD, rule, announced + "copy", bulwark = false))
        assertEquals(
            "an empty list at the gate offers nothing it needs; the read turns an empty announce into null",
            Availability.NOT_BY_SERVER,
            fieldAvailability(ConditionField.BODY, emptyList(), bulwark = false),
        )
        assertEquals("stop is base Sieve", Availability.OFFERED, actionAvailability(ActionKind.STOP, rule, emptyList(), bulwark = false))
        assertEquals(Availability.NOT_BY_SERVER, actionAvailability(ActionKind.STAR, rule, listOf("fileinto"), bulwark = false))
        assertEquals(Availability.NOT_BY_SERVER, actionAvailability(ActionKind.DELETE, rule, listOf("imap4flags"), bulwark = false))
        assertEquals(Availability.NOT_BY_SERVER, fieldAvailability(ConditionField.BODY, announced, bulwark = false))
        assertEquals("size is base Sieve", Availability.OFFERED, fieldAvailability(ConditionField.SIZE, emptyList(), bulwark = false))
        assertEquals("names compare case-blind", Availability.OFFERED, fieldAvailability(ConditionField.BODY, listOf("BODY"), bulwark = false))
    }

    @Test fun `a rule in Bulwark's JSON is offered only what the JSON can hold`() {
        assertEquals(Availability.NOT_IN_BULWARK, actionAvailability(ActionKind.COPY, rule, null, bulwark = true))
        assertEquals(Availability.NOT_IN_BULWARK, actionAvailability(ActionKind.FORWARD, rule, null, bulwark = true))
        assertEquals(Availability.OFFERED, actionAvailability(ActionKind.STOP, rule, null, bulwark = true))
        assertEquals(Availability.NOT_IN_BULWARK, fieldAvailability(ConditionField.LIST, null, bulwark = true))
        assertEquals(listOf(RuleMatch.CONTAINS, RuleMatch.IS), offeredMatches(ConditionField.FROM, bulwark = true))
        assertEquals(listOf(RuleMatch.CONTAINS, RuleMatch.IS, RuleMatch.NOT_CONTAINS), offeredMatches(ConditionField.FROM, bulwark = false))
        assertEquals(listOf(RuleMatch.OVER), offeredMatches(ConditionField.SIZE, bulwark = true))
    }

    @Test fun `a rule moves once, a Delete being its move, and takes each flag and a stop once`() {
        val deleting = rule.copy(actions = listOf(Action.MoveTo("Trash"), Action.MarkRead, Action.Stop))
        listOf(ActionKind.MOVE, ActionKind.DELETE, ActionKind.MARK_READ, ActionKind.STOP).forEach {
            assertEquals("$it", Availability.ALREADY_THERE, actionAvailability(it, deleting, null, bulwark = false))
        }
        listOf(ActionKind.COPY, ActionKind.FORWARD, ActionKind.STAR).forEach {
            assertEquals("$it", Availability.OFFERED, actionAvailability(it, deleting, null, bulwark = false))
        }
    }

    @Test fun `Delete is a move into the account's Trash, shown as Delete, never a discard`() {
        assertEquals(Action.MoveTo("INBOX.Trash"), newAction(ActionKind.DELETE, listOf("INBOX.Work"), "INBOX.Trash"))
        assertEquals(ActionKind.DELETE, kindOf(Action.MoveTo("INBOX.Trash"), "INBOX.Trash"))
        assertEquals(ActionKind.MOVE, kindOf(Action.MoveTo("INBOX.Work"), "INBOX.Trash"))
        assertEquals("with no Trash known, a move is a move", ActionKind.MOVE, kindOf(Action.MoveTo("INBOX.Trash"), null))
        assertEquals("a new move does not start on the Trash", Action.MoveTo("Work"), newAction(ActionKind.MOVE, listOf("Trash", "Work"), "Trash"))
    }

    @Test fun `an action added after a stop goes before it`() {
        val stopped = rule.copy(actions = listOf(Action.MoveTo("A"), Action.Stop))
        assertEquals(listOf(Action.MoveTo("A"), Action.Star, Action.Stop), withAction(stopped, Action.Star).actions)
        assertEquals(listOf(Action.Star, Action.Stop), withAction(rule.copy(actions = listOf(Action.Star)), Action.Stop).actions)
    }

    @Test fun `a stop the rule was read with has no remove button, one added in the editor has`() {
        val read = rule.copy(actions = listOf(Action.MoveTo("A"), Action.Stop))
        assertFalse(actionRemovable(Action.Stop, original = read))
        assertTrue(actionRemovable(Action.Stop, original = rule))
        assertTrue(actionRemovable(Action.Stop, original = null))
        assertTrue(actionRemovable(Action.MoveTo("A"), original = read))
    }

    @Test fun `removing a condition down to one drops the any`() {
        val any = rule.copy(mode = RuleMode.ANY, conditions = listOf(Condition(value = "a"), Condition(value = "b"), Condition(value = "c")))
        assertEquals(RuleMode.ANY, withoutCondition(any, 0).mode)
        assertEquals(listOf(Condition(value = "c")), withoutCondition(withoutCondition(any, 0), 0).conditions)
        assertEquals(RuleMode.ALL, withoutCondition(withoutCondition(any, 0), 0).mode)
    }

    @Test fun `a field change keeps what still means something there`() {
        val from = Condition(ConditionField.FROM, RuleMatch.IS, "a@example.com")
        assertEquals(Condition(ConditionField.SUBJECT, RuleMatch.IS, "a@example.com"), withField(from, ConditionField.SUBJECT, bulwark = false))
        assertEquals(Condition(ConditionField.BODY, RuleMatch.CONTAINS, "a@example.com"), withField(from, ConditionField.BODY, bulwark = false))
        assertEquals(Condition(ConditionField.SIZE, RuleMatch.OVER, ""), withField(from, ConditionField.SIZE, bulwark = false))
        assertEquals(Condition(ConditionField.FROM, RuleMatch.CONTAINS, ""), withField(Condition(ConditionField.SIZE, RuleMatch.OVER, "1024"), ConditionField.FROM, bulwark = false))
    }

    @Test fun `a size is typed in KB or MB and held in bytes`() {
        assertEquals("5242880", sizeValue("5", SizeUnit.MB))
        assertEquals("102400", sizeValue("100", SizeUnit.KB))
        assertEquals("", sizeValue("", SizeUnit.KB))
        assertEquals("", sizeValue("1.5", SizeUnit.MB))
        assertEquals(SizeUnit.MB, sizeUnitOf("5242880"))
        assertEquals(SizeUnit.KB, sizeUnitOf("102400"))
        assertEquals(SizeUnit.KB, sizeUnitOf(""))
        assertEquals("5", sizeInUnit("5242880", SizeUnit.MB))
        assertEquals("5120", sizeInUnit("5242880", SizeUnit.KB))
        assertEquals("", sizeInUnit("102400", SizeUnit.MB))
    }

    @Test fun `Save waits for every line to be writable`() {
        val forward = rule.copy(actions = listOf(Action.ForwardTo("not-an-address")))
        assertFalse(ruleComplete(forward))
        assertFalse("the editor's Save stays off", ruleSaveEnabled(original = null, rule = forward, busy = false, present = true))
        assertTrue(ruleComplete(rule.copy(actions = listOf(Action.ForwardTo("fwd@example.com")))))
        assertFalse("no size typed", ruleComplete(rule.copy(conditions = listOf(Condition(ConditionField.SIZE, RuleMatch.OVER, "")))))
        assertTrue(ruleComplete(rule.copy(conditions = listOf(Condition(ConditionField.SIZE, RuleMatch.OVER, "1024")))))
        assertFalse("no condition, no test", ruleComplete(rule.copy(conditions = emptyList())))
        assertFalse("a move with no folder", ruleComplete(rule.copy(actions = listOf(Action.MoveTo("")))))
    }

    @Test fun `a rule of every kind survives the bundle`() {
        val every = FilterRule(
            name = "every",
            enabled = false,
            mode = RuleMode.ANY,
            conditions = ConditionField.entries.map { Condition(it, RuleMatch.entries[it.ordinal % RuleMatch.entries.size], "v${it.ordinal}") },
            actions = listOf(Action.MoveTo("A"), Action.CopyTo("B"), Action.MarkRead, Action.Star, Action.ForwardTo("f@example.com"), Action.Stop),
        )
        assertEquals(every, restoredRule(savedRule(every)))
        val plainForward = loadedFilterRules("s", "if header :contains \"subject\" \"x\" {\n    redirect \"p@example.com\";\n}\n").rules.single()
        assertEquals("a forward read without copy stays so", plainForward, restoredRule(savedRule(plainForward)))
    }

    @Test fun `the state knows what the server offers and whether a new rule goes into Bulwark's JSON`() {
        val loaded = loadedFilterRules("bulwark", BULWARK_ONE).copy(sieveExtensions = listOf("fileinto"))
        val state = FiltersUiState(loading = false).withScript(loaded)
        assertEquals(listOf("fileinto"), state.offered)
        assertTrue(state.bulwark)
        assertTrue(ruleInBulwark(state, RuleTarget(original = null, start = FilterRule())))
        val one = (state.scriptRules.single() as ScriptRule.Form).rule
        assertTrue(ruleInBulwark(state, RuleTarget(original = one, start = one)))
        assertFalse(FiltersUiState(loading = false).withScript(loadedFilterRules("sterna", "keep;\n")).bulwark)
    }

    private companion object {
        val BULWARK_ONE = "/* @metadata:begin\n" +
            "{\"version\":1,\"rules\":[{\"id\":\"b1\",\"name\":\"One\",\"enabled\":true,\"matchType\":\"all\"," +
            "\"conditions\":[{\"field\":\"from\",\"comparator\":\"contains\",\"value\":\"a@\"}]," +
            "\"actions\":[{\"type\":\"star\"}],\"stopProcessing\":false}]}\n@metadata:end */\n\n" +
            "require [\"imap4flags\"];\n\n# Rule: One\nif header :contains \"From\" \"a@\" {\n    addflag \"\\\\Flagged\";\n}\n"
    }
}
