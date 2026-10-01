package app.sterna.ui.settings

import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.copy
import app.sterna.core.data.filter.FilterScriptStatus
import app.sterna.core.data.filter.ListedScript
import app.sterna.core.data.filter.ScriptRule
import app.sterna.core.data.filter.ScriptSnapshot
import app.sterna.core.data.filter.listing
import app.sterna.core.data.filter.loadedFilterRules
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
        val forms = state.scriptRules.filterIsInstance<ScriptRule.Form>()
        assertEquals("a block ending in stop is a form rule too", listOf("Spam", "boss"), forms.map { it.name })
        assertEquals(forms.map { FiltersListItem.Form(it.rule) }, filtersListing(state).filterIsInstance<FiltersListItem.Form>())
    }

    @Test fun `a write goes over the opened script, under its name, and an edit reaches its own block`() {
        val loaded = loadedFilterRules("roundcube", text)
        val boss = (loaded.scriptRules.single { it is ScriptRule.Form && it.name == "boss" } as ScriptRule.Form).rule

        val untouched = (filtersWrite(loaded, FiltersChange.Rewrite) as FiltersWritePlan.Write).write
        assertEquals("roundcube", untouched.name)
        assertEquals(ScriptSnapshot("roundcube", text), untouched.read)
        assertEquals("a rewrite changes nothing", text, untouched.script.emit())

        val edited = filtersWrite(loaded, FiltersChange.PutRule(was = boss, rule = boss.copy(value = "chief@example.com")))
        assertEquals(text.replace("boss@example.com", "chief@example.com"), (edited as FiltersWritePlan.Write).write.script.emit())
        val added = filtersWrite(loaded, FiltersChange.PutRule(was = null, rule = FilterRule(name = "new", value = "x@example.com", moveTo = "Box")))
        assertEquals(
            listOf("Spam", "boss", "new"),
            loadedFilterRules("roundcube", (added as FiltersWritePlan.Write).write.script.emit()).scriptRules.map { it.name },
        )
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

        val write = (filtersWrite(loaded, FiltersChange.Rewrite) as FiltersWritePlan.Write).write

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

    @Test fun `picking a script opens it, and does nothing on the one open`() {
        // Nothing is ever left unwritten on the list, so there is nothing to ask about on the way.
        assertEquals(ScriptPick.OPEN, scriptPickStep(picked = "roundcube", opened = "sterna"))
        assertEquals(ScriptPick.STAY, scriptPickStep(picked = "sterna", opened = "sterna"))
    }

    @Test fun `opening another script forgets the one left, keeping only the account`() {
        val loaded = loadedFilterRules("roundcube", text).listing(listed, "roundcube", serverResponder = false)
        val shown = FiltersUiState(loading = false, accountLabel = "alex@example.test", folders = listOf("Work"), landed = 2)
            .withScript(loaded)
            .copy(
                overwritePending = FiltersChange.Rewrite,
                proposal = FiltersChange.PutRule(null, FilterRule(name = "n", value = "v")),
                retry = FiltersChange.Rewrite,
                replayedOnce = true,
            )

        val leaving = shown.leavingScript()

        assertEquals(
            "nothing of the script left survives, a change waiting for its replay least of all",
            FiltersUiState(loading = true, accountLabel = "alex@example.test", folders = listOf("Work")),
            leaving,
        )
        assertEquals(emptyList<ScriptRule>(), leaving.scriptRules)
        assertEquals(null, leaving.overwritePending)
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
