package app.sterna.ui.settings

import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.ScriptRule
import app.sterna.core.data.filter.ScriptSnapshot
import app.sterna.core.data.filter.loadedFilterRules
import app.sterna.core.data.filter.withFormRules
import org.junit.Assert.assertEquals
import org.junit.Test

/** The filters state built from the opened script: the ids it pairs the listed rules with are the ones Save edits by. */
class FiltersScriptStateTest {

    private val text = "require [\"fileinto\"];\n\n# rule:[Spam]\nif header :contains \"subject\" \"[SPAM]\" {\n    fileinto \"Junk\";\n    stop;\n}\n\n" +
        "# rule:[boss]\nif address :is \"from\" \"boss@example.com\" {\n    fileinto \"Work\";\n}\n"

    @Test fun `the state carries the script's name, all its rules, and the form rules with their ids`() {
        val loaded = loadedFilterRules("roundcube", text)
        val state = FiltersUiState().withScript(loaded)

        assertEquals("roundcube", state.scriptName)
        assertEquals(loaded.scriptRules, state.scriptRules)
        assertEquals(listOf("Spam", "boss"), state.scriptRules.map { it.name })
        val boss = state.scriptRules.filterIsInstance<ScriptRule.Form>().single()
        assertEquals(listOf(boss.rule), state.rules)
        assertEquals(listOf<Int?>(boss.id), state.ruleIds)
    }

    @Test fun `saving the list untouched writes the script back byte for byte, and an edit reaches its own block`() {
        val loaded = loadedFilterRules("roundcube", text)
        val state = FiltersUiState().withScript(loaded)

        assertEquals(text, withFormRules(loaded.scriptToEdit, state.ruleIds, state.rules).emit())
        val edited = withFormRules(loaded.scriptToEdit, state.ruleIds, listOf(state.rules.single().copy(value = "chief@example.com"))).emit()
        assertEquals(text.replace("boss@example.com", "chief@example.com"), edited)
        val added = withFormRules(
            loaded.scriptToEdit,
            state.ruleIds + listOf(null),
            state.rules + FilterRule(name = "new", value = "x@example.com", moveTo = "Box"),
        )
        assertEquals(
            listOf("Spam", "boss", "new"),
            loadedFilterRules("roundcube", added.emit()).scriptRules.map { it.name },
        )
    }

    @Test fun `Save writes over the opened script, under its name, each rule by the id it was read as`() {
        val loaded = loadedFilterRules("roundcube", text)
        val state = FiltersUiState().withScript(loaded)

        val untouched = filtersWrite(loaded, state)!!
        assertEquals("roundcube", untouched.name)
        assertEquals(ScriptSnapshot("roundcube", text), untouched.read)
        assertEquals("rules paired with their ids rewrite nothing", text, untouched.script.emit())

        val edited = filtersWrite(loaded, state.copy(rules = listOf(state.rules.single().copy(value = "chief@example.com"))))!!
        assertEquals(text.replace("boss@example.com", "chief@example.com"), edited.script.emit())
    }
}
