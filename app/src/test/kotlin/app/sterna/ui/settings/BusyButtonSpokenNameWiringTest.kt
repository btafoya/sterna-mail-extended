package app.sterna.ui.settings

import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, and a last resort: nothing in this module composes, so no accessibility tree is
 * built here and no screen reader was listened to. What is pinned is that the four spinners which
 * stand in for a button's label while it saves or signs in carry a `contentDescription`. Unnamed,
 * the spinner leaves a `Button` with nothing to say but its role, and the one of `SaveAction`,
 * which replaces the whole `IconButton`, is a progress bar that does not say what is in progress.
 *
 * The string is read into a `val` above the spinner because `stringResource` is composable and the
 * `semantics` block is not. Inside a `Button` the description is expected to merge into the
 * button's own node, the one a reader focuses; in `SaveAction` the spinner is its own node.
 *
 * Holes of `CONTRIBUTING.md` that apply: 1 (`savingLabel` and `connecting` are only names: the
 * `val` is pinned on the line above the spinner, but nothing proves the block reads that one rather
 * than a rebinding), 3 (each window is found at the first busy test of its function, `if (saving)`
 * or its like, and pinned from there: a second spinner added elsewhere in the function is only
 * seen by the count, and a branch drawn around the button is not seen at all), and 4 (the name the
 * node ends up exposing, merged into the button or not, is never observed, nor is the wording of
 * `settings_saving` and `connect_connecting`, which are read by name only).
 *
 * Not covered: every other `CircularProgressIndicator` and `LoadingRing()` of these three files.
 */
class BusyButtonSpokenNameWiringTest {

    @Test fun `the vacation Save button says it is saving`() {
        assertEquals(
            "the spinner of VacationForm's Save button must carry `settings_saving`",
            listOf(
                "if (state.saving) {",
                "val savingLabel = stringResource(R.string.settings_saving)",
                "CircularProgressIndicator(",
                "modifier = Modifier.width(20.dp).semantics { contentDescription = savingLabel },",
                "strokeWidth = 2.dp,",
                "color = MaterialTheme.colorScheme.onPrimary,",
                ")",
                "} else {",
                "Text(stringResource(R.string.settings_vacation_save))",
            ),
            window(SETTINGS, "private fun VacationForm(", "if (state.saving) {", 9),
        )
        assertEquals(1, spinners(SETTINGS, "private fun VacationForm("))
    }

    @Test fun `the filters Save action says it is saving`() {
        assertEquals(
            "the spinner that replaces SaveAction's IconButton must carry `settings_saving`",
            listOf(
                "if (saving) {",
                "val savingLabel = stringResource(R.string.settings_saving)",
                "CircularProgressIndicator(",
                "modifier = Modifier.padding(horizontal = 12.dp).size(20.dp).semantics { contentDescription = savingLabel },",
                "strokeWidth = 2.dp,",
                ")",
                "} else {",
                "IconButton(onClick = onClick, enabled = enabled) {",
            ),
            window(FILTERS, "private fun SaveAction(", "if (saving) {", 8),
        )
        assertEquals(1, spinners(FILTERS, "private fun SaveAction("))
    }

    @Test fun `the imported account's OAuth button says it is connecting`() {
        assertEquals(
            "the spinner of ImportOAuthPanel's sign-in button must carry `connect_connecting`",
            CONNECTING + "Text(stringResource(R.string.connect_import_signin_microsoft))",
            window(CONNECT, "private fun ImportOAuthPanel(", "if (target.verifying) {", 5),
        )
        assertEquals(1, spinners(CONNECT, "private fun ImportOAuthPanel("))
    }

    @Test fun `the imported account's password button says it is connecting`() {
        assertEquals(
            "the spinner of ImportSignInPanel's sign-in button must carry `connect_connecting`",
            CONNECTING + "Text(stringResource(R.string.connect_import_signin_button))",
            window(CONNECT, "private fun ImportSignInPanel(", "if (target.verifying) {", 5),
        )
        assertEquals(1, spinners(CONNECT, "private fun ImportSignInPanel("))
    }

    private fun window(path: String, signature: String, busyTest: String, size: Int): List<String> {
        val lines = body(path, signature)
        val at = lines.indexOf(busyTest)
        return if (at < 0) emptyList() else lines.subList(at, minOf(at + size, lines.size))
    }

    private fun spinners(path: String, signature: String): Int =
        body(path, signature).count { it.startsWith("CircularProgressIndicator(") }

    private fun body(path: String, signature: String): List<String> =
        DeclarationSource.functionBody(DeclarationSource.file(path), signature)

    private companion object {
        const val SETTINGS = "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt"
        const val FILTERS = "app/src/main/kotlin/app/sterna/ui/settings/FiltersScreen.kt"
        const val CONNECT = "app/src/main/kotlin/app/sterna/ui/connect/ConnectScreen.kt"

        val CONNECTING = listOf(
            "if (target.verifying) {",
            "val connecting = stringResource(R.string.connect_connecting)",
            "CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp).semantics { contentDescription = connecting })",
            "} else {",
        )
    }
}
