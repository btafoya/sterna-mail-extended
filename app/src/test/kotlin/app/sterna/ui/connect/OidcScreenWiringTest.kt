package app.sterna.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test. It reads `ConnectScreen.kt` as text, for the two fields and
 * the button of a sign-in at an issuer the user typed (#249).
 *
 * Why a lint: a Composable is not instantiated in a JVM test. What the button decides is executed
 * by `OAuthButtonTest`; what is left here is that the screen asks that decision, for both the tap
 * and the dead state, and hands the view model what it answered.
 *
 * What it does not cover:
 * - Behaviour. A line can be present and dead; only the device passes see the fields and the tap.
 * - What encloses the two runs below. Each starts at its own guard, and nothing reads the
 *   conditions above it: the advanced block moved out of the JMAP branch stays green.
 * - The strings. `TranslationParityTest` counts them; nothing checks what they say.
 * - A value reaching the automatic sign-in through a name this file does not know. The census
 *   below reads four identifiers; a fifth, holding a copy of the issuer, passes.
 *
 * Lines are compared whole and trimmed, never by `contains`: a line that is lengthened keeps
 * containing its old text. The two blocks are compared as runs of adjacent lines, because every
 * line of a text field also fits the field next to it.
 */
class OidcScreenWiringTest {

    private val screen: List<String> = SCREEN.readLines().map { it.trim() }

    private fun assertOnce(line: String, why: String) {
        assertEquals(
            "ConnectScreen.kt must carry this whole line exactly once:\n  $line\n$why",
            1, screen.count { it == line },
        )
    }

    private fun assertRun(run: List<String>, why: String) {
        val starts = screen.indices.filter { screen[it] == run.first() }
        assertEquals(
            "ConnectScreen.kt must carry this whole line exactly once, as the head of a run:\n  ${run.first()}",
            1, starts.size,
        )
        assertEquals(
            "ConnectScreen.kt must carry these whole lines, adjacent and in this order. $why",
            run.joinToString("\n"),
            screen.drop(starts.single()).take(run.size).joinToString("\n"),
        )
    }

    /**
     * The run ends on the two braces that close `if (!useApiToken)` and `if (showAdvanced)`: the
     * fields and their help line are inside both, each field with its own value and its own label.
     */
    @Test fun `the two fields and their help sit under the advanced settings, hidden behind an API token`() {
        assertRun(
            ADVANCED,
            "A field showing one value and writing or naming the other compiles, and so does a " +
                "field drawn while an API token is the credential.",
        )
    }

    /**
     * The button's own guard heads the run: without `!useApiToken` an issuer still held by the
     * form starts a browser sign-in while an API token is what the user chose. The `onClick` is
     * the `when` and nothing else, so no second call rides along with an arm.
     */
    @Test fun `the button asks the decision for its tap and for its dead state`() {
        assertRun(
            BUTTON,
            "`connectOAuth` on the typed-issuer arm compiles and never contacts the issuer; " +
                "`!busy && username.isNotBlank()` leaves a live button whose tap does nothing; " +
                "the two fields are both Strings and a swap compiles.",
        )
    }

    @Test fun `the two fields are saved`() {
        assertOnce("var issuer by rememberSaveable { mutableStateOf(\"\") }", "Lost on rotation otherwise.")
        assertOnce("var clientId by rememberSaveable { mutableStateOf(\"\") }", "Lost on rotation otherwise.")
    }

    /**
     * Neither field is ever blanked, and the automatic sign-in runs at a step where neither is on
     * screen: typed for one address, they must not follow the next one there.
     */
    @Test fun `the automatic sign-in reads neither field`() {
        assertEquals(
            "startHandover must be these two statements and nothing else.",
            listOf(
                "fun startHandover() {",
                "handoverFor = username.trim()",
                "viewModel.connectOAuth(username, NO_TYPED_SERVER, accountName)",
                "}",
            ),
            functionBody("startHandover"),
        )
    }

    @Test fun `the typed values are read on the manual form's lines and nowhere else`() {
        val known = (ADVANCED + BUTTON + SAVED).toSet()
        val strays = screen.filter { TYPED.containsMatchIn(it) && it !in known }
        assertEquals(
            "ConnectScreen.kt reads the issuer, the client id or the button's decision on a line " +
                "this lint does not know. If it belongs to the manual form, add it to a run here; " +
                "anywhere else it carries a value typed for another address. Found:\n" +
                strays.joinToString("\n"),
            emptyList<String>(), strays,
        )
        assertTrue("the census matched nothing: its pattern is stale", screen.any { TYPED.containsMatchIn(it) })
    }

    private fun functionBody(name: String): List<String> {
        val start = screen.indexOfFirst { Regex("""\bfun $name\b""").containsMatchIn(it) }
        check(start >= 0) { "$name() is gone from ConnectScreen.kt: rename it here too, this lint reads nothing." }
        val out = mutableListOf<String>()
        var depth = 0
        for (line in screen.drop(start)) {
            out += line
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth <= 0) break
        }
        return out
    }

    private companion object {
        val SCREEN: File = "app/src/main/kotlin/app/sterna/ui/connect/ConnectScreen.kt".let { path ->
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, path).isFile }
                ?.let { File(it, path) }
                ?: error(
                    "cannot locate $path from ${File("").absolutePath}: this test reads a source " +
                        "file as text and needs a working directory inside the checkout",
                )
        }

        val TYPED = Regex("""\b(issuer|clientId|oauthButton|connectOidc)\b""")

        val SAVED = listOf(
            "var issuer by rememberSaveable { mutableStateOf(\"\") }",
            "var clientId by rememberSaveable { mutableStateOf(\"\") }",
        )

        val ADVANCED = listOf(
            "if (showAdvanced) {",
            "OutlinedTextField(",
            "value = server,",
            "onValueChange = { server = it },",
            "label = { Text(stringResource(R.string.connect_jmap_server)) },",
            "placeholder = { Text(stringResource(R.string.connect_jmap_server_placeholder)) },",
            "singleLine = true,",
            "modifier = Modifier.fillMaxWidth(),",
            ")",
            "if (!useApiToken) {",
            "OutlinedTextField(",
            "value = issuer,",
            "onValueChange = { issuer = it },",
            "label = { Text(stringResource(R.string.connect_oidc_issuer)) },",
            "placeholder = { Text(stringResource(R.string.connect_oidc_issuer_placeholder)) },",
            "singleLine = true,",
            "keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),",
            "modifier = Modifier.fillMaxWidth(),",
            ")",
            "OutlinedTextField(",
            "value = clientId,",
            "onValueChange = { clientId = it },",
            "label = { Text(stringResource(R.string.connect_oidc_client_id)) },",
            "singleLine = true,",
            "keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),",
            "modifier = Modifier.fillMaxWidth(),",
            ")",
            "Text(",
            "stringResource(R.string.connect_oidc_hint),",
            "style = MaterialTheme.typography.bodySmall,",
            "color = MaterialTheme.colorScheme.onSurfaceVariant,",
            ")",
            "}",
            "}",
        )

        val BUTTON = listOf(
            "if (protocol == MailProtocol.JMAP && !useApiToken) {",
            "val oauthButton = oauthButtonFor(issuer, clientId)",
            "TextButton(",
            "onClick = {",
            "when (oauthButton) {",
            "OAuthButton.ServerOAuth -> viewModel.connectOAuth(username, server, accountName)",
            "is OAuthButton.TypedIssuer -> viewModel.connectOidc(username, server, oauthButton.issuer, oauthButton.clientId, accountName)",
            "OAuthButton.NeedsClientId -> Unit",
            "}",
            "},",
            "enabled = oauthButtonEnabled(oauthButton, busy, username),",
            "modifier = Modifier.fillMaxWidth(),",
            ") { Text(stringResource(R.string.connect_oauth_button)) }",
            "}",
        )
    }
}
