package app.sterna.ui.settings

import app.sterna.R
import app.sterna.core.data.mail.VacationState
import app.sterna.core.imap.ManageSieveException
import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Which sentence the responder and filters screens show when they cannot offer their form, or when
 * a filters or responder load or save fails (#211). `loadVacation` answers `AccountUnsupported` for an IMAP
 * account ManageSieve is never tried on, and `Unsupported` for a server that was asked and lacks the
 * support, on either protocol, so the state tells the causes apart. The filters of a password IMAP
 * account go through ManageSieve, so their `Unsupported` no longer depends on the protocol, and a failed ManageSieve
 * dialogue is worded by the kind of its `ManageSieveException`. The first half of this class executes
 * those decisions. The second half is a source lint, same instrument and same disclaimer as
 * [SettingsScreenHonestyTest]: it only proves the ViewModels and screens call the decisions with
 * the right arguments, since neither is instantiable in this module.
 */
class FeatureUnavailableTest {

    // -- the decision, executed ---------------------------------------------------------------

    @Test fun `an IMAP account ManageSieve is never tried on is told it depends on the account type`() {
        val state = vacationUnavailableState(VacationState.AccountUnsupported, accountLabel = "alex@example.org")
        assertFalse("no form, so no Save, on an IMAP account", state.supported)
        assertEquals(
            "this account must carry the IMAP cause: the server was never asked, blaming it is false",
            UnavailableCause.IMAP_ACCOUNT, state.unavailableCause,
        )
        assertEquals(R.string.settings_vacation_imap_account, vacationUnavailableText(state.unavailableCause))
        assertEquals("alex@example.org", state.accountLabel)
    }

    @Test fun `a server that was asked and lacks the support keeps the server sentence, on IMAP too`() {
        val state = vacationUnavailableState(VacationState.Unsupported, accountLabel = "alex@example.org")
        assertFalse(state.supported)
        assertEquals(
            "a JMAP server without the capability and a ManageSieve server without vacation, date or " +
                "relational are the same fact: the server said no",
            UnavailableCause.SERVER, state.unavailableCause,
        )
        assertEquals(R.string.settings_vacation_unsupported, vacationUnavailableText(state.unavailableCause))
    }

    @Test fun `an unreachable filter service is never worded as a server without filters`() {
        listOf(FiltersError.LOAD, FiltersError.SAVE, FiltersError.ACTIVATE).forEach { kind ->
            assertEquals(
                "$kind: a refused connection, a timeout or a firewall on port 4190 must say Sterna " +
                    "could not reach the service, not that the server has no filters",
                R.string.settings_filters_sieve_unreachable,
                filtersErrorText(kind, ManageSieveException.Kind.UNREACHABLE),
            )
        }
    }

    @Test fun `a filter service without a secure connection says the password was not sent`() {
        listOf(FiltersError.LOAD, FiltersError.SAVE, FiltersError.ACTIVATE).forEach { kind ->
            assertEquals(
                R.string.settings_filters_sieve_insecure,
                filtersErrorText(kind, ManageSieveException.Kind.INSECURE),
            )
        }
    }

    @Test fun `a refusal and a failure outside ManageSieve keep the generic error of their gesture`() {
        assertEquals(R.string.settings_vacation_load_error, filtersErrorText(FiltersError.LOAD, ManageSieveException.Kind.REFUSED))
        assertEquals(R.string.settings_vacation_save_error, filtersErrorText(FiltersError.SAVE, ManageSieveException.Kind.REFUSED))
        assertEquals(R.string.settings_vacation_load_error, filtersErrorText(FiltersError.LOAD, null))
        assertEquals(R.string.settings_vacation_save_error, filtersErrorText(FiltersError.SAVE, null))
        assertEquals(
            "a failed activation says so, never \"Couldn't save\": nothing was written",
            R.string.settings_filters_activate_error,
            filtersErrorText(FiltersError.ACTIVATE, ManageSieveException.Kind.REFUSED),
        )
        assertEquals(R.string.settings_filters_activate_error, filtersErrorText(FiltersError.ACTIVATE, null))
    }

    @Test fun `a text left open has its own sentence, whatever ManageSieve kind an earlier failure left`() {
        (ManageSieveException.Kind.values().toList() + null).forEach { sieveKind ->
            assertEquals(
                "TEXT_UNCUT / $sieveKind",
                R.string.settings_filters_text_uncut,
                filtersErrorText(FiltersError.TEXT_UNCUT, sieveKind),
            )
        }
    }

    @Test fun `a failed filters gesture carries the kind of its ManageSieve failure and the server's words`() {
        val refused = ManageSieveException(
            "PUTSCRIPT: NO line 3: unknown extension",
            ManageSieveException.Kind.REFUSED,
            serverText = "line 3: unknown extension",
        )
        val saved = filtersFailedState(FiltersUiState(saving = false), FiltersError.SAVE, refused)
        assertEquals(FiltersError.SAVE, saved.errorKind)
        assertEquals(ManageSieveException.Kind.REFUSED, saved.errorSieveKind)
        assertEquals(
            "a refusal shows what the server said, not the command line the client built around it",
            "line 3: unknown extension",
            saved.errorDetail,
        )
        assertEquals(R.string.settings_vacation_save_error, filtersErrorText(saved.errorKind!!, saved.errorSieveKind))

        val unreachable = ManageSieveException("ManageSieve connection failed", ManageSieveException.Kind.UNREACHABLE)
        val loaded = filtersFailedState(FiltersUiState(loading = false), FiltersError.LOAD, unreachable)
        assertEquals(
            R.string.settings_filters_sieve_unreachable,
            filtersErrorText(loaded.errorKind!!, loaded.errorSieveKind),
        )

        val other = filtersFailedState(FiltersUiState(), FiltersError.LOAD, IOException("offline"))
        assertEquals(null, other.errorSieveKind)
        assertEquals("offline", other.errorDetail)
    }

    @Test fun `a refusal whose server text is empty shows the exception's message, never an empty detail`() {
        listOf("", "   ", null).forEach { serverText ->
            val refused = ManageSieveException("AUTHENTICATE: NO", ManageSieveException.Kind.REFUSED, serverText = serverText)
            assertEquals(
                "NO with server text <$serverText> must fall back to the exception's message",
                "AUTHENTICATE: NO",
                filtersFailedState(FiltersUiState(), FiltersError.LOAD, refused).errorDetail,
            )
        }
    }

    @Test fun `an unreachable ManageSieve on the responder is never worded as a failed load or save`() {
        val unreachable = ManageSieveException(
            "ManageSieve connection failed: java.net.SocketTimeoutException: connect timed out",
            ManageSieveException.Kind.UNREACHABLE,
        )
        listOf(
            VacationError.LOAD to R.string.settings_vacation_load_error,
            VacationError.SAVE to R.string.settings_vacation_save_error,
        ).forEach { (kind, generic) ->
            val state = vacationFailedState(VacationUiState(loading = false), kind, unreachable)
            assertEquals(kind, state.errorKind)
            assertEquals("$kind: the responder state must keep the kind of its ManageSieve failure", ManageSieveException.Kind.UNREACHABLE, state.errorSieveKind)
            val sentence = vacationErrorText(kind, state.errorSieveKind)
            assertNotEquals("$kind: a timeout on port 4190 must not print the exception after \"Couldn't\"", generic, sentence)
            assertEquals(R.string.settings_filters_sieve_unreachable, sentence)
        }
    }

    @Test fun `a responder without a secure ManageSieve says the password was not sent`() {
        val insecure = ManageSieveException("the server does not offer STARTTLS", ManageSieveException.Kind.INSECURE)
        listOf(VacationError.LOAD, VacationError.SAVE).forEach { kind ->
            val state = vacationFailedState(VacationUiState(saving = false), kind, insecure)
            assertEquals(ManageSieveException.Kind.INSECURE, state.errorSieveKind)
            assertEquals("$kind", R.string.settings_filters_sieve_insecure, vacationErrorText(kind, state.errorSieveKind))
        }
    }

    @Test fun `a responder refusal shows the server's words and a failure outside ManageSieve keeps the generic error`() {
        val refused = ManageSieveException(
            "PUTSCRIPT: NO quota exceeded",
            ManageSieveException.Kind.REFUSED,
            serverText = "quota exceeded",
        )
        val loaded = vacationFailedState(VacationUiState(loading = false), VacationError.LOAD, refused)
        assertEquals(ManageSieveException.Kind.REFUSED, loaded.errorSieveKind)
        assertEquals("quota exceeded", loaded.errorDetail)
        assertEquals(R.string.settings_vacation_load_error, vacationErrorText(VacationError.LOAD, loaded.errorSieveKind))
        val saved = vacationFailedState(VacationUiState(saving = false), VacationError.SAVE, refused)
        assertEquals(R.string.settings_vacation_save_error, vacationErrorText(VacationError.SAVE, saved.errorSieveKind))

        val offline = vacationFailedState(VacationUiState(loading = false), VacationError.LOAD, IOException("offline"))
        assertEquals("a JMAP or network failure carries no ManageSieve kind", null, offline.errorSieveKind)
        assertEquals("offline", offline.errorDetail)
        assertEquals(R.string.settings_vacation_load_error, vacationErrorText(VacationError.LOAD, offline.errorSieveKind))
    }

    @Test fun `the responder and the filters word the same ManageSieve failure alike`() {
        val kinds = ManageSieveException.Kind.values().toList() + null
        listOf(
            FiltersError.LOAD to VacationError.LOAD,
            FiltersError.SAVE to VacationError.SAVE,
        ).forEach { (filters, vacation) ->
            kinds.forEach { sieveKind ->
                assertEquals(
                    "$vacation / $sieveKind",
                    filtersErrorText(filters, sieveKind),
                    vacationErrorText(vacation, sieveKind),
                )
            }
        }
        assertEquals(
            "a date check never reached the server: a kind left from an earlier failure must not word it",
            R.string.settings_vacation_invalid_dates,
            vacationErrorText(VacationError.INVALID_DATES, ManageSieveException.Kind.UNREACHABLE),
        )
        listOf(
            IOException("offline"),
            ManageSieveException("AUTHENTICATE: NO", ManageSieveException.Kind.REFUSED, serverText = " "),
            ManageSieveException("PUTSCRIPT: NO x", ManageSieveException.Kind.REFUSED, serverText = "x"),
            IllegalStateException(),
        ).forEach { failure ->
            assertEquals(
                "$failure",
                filtersFailedState(FiltersUiState(), FiltersError.LOAD, failure).errorDetail,
                vacationFailedState(VacationUiState(), VacationError.LOAD, failure).errorDetail,
            )
        }
    }

    // -- the resources ----------------------------------------------------------------------------

    @Test fun `the filters no longer blame the account type, the responder still does, in ten languages`() {
        val files = RES.listFiles().orEmpty()
            .filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) }
            .map { File(it, "strings.xml") }
            .filter { it.isFile }
            .sortedBy { it.parentFile.name }
        assertEquals(
            listOf(
                "values", "values-de", "values-es", "values-fr", "values-it",
                "values-nl", "values-pl", "values-pt", "values-ru", "values-zh-rCN",
            ),
            files.map { it.parentFile.name },
        )
        val wrong = files.flatMap { file ->
            val keys = Regex("<string\\s+name=\"([^\"]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toSet()
            listOfNotNull(
                "settings_filters_imap_account still present".takeIf { "settings_filters_imap_account" in keys },
                "settings_vacation_imap_account missing".takeIf { "settings_vacation_imap_account" !in keys },
                "settings_filters_sieve_unreachable missing".takeIf { "settings_filters_sieve_unreachable" !in keys },
                "settings_filters_sieve_insecure missing".takeIf { "settings_filters_sieve_insecure" !in keys },
                "settings_filters_text_uncut missing".takeIf { "settings_filters_text_uncut" !in keys },
            ).map { "${file.parentFile.name}: $it" }
        }
        assertEquals(emptyList<String>(), wrong)
        val withDetail = files.filter { file ->
            Regex("<string\\s+name=\"settings_filters_text_uncut\">(.*?)</string>")
                .find(file.readText())?.groupValues?.get(1).orEmpty().contains('%')
        }
        assertEquals(
            "settings_filters_text_uncut is drawn without a detail: a placeholder would show as is",
            emptyList<String>(),
            withDetail.map { it.parentFile.name },
        )
    }

    @Test fun `the unreachable sentence claims nothing about what the server supports`() {
        val text = Regex("<string\\s+name=\"settings_filters_sieve_unreachable\">(.*?)</string>")
            .find(File(RES, "values/strings.xml").readText())?.groupValues?.get(1).orEmpty()
        assertTrue("no English settings_filters_sieve_unreachable", text.isNotBlank())
        listOf("support", "not available", "doesn").forEach {
            assertFalse("'$it' in \"$text\": a firewall or the network can be the cause", it in text.lowercase())
        }
        assertTrue("the port and the protocol name stay as they are: \"$text\"", "ManageSieve" in text && "4190" in text)
    }

    // -- the wiring, read as text -------------------------------------------------------------

    @Test fun `the responder ViewModel decides the cause from the loaded state, not the protocol`() {
        assertTrue(
            "VacationViewModel must answer both unavailable states with " +
                "vacationUnavailableState(state = result, accountLabel = store.accountLabel())",
            Regex(
                "VacationState\\.Unsupported\\s*,\\s*VacationState\\.AccountUnsupported\\s*->\\s*" +
                    "_state\\.value\\s*=\\s*vacationUnavailableState\\(\\s*" +
                    "state\\s*=\\s*result\\s*,\\s*accountLabel\\s*=\\s*store\\.accountLabel\\(\\)\\s*,?\\s*\\)",
            ).containsMatchIn(text(VACATION_VM)),
        )
        assertFalse("the protocol no longer says why the responder is unavailable", "credentials.protocol" in text(VACATION_VM))
    }

    @Test fun `the filters ViewModel answers unsupported without the account's protocol`() {
        assertTrue(
            "FiltersViewModel must answer FilterRulesState.Unsupported with " +
                "filtersUnavailableState(accountLabel = store.accountLabel())",
            Regex(
                "FilterRulesState\\.Unsupported\\s*->\\s*\\{[^}]*?_state\\.value\\s*=\\s*filtersUnavailableState\\(\\s*" +
                    "accountLabel\\s*=\\s*store\\.accountLabel\\(\\)\\s*,?\\s*\\)",
            ).containsMatchIn(text(FILTERS_VM)),
        )
    }

    @Test fun `the filters ViewModel words each failure in its own function, through the pure decision`() {
        assertEquals(
            "the body of 'fun load()' must record its failure as exactly this one line, the kind " +
                "of that gesture and no other",
            listOf("_state.update { filtersFailedState(it.copy(loading = false), FiltersError.LOAD, t) }"),
            DeclarationSource.functionBody(FILTERS_VM, "fun load()").filter { "filtersFailedState(" in it },
        )
        // The save's failure goes through filtersSaveFailed, executed in FiltersTextRulesTest, which
        // keeps the edits on screen: pinned here is that it is the save's only fold, and what it folds.
        assertEquals(
            "the body of 'fun save()' must record its failures through filtersSaveFailed only, in " +
                "the catch that also takes a text that cannot be cut",
            listOf("_state.update { filtersSaveFailed(it, t) }"),
            DeclarationSource.functionBody(FILTERS_VM, "fun save()").filter { "filtersSaveFailed(" in it || "filtersFailedState(" in it },
        )
        assertEquals(
            "in 'fun save()', a text filtersWrite cannot cut must throw SieveTextUncut: it is the only " +
                "failure filtersSaveFailed words as TEXT_UNCUT",
            listOf("val write = filtersWrite(base, edited) ?: throw SieveTextUncut()"),
            DeclarationSource.functionBody(FILTERS_VM, "fun save()").filter { "filtersWrite(" in it },
        )
        assertTrue(
            "filtersSaveFailed must clear nothing but the spinner; the kind it picks is executed in FiltersTextRulesTest",
            "filtersFailedState(state.copy(saving = false), kind, failure)" in text(FILTERS_VM),
        )
        assertFalse("no error detail may be built outside filtersFailedState", "errorDetail =" in text(FILTERS_VM))
    }

    @Test fun `the responder ViewModel words each failure in its own function, through the pure decision`() {
        listOf(
            Triple("fun load()", "loading = false", "VacationError.LOAD"),
            Triple("fun save()", "saving = false", "VacationError.SAVE"),
        ).forEach { (signature, flag, kind) ->
            val failures = DeclarationSource.functionBody(VACATION_VM, signature).filter { "vacationFailedState(" in it }
            assertEquals(
                "the body of '$signature' must record its failure as exactly this one line, the kind " +
                    "of that gesture and no other",
                listOf("_state.update { vacationFailedState(it.copy($flag), $kind, t) }"),
                failures,
            )
        }
        assertFalse("no error detail may be built outside vacationFailedState", "errorDetail =" in text(VACATION_VM))
    }

    @Test fun `the responder screen words both failures through the table, with the ManageSieve kind`() {
        val screen = text(SETTINGS_SCREEN)
        assertTrue(
            "SettingsScreen's responder load error must keep Retry and word its sentence as " +
                "stringResource(vacationErrorText(VacationError.LOAD, state.errorSieveKind), state.errorDetail)",
            Regex(
                "state\\.errorKind\\s*==\\s*VacationError\\.LOAD\\s*->\\s*VacationNote\\(\\s*" +
                    "stringResource\\(vacationErrorText\\(VacationError\\.LOAD, state\\.errorSieveKind\\), state\\.errorDetail\\),\\s*" +
                    "onRetry\\s*=\\s*viewModel::load,",
            ).containsMatchIn(screen),
        )
        assertTrue(
            "SettingsScreen's responder save error must be " +
                "stringResource(vacationErrorText(VacationError.SAVE, state.errorSieveKind), state.errorDetail)",
            Regex(
                "if\\s*\\(state\\.errorKind\\s*==\\s*VacationError\\.SAVE\\)\\s*\\{\\s*Text\\(\\s*" +
                    "stringResource\\(vacationErrorText\\(VacationError\\.SAVE, state\\.errorSieveKind\\), state\\.errorDetail\\),",
            ).containsMatchIn(screen),
        )
        assertFalse(
            "no generic error sentence may be named in SettingsScreen outside vacationErrorText",
            "R.string.settings_vacation_load_error" in screen || "R.string.settings_vacation_save_error" in screen,
        )
    }

    @Test fun `the filters screen names the unsupported sentence once, in the unsupported branch`() {
        assertEquals(
            "settings_filters_unsupported says the server has no filters: it may only be drawn for " +
                "an unsupported state, never for a failure ManageSieve reported",
            listOf("!state.supported -> FiltersNote(stringResource(R.string.settings_filters_unsupported))"),
            DeclarationSource.kotlinLines(FILTERS_SCREEN).filter { "settings_filters_unsupported" in it },
        )
    }

    @Test fun `both screens choose their sentence from the state`() {
        assertTrue(
            "SettingsScreen must render the unsupported responder as " +
                "VacationNote(stringResource(vacationUnavailableText(state.unavailableCause)))",
            Regex(
                "!state\\.supported\\s*->\\s*VacationNote\\(\\s*stringResource\\(\\s*" +
                    "vacationUnavailableText\\(\\s*state\\.unavailableCause\\s*\\)\\s*\\)\\s*\\)",
            ).containsMatchIn(text(SETTINGS_SCREEN)),
        )
        val filters = text(FILTERS_SCREEN)
        assertTrue(
            "FiltersScreen's load error must keep Retry and word its sentence as " +
                "stringResource(filtersErrorText(FiltersError.LOAD, state.errorSieveKind), state.errorDetail)",
            Regex(
                "state\\.errorKind\\s*==\\s*FiltersError\\.LOAD\\s*->\\s*FiltersNote\\(\\s*" +
                    "stringResource\\(filtersErrorText\\(FiltersError\\.LOAD, state\\.errorSieveKind\\), state\\.errorDetail\\),\\s*" +
                    "onRetry\\s*=\\s*viewModel::load,",
            ).containsMatchIn(filters),
        )
        assertTrue(
            "FiltersScreen's line above Save must draw the SAVE kind with its detail, the TEXT_UNCUT kind " +
                "without one, the ACTIVATE kind with its detail, and nothing for LOAD or no error, as one when over state.errorKind",
            Regex(
                "when\\s*\\(state\\.errorKind\\)\\s*\\{\\s*" +
                    "FiltersError\\.SAVE\\s*->\\s*stringResource\\(filtersErrorText\\(FiltersError\\.SAVE, state\\.errorSieveKind\\), state\\.errorDetail\\)\\s*" +
                    "FiltersError\\.TEXT_UNCUT\\s*->\\s*stringResource\\(filtersErrorText\\(FiltersError\\.TEXT_UNCUT, state\\.errorSieveKind\\)\\)\\s*" +
                    "FiltersError\\.ACTIVATE\\s*->\\s*stringResource\\(filtersErrorText\\(FiltersError\\.ACTIVATE, state\\.errorSieveKind\\), state\\.errorDetail\\)\\s*" +
                    "FiltersError\\.LOAD,\\s*null\\s*->\\s*null\\s*" +
                    "\\}\\?\\.let\\s*\\{\\s*message\\s*->\\s*Text\\(\\s*message,",
            ).containsMatchIn(filters),
        )
        assertFalse(
            "no generic error sentence may be named in FiltersScreen outside filtersErrorText",
            "settings_vacation_load_error" in filters || "settings_vacation_save_error" in filters,
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
        val RES: File by lazy { File(root, "app/src/main/res") }
    }
}
