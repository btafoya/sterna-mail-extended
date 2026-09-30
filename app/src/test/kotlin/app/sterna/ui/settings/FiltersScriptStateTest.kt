package app.sterna.ui.settings

import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.FilterScriptStatus
import app.sterna.core.data.filter.ListedScript
import app.sterna.core.data.filter.ScriptRule
import app.sterna.core.data.filter.ScriptSnapshot
import app.sterna.core.data.filter.listing
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

    private val listed = listOf(ListedScript("roundcube", false), ListedScript("sterna", true))

    @Test fun `the state lists every script and names the one opened, inactive beside the active one`() {
        val loaded = loadedFilterRules("roundcube", text).listing(listed, "roundcube", serverResponder = false)

        val state = FiltersUiState().withScript(loaded)

        assertEquals("roundcube", state.scriptName)
        assertEquals(listed, state.scripts)
        assertEquals(true, state.scriptNotActive)
        assertEquals(false, FiltersUiState().withScript(loadedFilterRules("sterna", text).listing(listed, null, false)).scriptNotActive)
    }

    @Test fun `a save over a chosen script writes it under its name and checks that script`() {
        val loaded = loadedFilterRules("roundcube", text).listing(listed, "roundcube", serverResponder = false)

        val write = filtersWrite(loaded, FiltersUiState().withScript(loaded))!!

        assertEquals("roundcube", write.name)
        assertEquals(ScriptSnapshot("roundcube", text, chosen = true), write.read)
    }

    @Test fun `over a script inactive beside the active one, the status says neither not running nor foreign`() {
        val state = FiltersUiState(loading = false).withScript(loadedFilterRules("roundcube", text).listing(listed, "roundcube", false))
        val status = FilterScriptStatus(scriptExists = true, scriptActive = false, enabledRuleCount = 2, foreignActive = true)

        val next = filtersStateWithStatus(state = state, status = status)

        assertEquals("Save leaves sterna running: the not-active line speaks instead", false, next.rulesNotRunning)
        assertEquals("the active script is not stopped by a save here", false, next.foreignActive)
        val sameOverOpenedDefault = filtersStateWithStatus(state = state.copy(scriptNotActive = false), status = status)
        assertEquals(true, sameOverOpenedDefault.rulesNotRunning)
        assertEquals(true, sameOverOpenedDefault.foreignActive)
    }

    @Test fun `picking a script opens it, asks first over unwritten edits, and does nothing on the one open`() {
        assertEquals(ScriptPick.OPEN, scriptPickStep(picked = "roundcube", opened = "sterna", dirty = false))
        assertEquals(ScriptPick.ASK, scriptPickStep(picked = "roundcube", opened = "sterna", dirty = true))
        assertEquals(ScriptPick.STAY, scriptPickStep(picked = "sterna", opened = "sterna", dirty = true))
        assertEquals(ScriptPick.STAY, scriptPickStep(picked = "sterna", opened = "sterna", dirty = false))
    }

    @Test fun `opening another script forgets every edit of the one left, keeping only the account`() {
        val loaded = loadedFilterRules("roundcube", text).listing(listed, "roundcube", serverResponder = false)
        val edited = FiltersUiState(loading = false, accountLabel = "alex@example.test", folders = listOf("Work"))
            .withScript(loaded)
            .let { it.withTextEdit(it.scriptRules.first().id, "keep;\n") }
            .copy(rules = listOf(FilterRule(name = "new", value = "x@example.com")), dirty = true)

        val leaving = edited.leavingScript()

        assertEquals(FiltersUiState(loading = true, accountLabel = "alex@example.test", folders = listOf("Work")), leaving)
        assertEquals(false, leaving.dirty)
        assertEquals(emptyMap<Int, String>(), leaving.textEdits)
        assertEquals(emptyList<FilterRule>(), leaving.rules)
        assertEquals(null, leaving.scriptName)
        assertEquals("nothing to edit while the next script loads", false, filtersAcceptEdits(leaving))
    }

    @Test fun `nothing is edited or saved while a read or an activation is under way`() {
        assertEquals(true, filtersAcceptEdits(FiltersUiState(loading = false)))
        assertEquals(false, filtersAcceptEdits(FiltersUiState(loading = true)))
        assertEquals(false, filtersAcceptEdits(FiltersUiState(loading = false, activating = true)))
        assertEquals(true, filtersAcceptEdits(FiltersUiState(loading = false, saving = true)))
    }
}
