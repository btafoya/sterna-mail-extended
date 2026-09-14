package app.sterna.ui.settings

import app.sterna.R
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.imap.ManageSieveException
import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Which sentence the responder and filters screens show when they cannot offer their form, or when
 * a filters load or save fails (#211). The responder is still JMAP only, so `loadVacation` answers
 * the same bare `Unsupported` for an IMAP account and for a JMAP server without the capability, and
 * the account's protocol tells them apart. The filters of a password IMAP account go through
 * ManageSieve, so their `Unsupported` no longer depends on the protocol, and a failed ManageSieve
 * dialogue is worded by the kind of its `ManageSieveException`. The first half of this class executes
 * those decisions. The second half is a source lint, same instrument and same disclaimer as
 * [SettingsScreenHonestyTest]: it only proves the ViewModels and screens call the decisions with
 * the right arguments, since neither is instantiable in this module.
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

    @Test fun `an unreachable filter service is never worded as a server without filters`() {
        listOf(FiltersError.LOAD, FiltersError.SAVE).forEach { kind ->
            assertEquals(
                "$kind: a refused connection, a timeout or a firewall on port 4190 must say Sterna " +
                    "could not reach the service, not that the server has no filters",
                R.string.settings_filters_sieve_unreachable,
                filtersErrorText(kind, ManageSieveException.Kind.UNREACHABLE),
            )
        }
    }

    @Test fun `a filter service without a secure connection says the password was not sent`() {
        listOf(FiltersError.LOAD, FiltersError.SAVE).forEach { kind ->
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
            ).map { "${file.parentFile.name}: $it" }
        }
        assertEquals(emptyList<String>(), wrong)
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
        listOf(
            Triple("fun load()", "loading = false", "FiltersError.LOAD"),
            Triple("fun save()", "saving = false", "FiltersError.SAVE"),
        ).forEach { (signature, flag, kind) ->
            val failures = DeclarationSource.functionBody(FILTERS_VM, signature).filter { "filtersFailedState(" in it }
            assertEquals(
                "the body of '$signature' must record its failure as exactly this one line, the kind " +
                    "of that gesture and no other",
                listOf("_state.update { filtersFailedState(it.copy($flag), $kind, t) }"),
                failures,
            )
        }
        assertFalse("no error detail may be built outside filtersFailedState", "errorDetail =" in text(FILTERS_VM))
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
            "FiltersScreen's save error must be " +
                "stringResource(filtersErrorText(FiltersError.SAVE, state.errorSieveKind), state.errorDetail)",
            "stringResource(filtersErrorText(FiltersError.SAVE, state.errorSieveKind), state.errorDetail)," in filters,
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
