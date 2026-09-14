package app.sterna.ui.settings

import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Whether Save on the responder screen writes at once or asks first (#211). On an IMAP account the
 * auto-reply is written into the `sterna` script and that script is activated, so the question is
 * the filters screen's: is a script this app did not write at stake.
 *
 * The first half executes the two decisions. The second half is a source lint, same disclaimer as
 * [FilterWarningWiringTest]: `VacationViewModel` and `VacationScreen` are not instantiable here, so
 * it proves only that they call the decisions with the state's own flag, never what is drawn.
 */
class VacationSaveGateTest {

    // -- the decision, executed ---------------------------------------------------------------

    @Test fun `a script this app did not write sends Save through a confirmation`() {
        assertEquals(FiltersSaveStep.CONFIRM_OVERWRITE, vacationSaveStep(foreignActiveScript = true))
    }

    @Test fun `an ordinary save is not interrupted`() {
        assertEquals(FiltersSaveStep.WRITE, vacationSaveStep(foreignActiveScript = false))
    }

    @Test fun `a landed save leaves nothing to confirm, and keeps the form as written`() {
        val before = VacationUiState(
            loading = false,
            enabled = true,
            subject = "Away",
            message = "Back on the 28th.",
            fromDate = 1L,
            toDate = 2L,
            saving = true,
            dirty = true,
            savedTick = 2,
            foreignActiveScript = true,
        )

        val after = vacationSavedState(before)

        assertFalse("the script on the server is Sterna's own now: a second Save must not ask again", after.foreignActiveScript)
        assertEquals(before.copy(saving = false, dirty = false, savedTick = 3, foreignActiveScript = false), after)
    }

    // -- the wiring, read as text -------------------------------------------------------------

    @Test fun `both write paths of the responder screen go through the confirmation decision`() {
        val screen = DeclarationSource.functionBody(SETTINGS_SCREEN, "private fun VacationScreen(")
        val form = DeclarationSource.functionBody(SETTINGS_SCREEN, "private fun VacationForm(")
        assertEquals(
            "VacationScreen must consult vacationSaveStep on the state's own flag, once",
            listOf("when (vacationSaveStep(foreignActiveScript = state.foreignActiveScript)) {"),
            screen.filter { "vacationSaveStep(" in it },
        )
        assertEquals(
            "the exit dialog's Save and the form's Save must both funnel into requestSave",
            listOf(
                "onSave = { confirmExit = false; requestSave(thenLeave = true) },",
                "else -> VacationForm(state, viewModel, onSave = { requestSave(thenLeave = false) })",
            ),
            screen.filter { "requestSave(thenLeave =" in it },
        )
        assertEquals(
            "the form's button must be that onSave, never viewModel::save",
            listOf("onClick = onSave,"),
            form.filter { it.startsWith("onClick = on") || "::save" in it },
        )
        assertEquals(
            "the only two places the responder screen may start a write: the WRITE branch and the " +
                "overwrite confirmation's own button",
            listOf(
                "FiltersSaveStep.WRITE -> { leaveAfterSave = thenLeave; viewModel.save() }",
                "TextButton(onClick = { confirmOverwrite = false; leaveAfterSave = overwriteThenLeave; viewModel.save() }) {",
            ),
            (screen + form).filter { Regex("viewModel(::save\\b|\\.save\\(\\))").containsMatchIn(it) },
        )
        assertEquals(
            "the dialog says what the responder's save destroys, not the filters screen's sentence",
            listOf(
                "title = { Text(stringResource(R.string.settings_vacation_overwrite_title)) },",
                "stringResource(R.string.settings_vacation_overwrite_body),",
                "Text(stringResource(R.string.settings_vacation_overwrite_confirm))",
            ),
            screen.filter { "R.string.settings_vacation_overwrite_" in it || "R.string.settings_filters_overwrite_" in it },
        )
    }

    @Test fun `the ViewModel carries the loaded flag and drops it only through the tested decision`() {
        val load = DeclarationSource.functionBody(VACATION_VM, "fun load()")
        assertEquals(listOf("foreignActiveScript = result.foreignActiveScript,"), load.filter { "foreignActiveScript" in it })
        val save = DeclarationSource.functionBody(VACATION_VM, "fun save()")
        assertEquals(
            listOf("_state.update { vacationSavedState(it) }"),
            save.filter { "savedTick" in it || "vacationSavedState" in it || "foreignActiveScript" in it },
        )
    }

    /**
     * The path that keeps a blocked sender out of the auto-reply must be the path the block rule
     * names: both read the account's cached folders through `trashFilePath`. `MailBySenderTest`
     * executes `trashFilePath`; this only proves the save hands it on, from this account's folders.
     */
    @Test fun `the save hands over the Trash path the block rule names`() {
        val save = DeclarationSource.functionBody(VACATION_VM, "fun save()")
        assertEquals(
            listOf(
                "val mailboxes = runCatching { repo.observeMailboxes(credentials.id).first() }",
                ".getOrElseUnlessCancelled { emptyList() }",
                "repo.accounts.saveVacation(credentials, vacation, trashFolder = trashFilePath(mailboxes))",
            ),
            save.filter { "mailboxes" in it || "saveVacation(" in it || "getOrElseUnlessCancelled" in it || "trashFilePath" in it },
        )
    }

    private companion object {
        val SETTINGS_SCREEN by lazy { DeclarationSource.file("app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt") }
        val VACATION_VM by lazy { DeclarationSource.file("app/src/main/kotlin/app/sterna/ui/settings/VacationViewModel.kt") }
    }
}
