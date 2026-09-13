package app.sterna.ui.settings

import app.sterna.R
import app.sterna.core.data.account.MailProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Which sentence the responder and filters screens show when they cannot offer their form (#211).
 * `loadVacation` and `loadFilterRules` answer the same bare `Unsupported` for an IMAP account and
 * for a JMAP server without the capability; the first half of this class executes the decision that
 * tells them apart. The second half is a source lint, same instrument and same disclaimer as
 * [SettingsScreenHonestyTest]: it only proves the ViewModels and screens call that decision with
 * the account's protocol and the state's cause, since neither is instantiable in this module.
 */
class FeatureUnavailableTest {

    // -- the decision, executed ---------------------------------------------------------------

    @Test fun `an IMAP account is told the responder depends on the account type, not the server`() {
        val state = vacationUnavailableState(MailProtocol.IMAP, accountLabel = "alex@example.org")
        assertFalse("no form, so no Save, on an IMAP account", state.supported)
        assertEquals(
            "an IMAP account must carry the IMAP cause: the server was never asked, blaming it is false",
            UnavailableCause.IMAP_ACCOUNT, state.unavailableCause,
        )
        assertEquals(R.string.settings_vacation_imap_account, vacationUnavailableText(state.unavailableCause))
        assertEquals("alex@example.org", state.accountLabel)
    }

    @Test fun `a JMAP server without the capability keeps the server sentence for the responder`() {
        val state = vacationUnavailableState(MailProtocol.JMAP, accountLabel = "alex@example.org")
        assertFalse(state.supported)
        assertEquals(UnavailableCause.SERVER, state.unavailableCause)
        assertEquals(R.string.settings_vacation_unsupported, vacationUnavailableText(state.unavailableCause))
    }

    @Test fun `an IMAP account is told the filters depend on the account type, not the server`() {
        val state = filtersUnavailableState(MailProtocol.IMAP, accountLabel = "alex@example.org")
        assertFalse(
            "saveFilterRules has no protocol guard of its own: an unsupported state is what keeps " +
                "Save off the screen on an IMAP account",
            state.supported,
        )
        assertEquals(UnavailableCause.IMAP_ACCOUNT, state.unavailableCause)
        assertEquals(R.string.settings_filters_imap_account, filtersUnavailableText(state.unavailableCause))
        assertEquals("alex@example.org", state.accountLabel)
    }

    @Test fun `a JMAP server without the capability keeps the server sentence for the filters`() {
        val state = filtersUnavailableState(MailProtocol.JMAP, accountLabel = "alex@example.org")
        assertFalse(state.supported)
        assertEquals(UnavailableCause.SERVER, state.unavailableCause)
        assertEquals(R.string.settings_filters_unsupported, filtersUnavailableText(state.unavailableCause))
    }

    // -- the wiring, read as text -------------------------------------------------------------

    @Test fun `the responder ViewModel decides the cause from the loaded account's protocol`() {
        assertTrue(
            "VacationViewModel must answer VacationState.Unsupported with " +
                "vacationUnavailableState(protocol = credentials.protocol, accountLabel = store.accountLabel())",
            Regex(
                "VacationState\\.Unsupported\\s*->\\s*_state\\.value\\s*=\\s*vacationUnavailableState\\(\\s*" +
                    "protocol\\s*=\\s*credentials\\.protocol\\s*,\\s*accountLabel\\s*=\\s*store\\.accountLabel\\(\\)\\s*,?\\s*\\)",
            ).containsMatchIn(text(VACATION_VM)),
        )
    }

    @Test fun `the filters ViewModel decides the cause from the loaded account's protocol`() {
        assertTrue(
            "FiltersViewModel must answer FilterRulesState.Unsupported with " +
                "filtersUnavailableState(protocol = credentials.protocol, accountLabel = store.accountLabel())",
            Regex(
                "FilterRulesState\\.Unsupported\\s*->\\s*\\{[^}]*?_state\\.value\\s*=\\s*filtersUnavailableState\\(\\s*" +
                    "protocol\\s*=\\s*credentials\\.protocol\\s*,\\s*accountLabel\\s*=\\s*store\\.accountLabel\\(\\)\\s*,?\\s*\\)",
            ).containsMatchIn(text(FILTERS_VM)),
        )
    }

    @Test fun `both screens choose their sentence from the state's cause`() {
        assertTrue(
            "SettingsScreen must render the unsupported responder as " +
                "VacationNote(stringResource(vacationUnavailableText(state.unavailableCause)))",
            Regex(
                "!state\\.supported\\s*->\\s*VacationNote\\(\\s*stringResource\\(\\s*" +
                    "vacationUnavailableText\\(\\s*state\\.unavailableCause\\s*\\)\\s*\\)\\s*\\)",
            ).containsMatchIn(text(SETTINGS_SCREEN)),
        )
        assertTrue(
            "FiltersScreen must render the unsupported filters as " +
                "FiltersNote(stringResource(filtersUnavailableText(state.unavailableCause)))",
            Regex(
                "!state\\.supported\\s*->\\s*FiltersNote\\(\\s*stringResource\\(\\s*" +
                    "filtersUnavailableText\\(\\s*state\\.unavailableCause\\s*\\)\\s*\\)\\s*\\)",
            ).containsMatchIn(text(FILTERS_SCREEN)),
        )
    }

    /** Comment lines dropped whole: a false match is a false failure, not a false pass. */
    private fun text(file: File): String = file.readLines()
        .filterNot { val c = it.trimStart(); c.startsWith("//") || c.startsWith("*") || c.startsWith("/*") }
        .joinToString("\n")

    private companion object {
        const val SETTINGS_DIR = "app/src/main/kotlin/app/sterna/ui/settings"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "$SETTINGS_DIR/SettingsScreen.kt").isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "sources as text and needs a working directory inside the checkout",
                )
        }

        val SETTINGS_SCREEN: File by lazy { File(root, "$SETTINGS_DIR/SettingsScreen.kt") }
        val FILTERS_SCREEN: File by lazy { File(root, "$SETTINGS_DIR/FiltersScreen.kt") }
        val VACATION_VM: File by lazy { File(root, "$SETTINGS_DIR/VacationViewModel.kt") }
        val FILTERS_VM: File by lazy { File(root, "$SETTINGS_DIR/FiltersViewModel.kt") }
    }
}
