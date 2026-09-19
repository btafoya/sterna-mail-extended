package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, the last resort: `ComposeViewModel` is not instantiable on the JVM. It pins the
 * arguments `ComposeViewModel` hands the recipient menu: the sender read from the picked "From" at
 * each keystroke without decrypting any credential, the server book fed whatever the contacts
 * permission, and the menu retired on a sender switch. What the menu does with them is executed in
 * `RecipientSuggesterTest` and `RecipientSuggestionsTest`.
 */
class RecipientSuggestionsWiringTest {

    private val code: List<String> by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
            ?: error("cannot locate the checkout from ${File("").absolutePath}")
        File(root, "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt").readLines()
            .map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") || it.isEmpty() }
    }

    /** The lines of `fun [name](`, its signature to its closing brace. */
    private fun body(name: String): List<String> {
        val start = code.indexOfFirst { it.startsWith("fun $name(") || it.startsWith("suspend fun $name(") }
        check(start >= 0) { "ComposeViewModel.kt declares no '$name(' — did it get renamed?" }
        var depth = 0
        for (i in start until code.size) {
            depth += code[i].count { it == '{' } - code[i].count { it == '}' }
            if (depth == 0) return code.subList(start, i + 1)
        }
        error("'$name(' never closes")
    }

    @Test fun `the menu is built over the seen addresses and the server book, nothing else`() {
        assertEquals(
            listOf(
                "private val recipients = RecipientSuggester(viewModelScope, repo.sending::suggestContacts, " +
                    "repo.accounts::serverContacts)",
            ),
            code.filter { "RecipientSuggester(" in it },
        )
    }

    @Test fun `each keystroke reads the sender from the picked From, then the opened account, then the current id`() {
        assertTrue(
            "suggest() must hand the menu, as a whole line, " +
                "sender = { composingAccountId(_selectedFrom.value, accountId, store::currentId) },\n" +
                "the picked From first, and the current id only, not store.load(), which decrypts " +
                "the credentials at each keystroke. Body was:\n${body("suggest").joinToString("\n")}",
            "sender = { composingAccountId(_selectedFrom.value, accountId, store::currentId) }," in body("suggest"),
        )
    }

    @Test fun `the contacts permission gates the device contacts and only them`() {
        val suggest = body("suggest")
        assertEquals(
            "contactsEnabled may only decide the device argument of suggest(); on anything else it " +
                "would take the server book away from someone who declined the phone's contacts",
            listOf("device = if (contactsEnabled.value) {"),
            suggest.filter { "contactsEnabled" in it },
        )
    }

    @Test fun `picking another sender retires the menu`() {
        val select = body("selectFrom")
        val set = select.indexOf("_selectedFrom.value = option")
        assertTrue("selectFrom no longer sets the sender — did it move?", set >= 0)
        assertEquals("recipients.senderChanged()", select[set + 1])
    }

    @Test fun `dismissing the menu goes through the suggester`() {
        assertEquals(listOf("fun clearSuggestions() {", "recipients.clear()", "}"), body("clearSuggestions"))
    }
}
