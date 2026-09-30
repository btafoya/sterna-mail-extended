package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, the last resort: neither `ComposeViewModel` nor `ComposeScreen` is instantiable on the
 * JVM. It pins the arguments they hand the recipient menu: the sender read from the picked "From"
 * without decrypting any credential, local sources only, the server book fed whatever the contacts
 * setting, her own addresses from every account, the chips of all three fields, and the menu
 * retired on a sender switch. What the menu does with them is executed in `RecipientSuggesterTest`,
 * `RecipientSuggestionsTest` and `RecipientRankingTest`.
 */
class RecipientSuggestionsWiringTest {

    private fun source(name: String): List<String> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
            ?: error("cannot locate the checkout from ${File("").absolutePath}")
        return File(root, "app/src/main/kotlin/app/sterna/ui/compose/$name").readLines()
            .map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") || it.isEmpty() }
    }

    private val viewModel: List<String> by lazy { source("ComposeViewModel.kt") }
    private val screen: List<String> by lazy { source("ComposeScreen.kt") }

    /** The lines from the one starting with [start] to where its braces close, or its parentheses too with [parens]. */
    private fun block(code: List<String>, start: String, parens: Boolean = false): List<String> {
        val at = code.indexOfFirst { it.startsWith(start) }
        check(at >= 0) { "no line starts with '$start' — did it get renamed?" }
        var depth = 0
        for (i in at until code.size) {
            depth += code[i].count { it == '{' || (parens && it == '(') } - code[i].count { it == '}' || (parens && it == ')') }
            if (depth == 0) return code.subList(at, i + 1)
        }
        error("'$start' never closes")
    }

    private val recipients: List<String> by lazy { block(viewModel, "private val recipients = RecipientSuggester(", parens = true) }

    @Test fun `the pool is read from the local copies only`() {
        assertEquals(
            listOf("repo.sending::sentContacts", "repo.sending::receivedContacts", "repo.accounts::serverBook"),
            recipients.flatMap { Regex("""repo\.\w+::\w+""").findAll(it).map { m -> m.value }.toList() },
        )
        assertEquals(1, viewModel.count { "RecipientSuggester(" in it })
    }

    @Test fun `the sender comes from the picked From, then the opened account, then the current id`() {
        assertTrue(
            "the suggester must be handed, as a whole line, " +
                "sender = { composingAccountId(_selectedFrom.value, accountId, store::currentId) },\n" +
                "the current id only, not store.load(), which decrypts the credentials. Block was:\n" +
                recipients.joinToString("\n"),
            "sender = { composingAccountId(_selectedFrom.value, accountId, store::currentId) }," in recipients,
        )
    }

    @Test fun `the contacts setting gates the phone's contacts and only them`() {
        assertEquals(
            "contactsEnabled may only decide the device argument; on anything else it would take the " +
                "server book away from someone who declined the phone's contacts",
            listOf("device = if (contactsEnabled.value) {"),
            recipients.filter { "contactsEnabled" in it },
        )
        assertTrue(
            "the phone's contacts are a blocking query, read on Dispatchers.IO",
            "{ withContext(Dispatchers.IO) { AndroidContacts.all(getApplication()) } }" in recipients,
        )
    }

    @Test fun `her addresses are those of every account, and the pool is ranked off the main thread`() {
        assertTrue("selves = ownAddresses(store.accounts(), store::identities)," in recipients)
        assertTrue("work = Dispatchers.Default," in recipients)
    }

    @Test fun `the field's text and the addresses on screen reach the menu untouched`() {
        assertEquals(
            listOf(
                "fun suggest(fieldValue: String, onScreen: Set<String>) {",
                "recipients.suggest(fieldValue, onScreen)",
                "}",
            ),
            block(viewModel, "fun suggest("),
        )
        assertEquals(
            listOf(
                "fun recipientFieldFocused(fieldValue: String, onScreen: Set<String>) {",
                "recipients.focused(fieldValue, onScreen)",
                "}",
            ),
            block(viewModel, "fun recipientFieldFocused("),
        )
    }

    @Test fun `switching the phone's contacts on or off rereads the pool`() {
        assertEquals(
            "the device argument is read when the pool loads: without this watcher, the setting turned on " +
                "by the priming sheet while To holds the focus brings no phone contact until the next focus",
            listOf("viewModelScope.launch { contactsEnabled.drop(1).collect { recipients.sourcesChanged() } }"),
            viewModel.filter { "contactsEnabled" in it && "recipients." in it },
        )
    }

    @Test fun `picking another sender retires the menu`() {
        val select = block(viewModel, "suspend fun selectFrom(")
        val set = select.indexOf("_selectedFrom.value = option")
        assertTrue("selectFrom no longer sets the sender — did it move?", set >= 0)
        assertEquals("recipients.senderChanged()", select[set + 1])
    }

    @Test fun `dismissing the menu goes through the suggester`() {
        assertEquals(listOf("fun clearSuggestions() {", "recipients.clear()", "}"), block(viewModel, "fun clearSuggestions("))
    }

    @Test fun `the screen hands the chips of all three fields, at every keystroke and every focus`() {
        assertEquals(
            listOf("val onScreen = { recipientsOnScreen(to, cc, bcc) }"),
            screen.filter { "recipientsOnScreen(" in it },
        )
        assertEquals(
            listOf(
                "val suggestRecipients: (String) -> Unit = { viewModel.suggest(it, onScreen()) }",
                "val recipientFieldFocused: (String) -> Unit = { viewModel.recipientFieldFocused(it, onScreen()) }",
            ),
            screen.filter { "viewModel.suggest(" in it || "viewModel::suggest" in it || "viewModel.recipientFieldFocused" in it || "viewModel::recipientFieldFocused" in it },
        )
    }

    @Test fun `each of the three fields suggests and reports its focus through those two`() {
        assertEquals(listOf("onSuggest = suggestRecipients,"), screen.filter { it.startsWith("onSuggest =") })
        assertEquals(listOf("onFocused = recipientFieldFocused,"), screen.filter { it.startsWith("onFocused =") })
        assertEquals(
            List(2) { "suggestRecipients, recipientFieldFocused, viewModel::clearSuggestions, missingKey = missingKeyFor," },
            screen.filter { it.startsWith("suggestRecipients, ") },
        )
        assertEquals(3, screen.count { it.startsWith("RecipientChipsField(") })
    }

    @Test fun `the focus is reported when it is gained, not at each recomposition`() {
        val focus = screen.indexOf("if (fs.isFocused) {")
        assertTrue("RecipientChipsField no longer tests fs.isFocused — did it move?", focus >= 0)
        assertEquals(listOf("if (!wasFocused) onFocused(input)", "wasFocused = true"), screen.subList(focus + 1, focus + 3))
    }
}
