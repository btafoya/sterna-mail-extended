package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test, same instrument and same disclaimer as
 * [SettingsScreenHonestyTest]: text only, so holes 3 and 4 of CONTRIBUTING apply to every test here.
 */
class FilterWarningWiringTest {

    @Test fun `the responder screen prints the statement of fact for the inactive script`() {
        assertTrue(
            "SettingsScreen must map RULES_NOT_RUNNING_WITH_REMEDY to " +
                "R.string.settings_vacation_filters_not_running — the responder screen states the " +
                "problem and its own Save is grey, so its sentence is the one that has to say " +
                "where the remedy is",
            ARM_NOT_RUNNING.containsMatchIn(text(SETTINGS_SCREEN)),
        )
    }

    @Test fun `the responder screen chooses that sentence against its own switch`() {
        // E3: the remedy (Save on the filters screen) switches the auto-reply off, and during an
        // absence it was offered on the screen whose switch reads on, saying nothing of the sort.
        val source = text(SETTINGS_SCREEN)
        assertTrue(
            "the responder screen must ask 'vacationFilterLine(state.filterWarning, " +
                "responderEnabled = state.enabled)' — the switch the user is looking at",
            Regex(
                "vacationFilterLine\\(state\\.filterWarning, responderEnabled = state\\.enabled\\)",
            ).containsMatchIn(source),
        )
        assertTrue(
            "the fact-only case must print R.string.settings_filters_not_running: the same words " +
                "the filters screen uses for the same state, minus the path that would undo this " +
                "screen. No new string, and no silence either",
            ARM_FACT_ONLY.containsMatchIn(source),
        )
        assertTrue(
            "the responder screen must not choose its sentence from the raw warning any more: a " +
                "'FilterScriptWarning.RULES_NOT_RUNNING ->' arm here is the unconditional remedy " +
                "written back",
            "FilterScriptWarning.RULES_NOT_RUNNING ->" !in source,
        )
    }

    @Test fun `the responder screen names the path to the remedy, not just the problem`() {
        assertTrue(
            "the sentence takes its two arguments from the strings the user actually reads on " +
                "screen (Settings → Filters), like sender_volume_block_body does: written as " +
                "literals they drift out of the menu and out of the nine translations",
            ARM_NOT_RUNNING_ARGS.containsMatchIn(text(SETTINGS_SCREEN)),
        )
    }

    @Test fun `the responder screen prints the prediction only for the other case`() {
        assertTrue(
            "SettingsScreen must map VacationFilterLine.RESPONDER_WILL_SUSPEND_RULES to " +
                "R.string.settings_vacation_suspends_filters",
            ARM_WILL_SUSPEND.containsMatchIn(text(SETTINGS_SCREEN)),
        )
    }

    @Test fun `the filters screen prints the statement of fact, guarded by its own flag`() {
        val source = text(FILTERS_SCREEN)
        assertTrue(
            "FiltersScreen must show R.string.settings_filters_not_running: it lists the rules as " +
                "if they were filtering mail while the script carrying them is not active",
            "R.string.settings_filters_not_running" in source,
        )
        assertTrue(
            "the line must be guarded by state.rulesNotRunning, not by state.foreignActive — the " +
                "foreign flag is false in exactly the state where the rules are dead",
            Regex("if \\(state\\.rulesNotRunning\\)").containsMatchIn(source),
        )
    }

    @Test fun `the filters screen gates Save through the tested rule, not an expression of its own`() {
        val source = text(FILTERS_SCREEN)
        assertTrue(
            "the Save button must be gated by filtersSaveEnabled(...): written inline it goes back " +
                "to `!saving && dirty`, which greys out the ONE gesture that restarts rules the " +
                "server has stopped — the screen then states the problem and locks the door",
            Regex("enabled = filtersSaveEnabled\\(").containsMatchIn(source),
        )
        assertTrue(
            "filtersSaveEnabled must be given state.rulesNotRunning: without it the widening is " +
                "decorative and the dead end is back",
            Regex("rulesNotRunning = state\\.rulesNotRunning").containsMatchIn(source),
        )
    }

    @Test fun `both write paths of the filters screen go through the confirmation decision`() {
        // The failure mode this closes is not "the confirmation is missing" but "the confirmation
        // is on the button only": the exit dialog's Save writes the same script, is reached by the
        // back gesture and by the title-bar arrow, and a door hung on one of the two is no door.
        val lines = text(FILTERS_SCREEN).lines().map { it.trim() }
        assertEquals(
            "FiltersScreen must consult filtersSaveStep(scriptUnreadable = state.scriptUnreadable) " +
                "exactly once, on the screen's own flag: a literal there makes the whole gate " +
                "decorative, and a second call site is a second policy",
            listOf("when (filtersSaveStep(scriptUnreadable = state.scriptUnreadable)) {"),
            lines.filter { it.contains("filtersSaveStep(") },
        )
        assertEquals(
            "BOTH Save gestures must funnel into requestSave(...) — the exit dialog first " +
                "(back gesture and title-bar arrow), then the Save button. `thenLeave` is which " +
                "one asked: dropped, either the screen leaves before the server has taken the " +
                "write, or it stays on a screen the user asked to leave",
            listOf(
                "onSave = { confirmExit = false; requestSave(thenLeave = true) },",
                "onSave = { requestSave(thenLeave = false) },",
            ),
            lines.filter { it.startsWith("onSave = ") },
        )
        assertTrue(
            "…and the Save button's own click must BE that onSave: rewired to viewModel::save the " +
                "funnel is still there, still tested, and walked straight past",
            lines.contains("onClick = onSave,"),
        )
        assertEquals(
            "the ONLY two places FiltersScreen may start a write: the WRITE branch of the " +
                "decision, and the overwrite confirmation's own button. Anything else in this " +
                "list is a third door, which is exactly how this one was found open",
            listOf(
                "FiltersSaveStep.WRITE -> { leaveAfterSave = thenLeave; viewModel.save() }",
                "TextButton(onClick = { confirmOverwrite = false; leaveAfterSave = overwriteThenLeave; viewModel.save() }) {",
            ),
            lines.filter { Regex("viewModel(::save\\b|\\.save\\(\\))").containsMatchIn(it) },
        )
    }

    @Test fun `the overwrite confirmation says what it destroys, and scrolls while saying it`() {
        val source = text(FILTERS_SCREEN)
        assertTrue(
            "the dialog must carry R.string.settings_filters_overwrite_body — the only sentence " +
                "that says the whole script is replaced by what is on screen; the title alone is " +
                "a question the user cannot answer",
            "R.string.settings_filters_overwrite_body" in source,
        )
        assertTrue(
            "…in a body that SCROLLS. Material's text slot is height-bounded and clips a bare " +
                "Text without a word, and this sentence is long by necessity (measured on the " +
                "block-sender dialog, cut mid-word at the largest font scale)",
            Regex(
                "R\\.string\\.settings_filters_overwrite_body\\),?\\s*" +
                    "modifier = Modifier\\.verticalScroll\\(rememberScrollState\\(\\)\\),",
            ).containsMatchIn(source),
        )
        assertTrue(
            "the confirming button must be the short verb (settings_filters_overwrite_confirm), " +
                "never the body: with a sentence in it the label wrapped and Cancel was drawn " +
                "INSIDE the confirm button — one tap on both, on a permanent server-side write",
            Regex(
                "TextButton\\(onClick = \\{ confirmOverwrite = false; leaveAfterSave = " +
                    "overwriteThenLeave; viewModel\\.save\\(\\) \\}\\) \\{\\s*" +
                    "Text\\(stringResource\\(R\\.string\\.settings_filters_overwrite_confirm\\)\\)",
            ).containsMatchIn(source),
        )
    }

    @Test fun `the filters screen chooses its foreign-script line through the tested decision`() {
        val source = text(FILTERS_SCREEN)
        assertTrue(
            "FiltersScreen must ask foreignScriptNotice(...) with ALL THREE facts, in the state's " +
                "own words: without scriptUnreadable the one line that names the overwrite can " +
                "never appear, and without vacationScriptActive the line falls back to \"another " +
                "filter script is active\", which names the cause and hides what Save costs",
            Regex(
                "foreignScriptNotice\\(\\s*scriptUnreadable = state\\.scriptUnreadable,\\s*" +
                    "foreignActive = state\\.foreignActive,\\s*" +
                    "vacationScriptActive = state\\.vacationScriptActive,",
            ).containsMatchIn(source),
        )
        assertTrue(
            "the auto-reply case must print R.string.settings_filters_stops_auto_reply",
            Regex(
                "ForeignScriptNotice\\.STOPS_AUTO_REPLY\\s*->\\s*R\\.string\\.settings_filters_stops_auto_reply",
            ).containsMatchIn(source),
        )
        assertTrue(
            "and the unreadable case must print R.string.settings_filters_unreadable — the only " +
                "sentence that says a save will REPLACE what is on the server",
            Regex(
                "ForeignScriptNotice\\.UNREADABLE_SCRIPT\\s*->\\s*R\\.string\\.settings_filters_unreadable",
            ).containsMatchIn(source),
        )
        assertTrue(
            "and another active script is the server's `vacation` behind a failed status read: " +
                "Save stops it all the same, so it gets the auto-reply line, never the old " +
                "\"another script is active\" sentence",
            Regex(
                "ForeignScriptNotice\\.ANOTHER_SCRIPT\\s*->\\s*R\\.string\\.settings_filters_stops_auto_reply",
            ).containsMatchIn(source) && "settings_filters_foreign_warning" !in source,
        )
    }

    @Test fun `the filters screen asks the tested rule whether to say there are no rules`() {
        // The decision is showsNoRulesNote(), executed in FilterStatusRefreshTest. Pinned here is
        // the condition, whole: written back as `state.rules.isEmpty()` the screen says "No rules
        // yet. Add one…" straight under "Sterna Mail cannot read this account's filter script;
        // saving will replace it", and every executed test stays green.
        val condition = text(FILTERS_SCREEN).lines().map { it.trim() }
            .filter { it.startsWith("showsNoRulesNote(") }
        assertEquals(
            "FiltersScreen must gate R.string.settings_filters_empty on exactly " +
                "'showsNoRulesNote(ruleCount = listing.size, scriptUnreadable = " +
                "state.scriptUnreadable) ->' — the count of cards it is showing, text blocks " +
                "included, and the flag the warning " +
                "above is chosen from. `scriptUnreadable = false` there is the contradiction " +
                "written back with nothing to catch it",
            listOf(
                "showsNoRulesNote(ruleCount = listing.size, scriptUnreadable = state.scriptUnreadable) ->",
            ),
            condition,
        )
        assertTrue(
            "…and the sentence it gates must still be the existing string: no new one, and no " +
                "silence for the ordinary empty account",
            "R.string.settings_filters_empty" in text(FILTERS_SCREEN),
        )
    }

    @Test fun `the text editor commits on the system back, survives rotation, and never opens a Bulwark rule`() {
        val lines = text(FILTERS_SCREEN).lines().map { it.trim() }
        assertEquals(
            "three back handlers, each where it belongs: the list asks before losing edits, each " +
                "editor commits what is typed. A text editor whose back drops the text loses it silently",
            listOf(
                "BackHandler { onCommit(rule) }",
                "BackHandler { onCommit(text) }",
                "BackHandler(enabled = state.dirty) { confirmExit = true }",
            ),
            lines.filter { it.startsWith("BackHandler") }.sorted(),
        )
        assertTrue(
            "the text editor's commit must reach the list: `viewModel.updateText(textCard.id, it)`",
            "onCommit = { viewModel.updateText(textCard.id, it); editingText = null }," in lines,
        )
        assertTrue(
            "which block is open, and its text, are saveable: a rotation must not drop the edit",
            "var editingText by rememberSaveable { mutableStateOf<Int?>(null) }" in lines &&
                "var text by rememberSaveable { mutableStateOf(initial) }" in lines,
        )
        assertEquals(
            "both editors' top arrow commits, like their back gesture: an arrow that cancels drops the edit",
            listOf("onBack = { onCommit(rule) },", "onBack = { onCommit(text) },"),
            lines.filter { it.startsWith("onBack = { onCommit(") || it.startsWith("onBack = { onCancel") }.sorted(),
        )
        assertTrue(
            "the text editor's Delete and the locked card's Delete both reach viewModel.removeText",
            "onDelete = { viewModel.removeText(textCard.id); editingText = null }," in lines &&
                "onDelete = { viewModel.removeText(item.id) }," in lines,
        )
        assertEquals(
            "…and each draws its Delete button on that very callback",
            2,
            lines.count { it == "TextButton(onClick = onDelete) {" },
        )
        assertTrue(
            "the text editor opens only a card the listing calls editable",
            lines.any { it.contains("it is FiltersListItem.Text && it.id == id && it.editable") } &&
                ".then(if (item.editable) Modifier.clickable(onClickLabel = open, onClick = onEdit) else Modifier)" in lines,
        )
    }

    @Test fun `the filters view model folds a status read into the state, at load and after a save`() {
        // Written back as `it.copy(rulesNotRunning = ...)` here, the update leaves the reach of
        // every test again, and it is where it was wrong: two of the three facts were never
        // refreshed after a save, and a failed read wiped the third.
        val source = text(FILTERS_VM)
        assertEquals(
            "FiltersViewModel must fold BOTH reads through filtersStateWithStatus(...) — the load " +
                "and the re-read after a save",
            2,
            Regex("filtersStateWithStatus\\(").findAll(source).count(),
        )
        // Measured, not supposed: with only "a call to loadFilterScriptStatus exists somewhere in
        // this file" asserted, replacing the load's status with `null` left the suite green, the
        // save's own read matched the pattern and answered for it. Each of the two call sites is
        // pinned on its own.
        assertTrue(
            "the load must fold in a status it read ITSELF, for the script it opened " +
                "(`status = repo.accounts.loadFilterScriptStatus(credentials, result.scriptName),`)",
            source.lines().map { it.trim() }.contains("status = repo.accounts.loadFilterScriptStatus(credentials, result.scriptName),"),
        )
        assertTrue(
            "and the save must fold in the status IT read, of the script it wrote, not null and not a fresh value",
            source.lines().map { it.trim() }.contains("val status = repo.accounts.loadFilterScriptStatus(credentials, written)") &&
                Regex("status = status").containsMatchIn(source),
        )
        assertTrue(
            "the state folded into after a save must be the CURRENT one (`state = it`): rebuilt " +
                "from scratch, the save would drop the rules and the label with it",
            Regex("filtersStateWithStatus\\(state = it, status = status\\)").containsMatchIn(source),
        )
        assertTrue(
            "the load must carry over what the RULES read concluded (`foreignFromRulesRead = " +
                "result.foreignActiveScript`): the script list cannot see a `sterna` script we " +
                "failed to parse, so passing false there loses the refusal that protects it",
            Regex("foreignFromRulesRead = result\\.foreignActiveScript").containsMatchIn(source),
        )
        // Whole line, not `containsMatchIn`.
        assertEquals(
            "…and WHICH refusal it was: the load must carry `unreadableFromRulesRead = " +
                "result.scriptUnreadable,` and nothing else on that line. The rules read is the " +
                "only place that fact exists; anything appended to it (`&& false`, `&& !x`) reads " +
                "as this line still being there and turns the flag off everywhere it is used",
            listOf("unreadableFromRulesRead = result.scriptUnreadable,"),
            source.lines().map { it.trim() }.filter { it.startsWith("unreadableFromRulesRead") },
        )
    }

    @Test fun `the filters screen opens a picked script through the tested decision, asking first over unwritten edits`() {
        val lines = text(FILTERS_SCREEN).lines().map { it.trim() }
        assertEquals(
            "one call, on the picked name, the OPENED script and the screen's own dirty flag: a literal " +
                "`dirty = false` there drops the edits without the dialog",
            listOf("when (scriptPickStep(picked = picked, opened = state.scriptName, dirty = state.dirty)) {"),
            lines.filter { "scriptPickStep(" in it },
        )
        assertTrue(
            "ASK must raise the SAME dialog as leaving the screen, remembering what to open",
            "ScriptPick.ASK -> { switchTo = picked; confirmExit = true }" in lines &&
                "ScriptPick.OPEN -> viewModel.openScript(picked)" in lines,
        )
        assertTrue(
            "Discard and a save that landed both go through leave(), which opens the picked script " +
                "instead of leaving; `onBack()` there leaves the screen when the user asked for another script",
            "onDiscard = { confirmExit = false; leave() }," in lines &&
                "PendingExit.LEAVE -> { leaveAfterSave = false; leave() }" in lines &&
                "if (next != null) viewModel.openScript(next) else onBack()" in lines,
        )
        assertTrue(
            "Cancel, and a save refused, forget the picked script: the next back gesture must leave, not open it",
            "onCancel = { confirmExit = false; switchTo = null }," in lines &&
                "PendingExit.STAY -> { leaveAfterSave = false; switchTo = null }" in lines,
        )
    }

    @Test fun `the not-active line is guarded by its own flag and its button activates, never saves`() {
        val lines = text(FILTERS_SCREEN).lines().map { it.trim() }
        assertTrue(
            "the line and its button must be guarded by state.scriptNotActive",
            "if (state.scriptNotActive) {" in lines && "R.string.settings_filters_script_inactive" in text(FILTERS_SCREEN),
        )
        assertEquals(
            "\"Make active\" is viewModel::activate, which sends the activation alone: wired to save it rewrites the script",
            listOf("onClick = viewModel::activate,"),
            lines.filter { "viewModel::activate" in it },
        )
        val vm = text(FILTERS_VM).lines().map { it.trim() }
        assertTrue(
            "the view model's activation is the repository's activation-only call, then a fresh read",
            "repo.accounts.activateScript(credentials, name)" in vm,
        )
        assertEquals(
            "the load reads the script picked by name, `pickedScript`; without it the selector reopens the active script",
            listOf("when (val result = repo.accounts.loadFilterRules(credentials, pickedScript)) {"),
            vm.filter { "loadFilterRules(" in it },
        )
    }

    @Test fun `opening another script drops the one left, and nothing moves while a read or an activation runs`() {
        val source = text(FILTERS_VM)
        fun body(from: String, to: String) = source.substring(source.indexOf(from), source.indexOf(to)).lines().map { it.trim() }
        assertEquals(
            "openScript must forget the script left, its base and its edits, BEFORE reading the next one: " +
                "a failed or abandoned read otherwise leaves Save able to write the discarded edits into it",
            listOf("pickedScript = name", "opened = null", "serverRules = emptyList()", "_state.update { it.leavingScript() }", "load()"),
            body("fun openScript(name: String) {", "fun load()").drop(1).filter { it.isNotEmpty() && it != "}" },
        )
        val activate = body("fun activate()", "fun addRule()")
        assertTrue(
            "the activation is gated like an edit, and shows the loading state, which hides every control",
            "if (!filtersAcceptEdits(_state.value)) return" in activate &&
                "_state.update { it.copy(activating = true, loading = true, errorKind = null) }" in activate,
        )
        assertTrue(
            "a failed activation is worded as ACTIVATE and the list is read again all the same: no return before load()",
            "staleFailure = FiltersError.ACTIVATE to t" in activate && "return@launch" !in activate &&
                activate.filter { it.isNotEmpty() && it != "}" }.last() == "load()",
        )
        val lines = source.lines().map { it.trim() }
        assertTrue(
            "edits and saves pass the same gate",
            "if (!filtersAcceptEdits(it)) return@update it" in lines && "if (!filtersAcceptEdits(edited)) return" in lines,
        )
        assertEquals(
            "a save that returns after a newer read, success or failure, must not paint over it",
            2,
            lines.count { it == "if (generation != loads) return@launch" },
        )
        assertTrue(
            "closing the overwrite confirmation outside its buttons forgets the picked script, like Cancel",
            "onDismissRequest = { confirmOverwrite = false; switchTo = null }," in text(FILTERS_SCREEN).lines().map { it.trim() },
        )
    }

    @Test fun `the prediction is gated on what the server holds, never on a literal`() {
        // Passing a constant here is how the responder screen came to tell somebody whose
        // auto-reply was already running that switching it on would suspend their rules.
        val literals = text(VACATION_VM).lines()
            .filter { Regex("responderEnabled = (true|false)\\b").containsMatchIn(it) }
        assertTrue(
            "VacationViewModel must pass the responder state the SERVER holds to " +
                "filterScriptWarning, not a hard-coded true/false. Found:\n" + literals.joinToString("\n"),
            literals.isEmpty(),
        )
    }

    @Test fun `the responder view model keeps the line it has when the re-read fails`() {
        // `previous = null` here IS the bug, written back: a save whose follow-up read fails then
        // clears the warning, over the account where the responder has just been switched off and
        // the server left no script active at all.
        val source = text(VACATION_VM)
        assertTrue(
            "VacationViewModel must refresh through refreshedFilterWarning(previous = it.filterWarning, …) " +
                "after a save, so a read that failed leaves what is on screen alone",
            Regex(
                "refreshedFilterWarning\\(\\s*previous = it\\.filterWarning,\\s*status = status,",
            ).containsMatchIn(source),
        )
        assertTrue(
            "and the status it folds in must be one this run actually read",
            Regex("val status = repo\\.accounts\\.loadFilterScriptStatus\\(credentials\\)").containsMatchIn(source),
        )
    }

    /** Comment lines dropped whole: a false match is a false failure, not a false pass. */
    private fun text(file: File): String = file.readLines()
        .filterNot { val c = it.trimStart(); c.startsWith("//") || c.startsWith("*") || c.startsWith("/*") }
        .joinToString("\n")

    private companion object {
        val ARM_NOT_RUNNING = Regex(
            "VacationFilterLine\\.RULES_NOT_RUNNING_WITH_REMEDY\\s*->\\s*stringResource\\(\\s*" +
                "R\\.string\\.settings_vacation_filters_not_running,",
        )
        val ARM_FACT_ONLY = Regex(
            "VacationFilterLine\\.RULES_NOT_RUNNING_FACT\\s*->\\s*stringResource\\(" +
                "R\\.string\\.settings_filters_not_running\\)",
        )
        val ARG_MENU = Regex("stringResource\\(R\\.string\\.inbox_settings\\)")
        val ARG_FILTERS = Regex("stringResource\\(R\\.string\\.settings_filters_title\\)")
        val ARM_NOT_RUNNING_ARGS = Regex(
            ARM_NOT_RUNNING.pattern + "\\s*" + ARG_MENU.pattern + ",\\s*" + ARG_FILTERS.pattern + ",",
        )
        val ARM_WILL_SUSPEND = Regex(
            "VacationFilterLine\\.RESPONDER_WILL_SUSPEND_RULES\\s*->\\s*" +
                "stringResource\\(R\\.string\\.settings_vacation_suspends_filters\\)",
        )

        const val SETTINGS_SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SETTINGS_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "sources as text and needs a working directory inside the checkout",
                )
        }

        val SETTINGS_SCREEN: File by lazy { File(root, SETTINGS_SCREEN_PATH) }
        val FILTERS_SCREEN: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/FiltersScreen.kt")
        }
        val VACATION_VM: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/VacationViewModel.kt")
        }
        val FILTERS_VM: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/FiltersViewModel.kt")
        }
    }
}
