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

    @Test fun `the stopped rules keep a gesture that puts them back`() {
        val lines = text(FILTERS_SCREEN).lines().map { it.trim() }
        val guarded = text(FILTERS_SCREEN).substringAfter("if (state.rulesNotRunning) {").substringBefore("FiltersNoticeLine(state)")
        assertTrue(
            "the restart button must sit under the rulesNotRunning guard, on the onRestart callback: without " +
                "it the screen states the problem and locks the door, and the responder screen's sentence " +
                "sends the user to a screen with nothing to press",
            "TextButton(onClick = onRestart, enabled = !busy) {" in guarded.lines().map { it.trim() } &&
                "Text(stringResource(R.string.settings_filters_restart))" in guarded.lines().map { it.trim() },
        )
        assertEquals(
            "…and onRestart is a write of the script as read, through the one door",
            listOf("onRestart = { viewModel.save(FiltersChange.Rewrite) },"),
            lines.filter { it.startsWith("onRestart = ") },
        )
    }

    @Test fun `every write of the filters screen is a viewModel save of one change`() {
        // The door is executed in FiltersSaveGateTest. Pinned here is that each gesture hands it
        // the change it means: a swapped argument writes the wrong rule, and every executed test
        // stays green.
        val lines = text(FILTERS_SCREEN).lines().map { it.trim() }
        assertEquals(
            listOf(
                "TextButton(onClick = { confirmDelete = null; viewModel.save(change) }) {",
                "TextButton(onClick = { confirmToggle = null; viewModel.save(change) }) {",
                "onSave = { viewModel.save(FiltersChange.PutRule(editing.original, it, editing.rank)) },",
                "onSave = { viewModel.save(FiltersChange.PutText(editingText.name, editingText.was, it, editingText.rank)) },",
                "onMove = { change -> if (filtersWarnsBeforeWrite(state)) confirmToggle = change else viewModel.save(change) },",
                "if (filtersWarnsBeforeWrite(state)) confirmToggle = change else viewModel.save(change)",
                "onRestart = { viewModel.save(FiltersChange.Rewrite) },",
            ),
            lines.filter { "viewModel.save(" in it },
        )
        assertEquals(
            "a switch writes its own rule, by its rank among twins, and asks first where the auto-reply line applies",
            listOf("val change = FiltersChange.PutRule(item.rule, item.rule.copy(enabled = on), item.rank)"),
            lines.filter { "copy(enabled = on)" in it },
        )
        assertTrue(
            "the switch's question says what the write costs, in the list line's own words",
            text(FILTERS_SCREEN).substringAfter("confirmToggle?.let { change ->").substringBefore("confirmButton")
                .contains("stringResource(R.string.settings_filters_stops_auto_reply),"),
        )
        assertEquals(
            "a deletion is only ever asked, never written straight from the button that asks",
            listOf(
                "onDelete = editing.original?.let { was -> { confirmDelete = FiltersChange.DeleteRule(was, editing.rank) } },",
                "onDelete = { confirmDelete = FiltersChange.DeleteText(editingText.name, editingText.was, editingText.rank) },",
                "onDeleteText = { item -> confirmDelete = FiltersChange.DeleteText(item.name, item.text, item.rank) },",
            ),
            lines.filter { "FiltersChange.Delete" in it },
        )
        assertEquals(
            "each editor's Save action is gated by its tested rule, on the editor's own values",
            listOf(
                "enabled = textSaveEnabled(original = target.was, text = text, busy = busy, present = textTargetPresent(state, target)),",
                "enabled = ruleSaveEnabled(original = target.original, rule = rule, busy = busy, present = ruleTargetPresent(state, target)),",
            ),
            lines.filter { "SaveEnabled(" in it },
        )
        assertEquals(
            "the busy flag the editors and the list are given is the tested one",
            listOf("val busy = filtersBusy(state)"),
            lines.filter { "filtersBusy(" in it },
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
                "TextButton\\(onClick = viewModel::confirmOverwrite\\) \\{\\s*" +
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

    @Test fun `both editors ask before dropping what was typed, survive rotation, and never open a Bulwark rule`() {
        val lines = text(FILTERS_SCREEN).lines().map { it.trim() }
        assertEquals(
            "one back handler per editor, the list's, which only holds the screen while a write runs, and the reorder mode's, which leaves that mode",
            listOf(
                "BackHandler { leave() }",
                "BackHandler { leave() }",
                "BackHandler(enabled = !filtersMayLeave(state)) {}",
                "BackHandler(enabled = reordering && mayReorder) { reordering = false }",
            ),
            lines.filter { it.startsWith("BackHandler") }.sorted(),
        )
        assertEquals(
            "every top arrow goes through the same decision as its back gesture",
            listOf("onBack = { back() },", "onBack = { leave() },", "onBack = { leave() },"),
            lines.filter { it.startsWith("onBack = {") }.sorted(),
        )
        assertEquals(
            "each editor asks on its own measure of a change, and stays while its write runs",
            listOf(
                "when (editorLeaveStep(changed = text != target.was, saving = state.saving)) {",
                "when (editorLeaveStep(changed = ruleEdited(target.original, rule), saving = state.saving)) {",
            ),
            lines.filter { "editorLeaveStep(" in it },
        )
        assertTrue(
            "which rule or block is open, and what was typed in either editor, are saveable: a rotation must not drop the edit",
            "var editingSaved by rememberSaveable { mutableStateOf<ArrayList<Any?>?>(null) }" in lines &&
                "var editingTextSaved by rememberSaveable { mutableStateOf<ArrayList<Any?>?>(null) }" in lines &&
                "var text by rememberSaveable { mutableStateOf(target.start) }" in lines &&
                "var rule by rememberSaveable(stateSaver = FilterRuleSaver) { mutableStateOf(target.start) }" in lines &&
                "private val FilterRuleSaver = Saver<FilterRule, ArrayList<Any?>>(save = { savedRule(it) }, restore = { restoredRule(it) })" in lines,
        )
        assertTrue(
            "the text editor opens only a card the listing calls editable",
            ".then(if (item.editable) Modifier.clickable(onClickLabel = open, onClick = onEdit) else Modifier)" in lines,
        )
        assertTrue(
            "Add rule opens an empty editor and creates nothing: the rule exists only once saved",
            "onAdd = { openRule(RuleTarget(original = null, start = FilterRule())) }," in lines,
        )
    }

    @Test fun `only a write that landed closes the editor and says so, consumed once`() {
        val source = text(FILTERS_SCREEN)
        val effect = source.substringAfter("LaunchedEffect(state.landed) {").substringBefore("LaunchedEffect(state.proposal)")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(
            "an event the view model sets and the screen clears: no count compared with a saved one, " +
                "which a new process restarts from zero",
            listOf(
                "if (state.landed == null) return@LaunchedEffect",
                "editingSaved = null",
                "editingTextSaved = null",
                "snackbarScope.launch { snackbar.showSnackbar(savedText) }",
                "viewModel.landedTaken()",
                "}",
            ),
            effect,
        )
        assertEquals(listOf("fun landedTaken() = _state.update { it.copy(landed = null) }"), text(FILTERS_VM).lines().map { it.trim() }.filter { "landedTaken" in it })
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

    @Test fun `the filters screen opens a picked script through the tested decision`() {
        val lines = text(FILTERS_SCREEN).lines().map { it.trim() }
        assertEquals(
            listOf("when (scriptPickStep(picked = picked, opened = state.scriptName)) {"),
            lines.filter { "scriptPickStep(" in it },
        )
        assertTrue("ScriptPick.OPEN -> viewModel.openScript(picked)" in lines)
    }

    @Test fun `the not-active line is guarded by its own flag and its button activates, never saves`() {
        val lines = text(FILTERS_SCREEN).lines().map { it.trim() }
        assertTrue(
            "the line and its button must be guarded by state.scriptNotActive",
            "if (state.scriptNotActive) {" in lines && "R.string.settings_filters_script_inactive" in text(FILTERS_SCREEN),
        )
        assertEquals(
            "\"Make active\" is viewModel::activate, which sends the activation alone: wired to save it rewrites the script",
            listOf("onActivate = viewModel::activate,"),
            lines.filter { "viewModel::activate" in it },
        )
        assertEquals(listOf("TextButton(onClick = onActivate, enabled = !busy) {"), lines.filter { "onClick = onActivate" in it })
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
            listOf("pickedScript = name", "opened = null", "_state.update { it.leavingScript() }", "load()"),
            body("fun openScript(name: String) {", "fun load()").drop(1).filter { it.isNotEmpty() && it != "}" },
        )
        val activate = body("fun activate()", "fun proposalTaken()")
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
        assertEquals(
            "no write path consults the gate on its own: it is filtersSaveAsked's, executed in FiltersSaveGateTest",
            listOf("if (!filtersAcceptEdits(_state.value)) return"),
            lines.filter { "filtersAcceptEdits(" in it && !it.startsWith("internal fun filtersAcceptEdits") },
        )
        assertEquals(
            "a save that returns after a newer read, success or failure, must not paint over it",
            2,
            lines.count { it == "if (generation != loads) return@launch" },
        )
        assertTrue(
            "closing the overwrite confirmation outside its buttons cancels it, like Cancel",
            "onDismissRequest = viewModel::cancelOverwrite," in text(FILTERS_SCREEN).lines().map { it.trim() },
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
